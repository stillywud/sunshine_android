package com.nightmare.sunshine.dlna;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

final class DlnaHlsStore {
    static final String MIME_TYPE = "application/vnd.apple.mpegurl";
    private static final long CLOCK_MASK = (1L << 33) - 1;
    private static final long MIN_SEGMENT_TICKS = 36_000;
    private static final long MAX_SEGMENT_TICKS = 135_000;
    private static final long PLAYLIST_TICKS = 360_000;
    private static final long MIN_PLAYLIST_TICKS = 270_000;
    private static final int MAX_SEGMENT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_HISTORY_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SEGMENTS = 32;
    private static final long MAX_PLAYLIST_AGE_NANOS = 2_000_000_000L;

    private static final class Segment {
        final long sequence;
        final long durationTicks;
        final byte[] bytes;
        long longestPlaylistTicks;
        long retainUntilNanos = Long.MAX_VALUE;

        Segment(long sequence, long durationTicks, byte[] bytes) {
            this.sequence = sequence;
            this.durationTicks = durationTicks;
            this.bytes = bytes;
        }
    }

    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private final LongSupplier nanoClock;
    private ByteArrayOutputStream current;
    private long currentClock;
    private long nextSequence;
    private long epochFirstSequence;
    private long discontinuitySequence;
    private long lastCompletedNanos;
    private int retainedBytes;
    private boolean stopped;
    private boolean capacityBlocked;

    DlnaHlsStore() {
        this(System::nanoTime);
    }

    DlnaHlsStore(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    synchronized void append(byte[] data, boolean keyframe) {
        if (stopped || data.length == 0) { return; }
        long clock = keyframe ? readClock(data) : -1;
        if (keyframe && clock >= 0) {
            if (current != null) {
                long duration = (clock - currentClock) & CLOCK_MASK;
                if (duration >= MAX_SEGMENT_TICKS) {
                    resetEpoch();
                } else if (duration >= MIN_SEGMENT_TICKS) {
                    long now = nanoClock.getAsLong();
                    if (reserve(current.size(), now)) {
                        byte[] bytes = current.toByteArray();
                        segments.addLast(new Segment(nextSequence++, duration, bytes));
                        retainedBytes += bytes.length;
                        lastCompletedNanos = now;
                        current = null;
                        capacityBlocked = false;
                        retireOutsideWindow(now);
                        notifyAll();
                    } else {
                        resetEpoch();
                        capacityBlocked = true;
                    }
                }
            }
            if (current == null) {
                current = new ByteArrayOutputStream();
                currentClock = clock;
            }
        }
        if (current != null) {
            if (data.length > MAX_SEGMENT_BYTES - current.size()) {
                resetEpoch();
            } else {
                current.write(data, 0, data.length);
            }
        }
    }

    private void resetEpoch() {
        current = null;
        epochFirstSequence = nextSequence;
        discontinuitySequence++;
        retireBefore(nextSequence, nanoClock.getAsLong());
        notifyAll();
    }

    private boolean reserve(int bytes, long now) {
        while (segments.size() >= MAX_SEGMENTS || retainedBytes > MAX_HISTORY_BYTES - bytes) {
            Segment oldest = segments.peekFirst();
            if (oldest == null || (oldest.longestPlaylistTicks > 0
                    && (oldest.retainUntilNanos == Long.MAX_VALUE || now - oldest.retainUntilNanos < 0))) {
                return false;
            }
            retainedBytes -= segments.removeFirst().bytes.length;
        }
        return true;
    }

    private void retireOutsideWindow(long now) {
        long duration = 0;
        long firstSequence = nextSequence;
        Iterator<Segment> iterator = segments.descendingIterator();
        while (iterator.hasNext() && duration < PLAYLIST_TICKS) {
            Segment segment = iterator.next();
            if (segment.sequence < epochFirstSequence) { break; }
            duration += segment.durationTicks;
            firstSequence = segment.sequence;
        }
        retireBefore(firstSequence, now);
    }

    private void retireBefore(long firstSequence, long now) {
        for (Segment segment : segments) {
            if (segment.sequence >= firstSequence) { break; }
            if (segment.longestPlaylistTicks > 0 && segment.retainUntilNanos == Long.MAX_VALUE) {
                long graceTicks = segment.durationTicks + segment.longestPlaylistTicks;
                segment.retainUntilNanos = now + (graceTicks * 1_000_000_000L + 89_999L) / 90_000L;
            }
        }
    }

    synchronized byte[] awaitPlaylist(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!stopped) {
            byte[] playlist = playlist();
            if (playlist != null) { return playlist; }
            if (capacityBlocked) { return null; }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { return null; }
            wait(Math.max(1, Math.min(100, remaining / 1_000_000L)));
        }
        return null;
    }

    private byte[] playlist() {
        if (nanoClock.getAsLong() - lastCompletedNanos > MAX_PLAYLIST_AGE_NANOS) { return null; }
        List<Segment> window = new ArrayList<>();
        long duration = 0;
        Iterator<Segment> iterator = segments.descendingIterator();
        while (iterator.hasNext() && duration < PLAYLIST_TICKS) {
            Segment segment = iterator.next();
            if (segment.sequence < epochFirstSequence) { break; }
            window.add(segment);
            duration += segment.durationTicks;
        }
        if (duration < MIN_PLAYLIST_TICKS) { return null; }
        Collections.reverse(window);
        Segment first = window.get(0);
        boolean beginsDiscontinuity = discontinuitySequence > 0 && first.sequence == epochFirstSequence;
        StringBuilder body = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n");
        body.append("#EXT-X-MEDIA-SEQUENCE:").append(first.sequence).append('\n');
        body.append("#EXT-X-DISCONTINUITY-SEQUENCE:")
                .append(discontinuitySequence - (beginsDiscontinuity ? 1 : 0)).append('\n');
        body.append("#EXT-X-INDEPENDENT-SEGMENTS\n");
        if (beginsDiscontinuity) { body.append("#EXT-X-DISCONTINUITY\n"); }
        for (Segment segment : window) {
            segment.longestPlaylistTicks = Math.max(segment.longestPlaylistTicks, duration);
            body.append(String.format(Locale.US, "#EXTINF:%.6f,\n", segment.durationTicks / 90_000.0));
            body.append("segment-").append(segment.sequence).append(".ts\n");
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    synchronized byte[] segment(long sequence) {
        for (Segment segment : segments) {
            if (segment.sequence == sequence) { return segment.bytes; }
        }
        return null;
    }

    synchronized void stop() {
        stopped = true;
        current = null;
        segments.clear();
        retainedBytes = 0;
        notifyAll();
    }

    private static long readClock(byte[] data) {
        for (int offset = 0; offset + 188 <= data.length; offset += 188) {
            if ((data[offset] & 0xff) != 0x47) { return -1; }
            if ((data[offset + 3] & 0x20) == 0 || (data[offset + 4] & 0xff) < 7
                    || (data[offset + 5] & 0x10) == 0) {
                continue;
            }
            return ((long)(data[offset + 6] & 0xff) << 25)
                    | ((long)(data[offset + 7] & 0xff) << 17)
                    | ((long)(data[offset + 8] & 0xff) << 9)
                    | ((long)(data[offset + 9] & 0xff) << 1)
                    | ((data[offset + 10] & 0xff) >> 7);
        }
        return -1;
    }
}
