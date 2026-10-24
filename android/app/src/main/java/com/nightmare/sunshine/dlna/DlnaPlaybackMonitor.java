package com.nightmare.sunshine.dlna;

import android.util.Log;

import com.nightmare.sunshine.dlna.DlnaUpnp.PlaybackSnapshot;
import com.nightmare.sunshine.dlna.DlnaUpnp.Renderer;

public final class DlnaPlaybackMonitor {
    private static final long POLL_MS = 2000;

    private final Renderer renderer;
    private volatile String expectedUrl;
    private final Runnable onDrift;
    private final long driftMs;
    private final long cooldownMs;
    private volatile DriftTracker tracker;
    private volatile Thread worker;

    public DlnaPlaybackMonitor(Renderer renderer, String expectedUrl, Runnable onDrift) {
        this(renderer, expectedUrl, 2_000L, 20_000L, onDrift);
    }

    public DlnaPlaybackMonitor(Renderer renderer, String expectedUrl, long driftMs,
            long cooldownMs, Runnable onDrift) {
        if (renderer == null || expectedUrl == null || expectedUrl.isEmpty() || onDrift == null) {
            throw new IllegalArgumentException("Renderer, expected URL and callback are required");
        }
        if (driftMs <= 0 || cooldownMs <= 0) {
            throw new IllegalArgumentException("Drift threshold and cooldown must be positive");
        }
        this.renderer = renderer;
        this.expectedUrl = expectedUrl;
        this.driftMs = driftMs;
        this.cooldownMs = cooldownMs;
        this.onDrift = onDrift;
    }

    /**
     * Live recovery re-pushes the stream under a varied URL. The receiver may
     * report either form, so match on the query-insensitive key and keep the
     * tracker's expectation in step with what was actually pushed.
     */
    public synchronized void updateExpectedUrl(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        expectedUrl = url;
        DriftTracker current = tracker;
        if (current != null) {
            current.updateExpectedUrl(url);
        }
    }

    /**
     * Recovery re-pushes vary only the throwaway "r" query parameter; any
     * other query difference (or path difference) is genuinely different
     * content. Compare on the URI minus that parameter.
     */
    static String mediaUriKey(String uri) {
        if (uri == null) {
            return null;
        }
        int query = uri.indexOf('?');
        if (query < 0) {
            return uri;
        }
        String base = uri.substring(0, query);
        StringBuilder kept = new StringBuilder();
        for (String pair : uri.substring(query + 1).split("&")) {
            if (pair.isEmpty() || pair.equals("r") || pair.startsWith("r=")) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(pair);
        }
        return kept.length() == 0 ? base : base + '?' + kept;
    }

    static boolean sameMediaUri(String reported, String expected) {
        String reportedKey = mediaUriKey(reported);
        return reportedKey != null && reportedKey.equals(mediaUriKey(expected));
    }

    public synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(this::monitor, "DlnaPlaybackMonitor");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        Thread previous = worker;
        worker = null;
        if (previous != null) {
            previous.interrupt();
        }
    }

    private boolean isRunning() {
        return worker == Thread.currentThread() && !Thread.currentThread().isInterrupted();
    }

    private void monitor() {
        DriftTracker localTracker = new DriftTracker(expectedUrl, driftMs, cooldownMs);
        tracker = localTracker;
        long started = System.nanoTime();
        try {
            while (isRunning()) {
                long pollStarted = System.nanoTime();
                PlaybackSnapshot snapshot = DlnaUpnp.getPlaybackSnapshot(renderer);
                if (!isRunning()) {
                    break;
                }
                long nowMs = (System.nanoTime() - started) / 1_000_000L;
                if (localTracker.observe(nowMs, snapshot == null ? null : snapshot.transportState,
                        snapshot == null ? -1 : snapshot.positionMs, snapshot == null ? null : snapshot.mediaUri)) {
                    PlaybackSnapshot confirmed = DlnaUpnp.getPlaybackSnapshot(renderer);
                    if (confirmed != null && "PLAYING".equals(confirmed.transportState)
                            && confirmed.positionMs >= snapshot.positionMs
                            && sameMediaUri(confirmed.mediaUri, localTracker.expectedUrl())
                            && isRunning()) {
                        onDrift.run();
                    }
                }
                if (isRunning()) {
                    Thread.sleep(Math.max(1, POLL_MS - (System.nanoTime() - pollStarted) / 1_000_000L));
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            Log.w("DlnaPlaybackMonitor", "Monitoring disabled: " + failure.getMessage());
        } finally {
            synchronized (this) {
                if (worker == Thread.currentThread()) {
                    worker = null;
                }
                if (tracker == localTracker) {
                    tracker = null;
                }
            }
        }
    }

    static final class DriftTracker {
        private static final long WARMUP_MS = 10000;

        private String expectedUrl;
        private final long driftMs;
        private final long cooldownMs;
        private boolean initialized;
        private boolean advancing;
        private boolean stabilized;
        private boolean foreignMedia;
        private boolean recovered;
        private long warmupStarted;
        private long previousWall;
        private long previousPosition;
        private long bestOffset;
        private long lastRecovery;
        private int deficitSamples;

        DriftTracker(String expectedUrl, long driftMs, long cooldownMs) {
            this.expectedUrl = expectedUrl;
            this.driftMs = driftMs;
            this.cooldownMs = cooldownMs;
        }

        void updateExpectedUrl(String url) {
            this.expectedUrl = url;
        }

        String expectedUrl() {
            return expectedUrl;
        }

        boolean observe(long nowMs, String state, long positionMs, String mediaUri) {
            if (mediaUri != null && !mediaUri.isEmpty()) {
                if (!sameMediaUri(mediaUri, expectedUrl)) {
                    foreignMedia = true;
                    reset();
                    return false;
                }
                if (foreignMedia) {
                    foreignMedia = false;
                    reset();
                }
            }
            if (nowMs < 0 || !"PLAYING".equals(state) || positionMs < 0
                    || mediaUri == null || mediaUri.isEmpty() || foreignMedia) {
                reset();
                return false;
            }
            if (initialized && (nowMs <= previousWall || positionMs < previousPosition)) {
                reset();
            }
            if (!initialized) {
                initialized = true;
                warmupStarted = nowMs;
                previousWall = nowMs;
                previousPosition = positionMs;
                return false;
            }
            advancing |= positionMs > previousPosition;
            previousWall = nowMs;
            previousPosition = positionMs;
            long offset = nowMs - positionMs;
            if (!stabilized) {
                if (advancing && nowMs - warmupStarted >= WARMUP_MS) {
                    stabilized = true;
                    bestOffset = offset;
                }
                return false;
            }
            bestOffset = Math.min(bestOffset, offset);
            if ((recovered && nowMs - lastRecovery < cooldownMs) || offset - bestOffset < driftMs) {
                deficitSamples = 0;
                return false;
            }
            if (++deficitSamples < 2) {
                return false;
            }
            recovered = true;
            lastRecovery = nowMs;
            reset();
            return true;
        }

        private void reset() {
            initialized = false;
            advancing = false;
            stabilized = false;
            deficitSamples = 0;
        }
    }
}
