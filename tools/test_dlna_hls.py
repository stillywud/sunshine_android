"""Exercise the sender's HLS endpoints over real loopback HTTP with JDK 17."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
import com.nightmare.sunshine.dlna.DlnaHttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public class HlsRegression {
    interface Checked { void run() throws Exception; }
    static final Map<DlnaHttpServer, AtomicLong> clocks = new WeakHashMap<>();
    static class Response {
        String headers;
        byte[] body;
        Response(String headers, byte[] body) { this.headers = headers; this.body = body; }
    }
    static byte[] frame(long ticks, int marker) {
        byte[] data = new byte[188 * 3];
        Arrays.fill(data, (byte)marker);
        for (int packet = 0; packet < 3; packet++) {
            int offset = packet * 188;
            data[offset] = 0x47;
            data[offset + 1] = (byte)(packet == 1 ? 0x50 : 0x40);
            data[offset + 2] = 0;
            data[offset + 3] = 0x10;
        }
        int offset = 376;
        ticks &= (1L << 33) - 1;
        data[offset + 1] = 0x41;
        data[offset + 3] = 0x30;
        data[offset + 4] = 7;
        data[offset + 5] = 0x50;
        data[offset + 6] = (byte)(ticks >> 25);
        data[offset + 7] = (byte)(ticks >> 17);
        data[offset + 8] = (byte)(ticks >> 9);
        data[offset + 9] = (byte)(ticks >> 1);
        data[offset + 10] = (byte)(((ticks & 1) << 7) | 0x7e);
        data[offset + 11] = 0;
        return data;
    }
    static void populate(DlnaHttpServer server, int first, int count, long epoch) {
        for (int index = first; index < first + count; index++) {
            server.write(frame(epoch + index * 45000L, index), true);
        }
    }
    static String headers(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0;
        while (state != 4) {
            int value = input.read();
            if (value < 0) throw new AssertionError("EOF before HTTP headers");
            bytes.write(value);
            state = value == (state % 2 == 0 ? '\r' : '\n') ? state + 1 : 0;
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }
    static Response request(DlnaHttpServer server, String method, String path, String extra) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(6500);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                    + extra + "\r\n").getBytes(StandardCharsets.US_ASCII));
            String head = headers(socket.getInputStream());
            if (!head.contains("Content-Length:")) {
                throw new AssertionError("HLS response must be finite: " + head);
            }
            return new Response(head, socket.getInputStream().readAllBytes());
        }
    }
    static String playlist(DlnaHttpServer server) throws Exception {
        Response response = request(server, "GET", "/live.m3u8", "");
        if (!response.headers.startsWith("HTTP/1.1 200")
                || !response.headers.contains("application/vnd.apple.mpegurl")) {
            throw new AssertionError("not an HLS playlist: " + response.headers);
        }
        return new String(response.body, StandardCharsets.UTF_8);
    }
    static List<String> segmentPaths(String playlist) {
        List<String> paths = new ArrayList<>();
        for (String line : playlist.split("\n")) {
            if (!line.isEmpty() && !line.startsWith("#")) paths.add("/" + line.trim());
        }
        return paths;
    }
    static long sequence(String path) {
        return Long.parseLong(path.substring(path.lastIndexOf('-') + 1, path.length() - 3));
    }
    static DlnaHttpServer server() throws Exception {
        AtomicLong clock = new AtomicLong(System.nanoTime());
        java.lang.reflect.Constructor<DlnaHttpServer> constructor = DlnaHttpServer.class
                .getDeclaredConstructor(String.class, LongSupplier.class);
        constructor.setAccessible(true);
        DlnaHttpServer server = constructor.newInstance("video/mp2t", (LongSupplier)clock::get);
        clocks.put(server, clock);
        server.start(0);
        return server;
    }
    static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    static byte[] availablePlaylist(DlnaHttpServer server) throws Exception {
        Object store = field(server, "hls");
        java.lang.reflect.Method await = store.getClass().getDeclaredMethod("awaitPlaylist", long.class);
        await.setAccessible(true);
        return (byte[])await.invoke(store, 0L);
    }
    static void populateLarge(DlnaHttpServer server, int first, int count) {
        for (int index = first; index < first + count; index++) {
            byte[] bytes = Arrays.copyOf(frame(90000 + index * 45000L, index), 188 * 5577);
            server.write(bytes, true);
        }
    }
    static void awaitCondition(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + 1_500_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        if (!condition.getAsBoolean()) throw new AssertionError(message);
    }
    static Socket slowRequest(DlnaHttpServer server, String path) throws Exception {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(1024);
        socket.setSoTimeout(2000);
        socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()));
        socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        String response = headers(socket.getInputStream());
        if (!response.startsWith("HTTP/1.1 200")) {
            socket.close();
            throw new AssertionError("slow request rejected: " + response);
        }
        return socket;
    }
    static Socket servingSocket(DlnaHttpServer server, Socket client) throws Exception {
        for (Object candidate : (Set<?>)field(server, "sockets")) {
            Socket socket = (Socket)candidate;
            if (socket.getPort() == client.getLocalPort()) return socket;
        }
        throw new AssertionError("slow response did not remain in flight");
    }
    @SuppressWarnings("unchecked")
    static void expireWrites(DlnaHttpServer server) throws Exception {
        Map<Socket, Long> deadlines = (Map<Socket, Long>)field(server, "hlsDeadlines");
        if (deadlines.isEmpty()) throw new AssertionError("stalled response has no write deadline");
        deadlines.replaceAll((socket, deadline) -> System.nanoTime() - 1);
    }
    public static void main(String[] args) throws Exception {
        Map<String, Checked> cases = new LinkedHashMap<>();
        cases.put("playlist has stable one-second target and at least three seconds of complete segments", () -> {
            DlnaHttpServer server = server();
            try {
                populate(server, 0, 18, 90000);
                String body = playlist(server);
                if (!body.startsWith("#EXTM3U\n") || !body.contains("#EXT-X-TARGETDURATION:1\n")
                        || !body.contains("#EXT-X-INDEPENDENT-SEGMENTS\n") || body.contains("#EXT-X-ENDLIST")) {
                    throw new AssertionError("invalid live playlist: " + body);
                }
                double duration = 0;
                for (String line : body.split("\n")) {
                    if (line.startsWith("#EXTINF:")) {
                        double value = Double.parseDouble(line.substring(8, line.indexOf(',')));
                        if (Math.round(value) > 1) throw new AssertionError("invalid target duration");
                        duration += value;
                    }
                }
                if (duration < 3 || duration > 5) throw new AssertionError("bad live window " + duration);
                List<String> paths = segmentPaths(body);
                for (int index = 1; index < paths.size(); index++) {
                    if (sequence(paths.get(index)) != sequence(paths.get(index - 1)) + 1) {
                        throw new AssertionError("noncontiguous sequence");
                    }
                }
            } finally { server.stop(); }
        });
        cases.put("an initial HEAD probe waits for a usable live window without sending a body", () -> {
            DlnaHttpServer server = server();
            Thread producer = new Thread(() -> {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { return; }
                populate(server, 0, 12, 90000);
            });
            try {
                producer.start();
                Response head = request(server, "HEAD", "/live.m3u8", "");
                if (!head.headers.startsWith("HTTP/1.1 200") || head.body.length != 0) {
                    throw new AssertionError("initial HLS HEAD probe rejected before encoder warmed up");
                }
            } finally { producer.join(); server.stop(); }
        });
        cases.put("completed segment bytes remain immutable while the live stream grows", () -> {
            DlnaHttpServer server = server();
            try {
                populate(server, 0, 12, 90000);
                String path = segmentPaths(playlist(server)).get(0);
                long index = sequence(path);
                Response before = request(server, "GET", path, "");
                if (!Arrays.equals(before.body, frame(90000 + index * 45000, (int)index))) {
                    throw new AssertionError("segment does not begin at its real PAT/PMT/IDR");
                }
                populate(server, 12, 6, 90000);
                Response after = request(server, "GET", path, "");
                if (!Arrays.equals(before.body, after.body)) throw new AssertionError("published segment changed");
                if (server.servedBytes() < before.body.length + after.body.length
                        || server.lastServedAt() <= 0 || server.clientCount() < 1) {
                    throw new AssertionError("segmented traffic missing from sender statistics");
                }
            } finally { server.stop(); }
        });
        cases.put("segment HEAD and byte ranges describe only the immutable finite body", () -> {
            DlnaHttpServer server = server();
            try {
                populate(server, 0, 12, 90000);
                String path = segmentPaths(playlist(server)).get(0);
                Response whole = request(server, "GET", path, "");
                Response head = request(server, "HEAD", path, "");
                if (head.body.length != 0 || !head.headers.contains("Content-Length: " + whole.body.length + "\r\n")) {
                    throw new AssertionError("HEAD mismatch");
                }
                Response part = request(server, "GET", path, "Range: bytes=7-99\r\n");
                if (!part.headers.startsWith("HTTP/1.1 206") || !Arrays.equals(part.body, Arrays.copyOfRange(whole.body, 7, 100))) {
                    throw new AssertionError("range identity violated");
                }
                Response suffix = request(server, "GET", path, "Range: bytes=-10\r\n");
                if (!Arrays.equals(suffix.body, Arrays.copyOfRange(whole.body, whole.body.length - 10, whole.body.length))) {
                    throw new AssertionError("suffix range mismatch");
                }
                Response invalid = request(server, "GET", path, "Range: bytes=99999-\r\n");
                if (!invalid.headers.startsWith("HTTP/1.1 416")) throw new AssertionError("missing 416");
            } finally { server.stop(); }
        });
        cases.put("HLS segment HEAD ignores valid and unsatisfiable ranges", () -> {
            DlnaHttpServer server = server();
            try {
                populate(server, 0, 12, 90000);
                String path = segmentPaths(playlist(server)).get(0);
                int length = request(server, "GET", path, "").body.length;
                for (String range : new String[]{"bytes=7-99", "bytes=99999-", "bytes=-10"}) {
                    Response head = request(server, "HEAD", path, "Range: " + range + "\r\n");
                    if (!head.headers.startsWith("HTTP/1.1 200") || head.body.length != 0
                            || !head.headers.contains("Content-Length: " + length + "\r\n")
                            || head.headers.contains("Content-Range:")) {
                        throw new AssertionError("HEAD interpreted Range " + range + ": " + head.headers);
                    }
                }
            } finally { server.stop(); }
        });
        cases.put("paced 3 Mbps publication stays available with bounded retained history", () -> {
            DlnaHttpServer server = server();
            try {
                int frameBytes = 188 * 999;
                for (int index = 0; index < 100; index++) {
                    clocks.get(server).addAndGet(500_000_000L);
                    server.write(Arrays.copyOf(frame(90000 + index * 45000L, index), frameBytes), true);
                    if (index >= 6) {
                        byte[] bytes = availablePlaylist(server);
                        if (bytes == null) throw new AssertionError("ordinary bitrate exhausted retention capacity");
                        String body = new String(bytes, StandardCharsets.UTF_8);
                        if (segmentPaths(body).size() < 6 || !body.contains("#EXT-X-DISCONTINUITY-SEQUENCE:0\n")) {
                            throw new AssertionError("ordinary bitrate lost its continuous playable window");
                        }
                    }
                    Object store = field(server, "hls");
                    if ((int)field(store, "retainedBytes") > 8 * 1024 * 1024
                            || ((Collection<?>)field(store, "segments")).size() > 32) {
                        throw new AssertionError("steady publication exceeded its history bound");
                    }
                }
                if (server.streamLength() != 100L * frameBytes) throw new AssertionError("HLS changed raw TS publication");
            } finally { server.stop(); }
        });
        cases.put("retirement includes the longest advertised window and the segment duration", () -> {
            DlnaHttpServer server = server();
            try {
                for (int index = 0; index < 4; index++) server.write(frame(90000 + index * 126000L, index), true);
                String oldest = segmentPaths(playlist(server)).get(0);
                server.write(frame(90000 + 4 * 126000L, 4), true);
                clocks.get(server).addAndGet(5_599_999_999L);
                for (int index = 5; index < 46; index++) server.write(frame(90000 + index * 126000L, index), true);
                if (!request(server, "HEAD", oldest, "").headers.startsWith("HTTP/1.1 200")) {
                    throw new AssertionError("4.2-second playlist plus 1.4-second segment grace was shortened");
                }
                clocks.get(server).incrementAndGet();
                for (int index = 46; index < 52; index++) server.write(frame(90000 + index * 126000L, index), true);
                if (!request(server, "GET", oldest, "").headers.startsWith("HTTP/1.1 404")) {
                    throw new AssertionError("long-window promise never became reclaimable");
                }
            } finally { server.stop(); }
        });
        cases.put("byte pressure preserves promised segments and refuses an incomplete live window", () -> {
            DlnaHttpServer server = server();
            try {
                populateLarge(server, 0, 9);
                List<String> promised = segmentPaths(playlist(server));
                populateLarge(server, 9, 24);
                for (String path : promised) {
                    Response response = request(server, "GET", path, "Range: bytes=0-563\r\n");
                    if (!response.headers.startsWith("HTTP/1.1 206")
                            || !Arrays.equals(response.body, frame(90000 + sequence(path) * 45000L, (int)sequence(path)))) {
                        throw new AssertionError("byte pressure evicted or changed promised " + path);
                    }
                }
                if ((int)field(field(server, "hls"), "retainedBytes") > 8 * 1024 * 1024) {
                    throw new AssertionError("retention exceeded 8 MiB");
                }
                if (availablePlaylist(server) != null) throw new AssertionError("capacity fault advertised live media");
                Response unavailable = request(server, "HEAD", "/live.m3u8", "");
                if (!unavailable.headers.startsWith("HTTP/1.1 503") || unavailable.body.length != 0
                        || !unavailable.headers.contains("Retry-After: 1")) {
                    throw new AssertionError("capacity fault did not produce a controlled HTTP error");
                }
                clocks.get(server).addAndGet(4_499_999_999L);
                populateLarge(server, 33, 2);
                if (!request(server, "HEAD", promised.get(0), "").headers.startsWith("HTTP/1.1 200")) {
                    throw new AssertionError("retirement grace ended before 4.5 seconds");
                }
                clocks.get(server).incrementAndGet();
                populateLarge(server, 35, 12);
                if (!request(server, "GET", promised.get(0), "").headers.startsWith("HTTP/1.1 404")) {
                    throw new AssertionError("expired promises prevented capacity recovery");
                }
                String recovered = playlist(server);
                if (segmentPaths(recovered).size() < 6 || !recovered.contains("#EXT-X-DISCONTINUITY")) {
                    throw new AssertionError("capacity recovery lost its usable window or discontinuity");
                }
            } finally { server.stop(); }
        });
        cases.put("retirement grace starts at removal rather than advertisement or media time", () -> {
            DlnaHttpServer server = server();
            try {
                populate(server, 0, 12, 90000);
                String oldest = segmentPaths(playlist(server)).get(0);
                clocks.get(server).addAndGet(3_000_000_000L);
                populate(server, 12, 1, 90000);
                clocks.get(server).addAndGet(4_499_999_999L);
                populate(server, 13, 150, 90000);
                if (!request(server, "GET", oldest, "").headers.startsWith("HTTP/1.1 200")) {
                    throw new AssertionError("media advancement or advertisement age shortened retirement grace");
                }
                clocks.get(server).incrementAndGet();
                populate(server, 163, 40, 90000);
                if (!request(server, "GET", oldest, "").headers.startsWith("HTTP/1.1 404")) {
                    throw new AssertionError("retired history did not become reclaimable");
                }
                clocks.get(server).addAndGet(5_000_000_000L);
                populate(server, 203, 12, 90000);
                playlist(server);
            } finally { server.stop(); }
        });
        cases.put("PCR wrap is handled without a huge segment duration", () -> {
            DlnaHttpServer server = server();
            try {
                populate(server, 0, 12, (1L << 33) - 180000);
                String body = playlist(server);
                if (body.contains("#EXTINF:0.000") || !body.contains("#EXTINF:0.500000,")) {
                    throw new AssertionError("PCR clock wrap damaged durations");
                }
            } finally { server.stop(); }
        });
        cases.put("sliding history evicts old segments but keeps removed in-flight segments", () -> {
            DlnaHttpServer server = server();
            try {
                populate(server, 0, 20, 90000);
                String oldPath = segmentPaths(playlist(server)).get(0);
                populate(server, 20, 6, 90000);
                if (!request(server, "GET", oldPath, "").headers.startsWith("HTTP/1.1 200")) {
                    throw new AssertionError("recently removed segment evicted too early");
                }
                populate(server, 26, 2, 90000);
                clocks.get(server).addAndGet(6_000_000_000L);
                populate(server, 28, 148, 90000);
                if (!request(server, "GET", oldPath, "").headers.startsWith("HTTP/1.1 404")) {
                    throw new AssertionError("unbounded segment history");
                }
                playlist(server);
            } finally { server.stop(); }
        });
        cases.put("long GOP is not mislabeled as a one-second segment and recovery is discontinuous", () -> {
            DlnaHttpServer server = server();
            try {
                populate(server, 0, 10, 90000);
                server.write(frame(90000 + 12 * 45000, 12), true);
                populate(server, 13, 12, 90000);
                String body = playlist(server);
                if (!body.contains("#EXT-X-DISCONTINUITY-SEQUENCE:1")
                        && !body.contains("#EXT-X-DISCONTINUITY\n")) {
                    throw new AssertionError("lost media gap was hidden");
                }
                for (String line : body.split("\n")) {
                    if (line.startsWith("#EXTINF:") && Double.parseDouble(line.substring(8, line.indexOf(','))) >= 1.5) {
                        throw new AssertionError("invalid duration under target one");
                    }
                }
            } finally { server.stop(); }
        });
        cases.put("oversized incomplete GOP cannot retain unlimited memory or be published", () -> {
            DlnaHttpServer server = server();
            try {
                populate(server, 0, 12, 90000);
                String previous = segmentPaths(playlist(server)).get(0);
                server.write(new byte[3 * 1024 * 1024], false);
                populate(server, 12, 12, 90000);
                String recovered = playlist(server);
                if (segmentPaths(recovered).contains(previous)) throw new AssertionError("invalid old epoch advertised");
                for (String path : segmentPaths(recovered)) {
                    if (request(server, "GET", path, "").body.length > 2 * 1024 * 1024) {
                        throw new AssertionError("oversized segment published");
                    }
                }
            } finally { server.stop(); }
        });
        cases.put("stale cached playlist is never returned as a live edge", () -> {
            DlnaHttpServer server = server();
            try {
                populate(server, 0, 12, 90000);
                java.lang.reflect.Field storeField = DlnaHttpServer.class.getDeclaredField("hls");
                storeField.setAccessible(true);
                Object store = storeField.get(server);
                java.lang.reflect.Field completedField = store.getClass().getDeclaredField("lastCompletedNanos");
                completedField.setAccessible(true);
                completedField.setLong(store, clocks.get(server).get() - 3_000_000_000L);
                java.lang.reflect.Method await = store.getClass().getDeclaredMethod("awaitPlaylist", long.class);
                await.setAccessible(true);
                if (await.invoke(store, 0L) != null) throw new AssertionError("stale live playlist returned");
                populate(server, 12, 2, 90000);
                playlist(server);
            } finally { server.stop(); }
        });
        cases.put("stopping a server interrupts a waiting playlist request", () -> {
            DlnaHttpServer server = server();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread waiter = new Thread(() -> {
                try {
                    request(server, "GET", "/live.m3u8", "");
                } catch (EOFException expected) {
                } catch (AssertionError expected) {
                    if (!expected.getMessage().contains("EOF")) failure.set(expected);
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            try {
                waiter.start();
                Thread.sleep(100);
                server.stop();
                waiter.join(1200);
                if (waiter.isAlive()) throw new AssertionError("waiting HLS client leaked");
                if (failure.get() != null) throw new AssertionError("unexpected waiter result", failure.get());
            } finally { server.stop(); }
        });
        cases.put("concurrent HLS writes are bounded without blocking publication or raw TS", () -> {
            DlnaHttpServer server = server();
            List<Socket> clients = new ArrayList<>();
            try {
                populateLarge(server, 0, 9);
                List<String> paths = segmentPaths(playlist(server));
                for (int index = 0; index < 4; index++) clients.add(slowRequest(server, paths.get(index)));
                for (Socket client : clients) servingSocket(server, client);
                Response busy = request(server, "HEAD", paths.get(4), "");
                if (!busy.headers.startsWith("HTTP/1.1 503") || busy.body.length != 0
                        || !busy.headers.contains("Retry-After: 1")) {
                    throw new AssertionError("fifth HLS response was not bounded: " + busy.headers);
                }
                java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                Thread producer = new Thread(() -> {
                    try { populateLarge(server, 9, 2); }
                    catch (Throwable error) { failure.set(error); }
                });
                producer.start();
                producer.join(1500);
                if (producer.isAlive()) throw new AssertionError("slow HLS consumers blocked the encoder");
                if (failure.get() != null) throw new AssertionError("publication failed", failure.get());
                try (Socket raw = new Socket("127.0.0.1", server.getPort())) {
                    raw.setSoTimeout(1500);
                    raw.getOutputStream().write("HEAD /live.ts HTTP/1.1\r\nHost: localhost\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    if (!headers(raw.getInputStream()).startsWith("HTTP/1.1 200")
                            || raw.getInputStream().read() != -1) {
                        throw new AssertionError("HLS capacity changed raw TS HEAD behavior");
                    }
                }
                List<Socket> serving = new ArrayList<>();
                for (Socket client : clients) serving.add(servingSocket(server, client));
                java.util.concurrent.ScheduledExecutorService watchdog =
                        (java.util.concurrent.ScheduledExecutorService)field(server, "hlsWatchdog");
                server.stop();
                awaitCondition(() -> serving.stream().allMatch(Socket::isClosed), "stop left HLS writes open");
                if (!watchdog.awaitTermination(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("stop leaked the HLS deadline worker");
                }
                java.util.concurrent.Semaphore slots = (java.util.concurrent.Semaphore)field(server, "hlsSlots");
                awaitCondition(() -> slots.availablePermits() == 4, "stop leaked HLS response permits");
            } finally {
                for (Socket client : clients) client.close();
                server.stop();
            }
        });
        cases.put("stalled HLS write expires and releases its response slot", () -> {
            DlnaHttpServer server = server();
            Socket client = null;
            try {
                populateLarge(server, 0, 9);
                String path = segmentPaths(playlist(server)).get(0);
                client = slowRequest(server, path);
                Socket serving = servingSocket(server, client);
                expireWrites(server);
                awaitCondition(serving::isClosed, "stalled HLS write survived its deadline");
                java.util.concurrent.Semaphore slots = (java.util.concurrent.Semaphore)field(server, "hlsSlots");
                awaitCondition(() -> slots.availablePermits() == 4, "expired HLS write leaked its slot");
                if (!request(server, "HEAD", path, "").headers.startsWith("HTTP/1.1 200")) {
                    throw new AssertionError("expired write prevented a subsequent request");
                }
            } finally {
                if (client != null) client.close();
                server.stop();
            }
        });
        cases.put("unknown paths and unsupported methods never start a raw live stream", () -> {
            DlnaHttpServer server = server();
            try {
                if (!request(server, "GET", "/missing.m3u8", "").headers.startsWith("HTTP/1.1 404")) {
                    throw new AssertionError("unknown route not rejected");
                }
                if (!request(server, "POST", "/live.m3u8", "").headers.startsWith("HTTP/1.1 405")) {
                    throw new AssertionError("unsupported method not rejected");
                }
            } finally { server.stop(); }
        });
        int failed = 0;
        for (Map.Entry<String, Checked> entry : cases.entrySet()) {
            if (args.length > 0 && Arrays.stream(args).noneMatch(entry.getKey()::contains)) continue;
            try { entry.getValue().run(); System.out.println("PASS " + entry.getKey()); }
            catch (Throwable error) { failed++; System.out.println("FAIL " + entry.getKey() + ": " + error); }
        }
        if (failed > 0) throw new AssertionError(failed + " HLS regression(s)");
    }
}
'''

with tempfile.TemporaryDirectory(prefix="dlna-hls-test-") as directory:
    temporary = Path(directory)
    stub = temporary / "android/util/Log.java"
    stub.parent.mkdir(parents=True)
    stub.write_text("package android.util; public class Log { public static int i(String tag,String message){return 0;} public static int w(String tag,String message){return 0;} }", encoding="utf-8")
    harness = temporary / "HlsRegression.java"
    harness.write_text(JAVA, encoding="utf-8")
    sources = ROOT / "android/app/src/main/java/com/nightmare/sunshine/dlna"
    java_sources = [sources / "DlnaHttpServer.java"]
    if (sources / "DlnaHlsStore.java").exists():
        java_sources.append(sources / "DlnaHlsStore.java")
    jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin"
    subprocess.run([str(jdk / "javac"), "-encoding", "UTF-8", "-d", str(temporary), str(stub), str(harness), *map(str, java_sources)], check=True)
    subprocess.run([str(jdk / "java"), "-cp", str(temporary), "HlsRegression", *sys.argv[1:]], check=True)
