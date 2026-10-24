package com.nightmare.sunshine.dlna;

import android.util.Log;

import com.nightmare.sunshine.dlna.DlnaUpnp.PlaybackSnapshot;
import com.nightmare.sunshine.dlna.DlnaUpnp.Renderer;

public final class DlnaPlaybackMonitor {
    private static final long POLL_MS = 2000;

    private final Renderer renderer;
    private final String expectedUrl;
    private final Runnable onDrift;
    private volatile Thread worker;

    public DlnaPlaybackMonitor(Renderer renderer, String expectedUrl, Runnable onDrift) {
        if (renderer == null || expectedUrl == null || expectedUrl.isEmpty() || onDrift == null) {
            throw new IllegalArgumentException("Renderer, expected URL and callback are required");
        }
        this.renderer = renderer;
        this.expectedUrl = expectedUrl;
        this.onDrift = onDrift;
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
        DriftTracker tracker = new DriftTracker(expectedUrl);
        long started = System.nanoTime();
        try {
            while (isRunning()) {
                long pollStarted = System.nanoTime();
                PlaybackSnapshot snapshot = DlnaUpnp.getPlaybackSnapshot(renderer);
                if (!isRunning()) {
                    break;
                }
                long nowMs = (System.nanoTime() - started) / 1_000_000L;
                if (tracker.observe(nowMs, snapshot == null ? null : snapshot.transportState,
                        snapshot == null ? -1 : snapshot.positionMs, snapshot == null ? null : snapshot.mediaUri)) {
                    PlaybackSnapshot confirmed = DlnaUpnp.getPlaybackSnapshot(renderer);
                    if (confirmed != null && "PLAYING".equals(confirmed.transportState)
                            && confirmed.positionMs >= snapshot.positionMs
                            && expectedUrl.equals(confirmed.mediaUri)
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
            }
        }
    }

    static final class DriftTracker {
        private static final long WARMUP_MS = 10000;
        private static final long DRIFT_MS = 2000;
        private static final long COOLDOWN_MS = 20000;

        private final String expectedUrl;
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

        DriftTracker(String expectedUrl) {
            this.expectedUrl = expectedUrl;
        }

        boolean observe(long nowMs, String state, long positionMs, String mediaUri) {
            if (mediaUri != null && !mediaUri.isEmpty()) {
                if (!mediaUri.equals(expectedUrl)) {
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
            if ((recovered && nowMs - lastRecovery < COOLDOWN_MS) || offset - bestOffset < DRIFT_MS) {
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
