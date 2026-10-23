package com.nightmare.sunshine.dlna;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

/**
 * 极简 HTTP 服务器，只做一件事：把一个「不断变长」的 TS 流喂给 DLNA 接收端。
 *
 * 关键点（都是实测踩出来的）：
 *
 * 1. 有界 Range 返回准确字节切片；无界 Range 作为新直播返回 200，
 *    避免把某次响应内部的字节偏移误当成全局历史位置。
 *
 * 2. 必须返回正确的 Content-Type。TS 用 video/mp2t。
 *
 * 3. 实时流不能设 Content-Length（长度未知），用 Connection: close 让它读到底。
 *    有界 Range 则返回 206 和精确长度，不能继续追加响应范围外的数据。
 *
 * 4. 环形缓冲区：编码器持续写入，HTTP 线程从某个位置开始读。
 *    新直播从最新关键帧加入；socket 写阻塞负责网络背压，不人为扣留尾部数据。
 */
public class DlnaHttpServer {
    private static final String TAG = "DlnaHttp";

    /**
     * 环形缓冲区容量。
     *
     * 这里保存的是「最近的流数据」，供播放器续传时回读。
     * 对实时投屏来说历史数据几乎没有价值，容量越大反而越糟：
     * 播放器可以从很早的位置开始请求，然后一直补历史、永远追不上实时。
     *
     * 原来给 32MB（2Mbps 下能存 128 秒），实测播放器会请求 112 秒前的
     * 位置，导致延迟巨大。降到 8MB（约 30 秒）既能覆盖正常的断线重连，
     * 又不会让播放器陷进历史数据里。
     */
    private static final int RING_CAPACITY = 8 * 1024 * 1024;

    /** TS 包固定 188 字节。直播起点对齐；有界 Range 保留请求的原始字节偏移。 */
    private static final int TS_PACKET_SIZE = 188;

    /** 最近窗口的字节吞吐，用于把「秒」换算成「字节」。 */
    private long rateWindowStartAt = System.currentTimeMillis();
    private long rateWindowBytes;
    private volatile long bytesPerSec;

    /** 当前估算的每秒字节数（带一次平滑，避免抖动）。 */
    private long measuredBytesPerSec() {
        long now = System.currentTimeMillis();
        long elapsed = now - rateWindowStartAt;
        if (elapsed >= 1000) {
            long instant = (totalBytes() - rateWindowBytes) * 1000L / elapsed;
            // 简单指数平滑：新值占 1/4，避免单次采样抖动影响跳帧判断
            bytesPerSec = bytesPerSec == 0 ? instant : (bytesPerSec * 3 + instant) / 4;
            rateWindowBytes = totalBytes();
            rateWindowStartAt = now;
        }
        // 还没测出来时给个保守值（约 2.5Mbps），避免阈值过大
        return bytesPerSec > 0 ? bytesPerSec : 320_000;
    }

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private final java.util.Set<Socket> sockets = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final byte[] ring = new byte[RING_CAPACITY];
    /** 累计写入的总字节数（单调递增），用它换算环形缓冲区的绝对偏移 */
    private long totalWritten;
    private final Object lock = new Object();

    private int port;
    private final String contentType;

    public DlnaHttpServer(String contentType) {
        this.contentType = contentType;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    /** 启动监听，返回实际端口（0 表示让系统分配）。 */
    public int start(int requestedPort) throws IOException {
        serverSocket = new ServerSocket(requestedPort);
        serverSocket.setReuseAddress(true);
        port = serverSocket.getLocalPort();
        running = true;

        acceptThread = new Thread(this::acceptLoop, "DlnaHttpAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        Log.i(TAG, "HTTP 流服务已启动，端口 " + port);
        return port;
    }

    public void stop() {
        running = false;
        synchronized (lock) { lock.notifyAll(); }
        for (Socket socket : sockets) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        serverSocket = null;
        Log.i(TAG, "HTTP 流服务已停止");
    }

    /** 最近一个关键帧在流中的绝对位置（-1 表示还没有） */
    private volatile long lastKeyframeOffset = -1;

    /** 最近若干关键帧的位置，供跳转时找对齐点 */
    private final java.util.ArrayDeque<Long> keyframeHistory = new java.util.ArrayDeque<>();

    /** 编码器产出一个带 PAT/PMT + SPS/PPS 的关键帧时，记录它的起始位置。 */
    public void markKeyframe() {
        synchronized (lock) {
            lastKeyframeOffset = totalWritten;
            keyframeHistory.addLast(totalWritten);
            // 只保留最近 32 个，够覆盖环形缓冲
            while (keyframeHistory.size() > 32) {
                keyframeHistory.removeFirst();
            }
        }
    }

    /** 供 HTTP 端取"从哪个位置开始推"用 */
    public long keyframeOffset() {
        return lastKeyframeOffset;
    }

    /** 当前流的总长度 */
    public long streamLength() {
        return totalBytes();
    }

    /** 编码器每产出一批 TS 字节就调这里。 */
    public void write(byte[] data) { write(data, false); }

    /** Atomically publish an IDR boundary and its bytes; audio cannot interleave. */
    public void write(byte[] data, boolean keyframe) {
        if (data == null || data.length == 0) {
            return;
        }
        synchronized (lock) {
            if (keyframe) { markKeyframe(); }
            int offset = 0;
            while (offset < data.length) {
                int pos = (int) (totalWritten % RING_CAPACITY);
                int n = Math.min(data.length - offset, RING_CAPACITY - pos);
                System.arraycopy(data, offset, ring, pos, n);
                totalWritten += n;
                offset += n;
            }
            long oldest = Math.max(0, totalWritten - RING_CAPACITY);
            while (!keyframeHistory.isEmpty() && keyframeHistory.peekFirst() < oldest) {
                keyframeHistory.removeFirst();
            }
            lock.notifyAll();
        }
    }

    /** 当前流的总长度（作为 Content-Length 的“虚拟”上限） */
    private long totalBytes() {
        synchronized (lock) {
            return totalWritten;
        }
    }

    /** 累计已推送给接收端的字节数（真实出网流量，和编码产出量不同）。 */
    private final java.util.concurrent.atomic.AtomicLong servedBytes =
            new java.util.concurrent.atomic.AtomicLong();

    public long servedBytes() {
        return servedBytes.get();
    }

    /**
     * 每个活跃连接当前读到流里的哪个绝对位置。
     *
     * 「积压」必须按这个算，不能用累计推送字节数——
     * 后者是所有连接的历史累计量，多次连接/重连/外部探测都会让它虚高，
     * 曾经因此导致积压永远显示 0，把真实延迟完全掩盖了。
     */
    private final java.util.Map<Integer, Long> connCursors =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger connSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 记录某连接的读取进度 */
    private void updateCursor(int connId, long cursor) {
        connCursors.put(connId, cursor);
    }

    private void removeCursor(int connId) {
        connCursors.remove(connId);
    }

    /** 当前活跃的 HTTP 连接数。0 表示接收端没在拉流。 */
    public int clientCount() {
        return connCursors.size();
    }

    /** 最近一次有数据发出的时刻（毫秒） */
    private volatile long lastServedAt;

    public long lastServedAt() {
        return lastServedAt;
    }

    /**
     * 接收端当前落后源多少秒。
     *
     * 对每个活跃连接算「流最新位置 − 它读到的位置」，取其中最小的那个——
     * 最小的代表最快的那个显示端，也就是用户实际看到的进度。
     * 再除以码率换算成秒。
     *
     * 不能用「总产出 − 累计推送」来算：那是历史累计量，连接重连、
     * 外部探测都会污染它，曾经因此把积压算成恒定 0，看不出真实延迟。
     */
    public long lagSeconds() {
        long rate = measuredBytesPerSec();
        if (rate <= 0 || connCursors.isEmpty()) {
            return 0;
        }
        long newest = totalBytes();
        long minLag = Long.MAX_VALUE;
        for (Long cursor : connCursors.values()) {
            long lag = newest - cursor;
            if (lag < minLag) {
                minLag = lag;
            }
        }
        return minLag == Long.MAX_VALUE || minLag <= 0 ? 0 : minLag / rate;
    }

    private void acceptLoop() {
        final ServerSocket listener = serverSocket;
        while (running) {
            try {
                Socket socket = listener.accept();
                socket.setTcpNoDelay(true);
                socket.setSendBufferSize(64 * 1024);
                sockets.add(socket);
                if (!running) { socket.close(); sockets.remove(socket); break; }
                Thread t = new Thread(() -> handle(socket), "DlnaHttpConn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept 失败: " + e.getMessage());
                }
            }
        }
    }

    private void handle(Socket socket) {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        final int connId = connSeq.incrementAndGet();
        try {
            socket.setSoTimeout(15000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }

            String rangeHeader = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                    if ("range".equals(name)) {
                        rangeHeader = line.substring(colon + 1).trim();
                    }
                }
            }

            Log.i(TAG, "请求 " + requestLine + " from " + remote
                    + (rangeHeader != null ? " Range=" + rangeHeader : ""));

            long requestedStart = 0;
            long requestedEnd = Long.MAX_VALUE;
            boolean hasRange = rangeHeader != null && rangeHeader.startsWith("bytes=");
            if (hasRange) {
                String spec = rangeHeader.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                try {
                    if (dash <= 0) { hasRange = false; }
                    else {
                        requestedStart = Long.parseLong(spec.substring(0, dash).trim());
                        if (dash + 1 < spec.length()) {
                            requestedEnd = Long.parseLong(spec.substring(dash + 1).trim());
                        }
                        if (requestedStart < 0 || requestedEnd < requestedStart) { hasRange = false; }
                    }
                } catch (NumberFormatException e) { hasRange = false; }
            }

            long total;
            long alignedFrom;
            long endExclusive = Long.MAX_VALUE;
            boolean partial;
            synchronized (lock) {
                total = totalWritten;
                long oldest = Math.max(0, total - RING_CAPACITY);
                // Range is a byte identity contract. Never align a retained range
                // to a keyframe or change the cursor after publishing its headers.
                // Any open-ended reconnect starts a fresh live response: its offset
                // is relative to a previous 200 body, not this global ring buffer.
                partial = hasRange && requestedEnd != Long.MAX_VALUE
                        && requestedStart >= oldest && requestedStart < total;
                if (partial) {
                    alignedFrom = requestedStart;
                    endExclusive = Math.min(requestedEnd, total - 1) + 1;
                } else {
                    alignedFrom = !keyframeHistory.isEmpty() ? keyframeHistory.peekLast()
                            : alignToPacket(oldest);
                }
            }
            Log.i(TAG, "start=" + alignedFrom + " requested=" + requestedStart
                    + " total=" + total + (partial ? " 206 exact range" : " 200 live"));

            StringBuilder head = new StringBuilder();
            if (partial) {
                head.append("HTTP/1.1 206 Partial Content\r\n");
                head.append("Content-Range: bytes ").append(alignedFrom)
                        .append("-").append(endExclusive - 1).append("/").append(total)
                        .append("\r\n");
                head.append("Content-Length: ").append(endExclusive - alignedFrom).append("\r\n");
            } else {
                head.append("HTTP/1.1 200 OK\r\n");
            }
            head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            head.append("Cache-Control: no-cache, no-store\r\n");
            head.append("Pragma: no-cache\r\n");
            head.append("Connection: close\r\n");
            head.append("\r\n");
            out.write(head.toString().getBytes("UTF-8"));
            out.flush();

            // 先登记起始位置，避免刚从连接建立到第一块数据写出之间
            // 集合为空导致延迟误报 0
            updateCursor(connId, alignedFrom);
            if (!requestLine.startsWith("HEAD ")) {
                streamFrom(out, alignedFrom, endExclusive, connId);
            }
        } catch (Exception e) {
            Log.w(TAG, "连接结束 " + remote + ": " + e.getMessage());
        } finally {
            removeCursor(connId);
            sockets.remove(socket);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 从指定绝对偏移开始，把环形缓冲区里的数据持续推给客户端。 */
    private void streamFrom(OutputStream out, long cursor, long endExclusive,
                            int connId) throws IOException {
        long lastWrite = System.nanoTime();
        byte[] chunk = new byte[188 * 128];
        while (running && cursor < endExclusive) {
            int n;
            synchronized (lock) {
                if (cursor < totalWritten - RING_CAPACITY) {
                    // Never silently send overwritten bytes under historical offsets/PTS.
                    throw new IOException("Receiver overran live ring; reconnect at a retained IDR");
                }
                long available = Math.min(totalWritten, endExclusive) - cursor;
                if (available <= 0) {
                    try { lock.wait(500); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    if (System.nanoTime() - lastWrite > 120_000_000_000L) { return; }
                    continue;
                }
                n = (int) Math.min(available, chunk.length);
                int pos = (int) (cursor % RING_CAPACITY);
                int first = Math.min(n, RING_CAPACITY - pos);
                System.arraycopy(ring, pos, chunk, 0, first);
                if (first < n) { System.arraycopy(ring, 0, chunk, first, n - first); }
            }
            // Socket.write provides actual consumer backpressure. Never withhold
            // newest bytes behind a minimum backlog or lock the encoder on network IO.
            out.write(chunk, 0, n);
            out.flush();
            cursor += n;
            servedBytes.addAndGet(n);
            updateCursor(connId, cursor);
            lastServedAt = System.currentTimeMillis();
            lastWrite = System.nanoTime();
        }
    }

    /** 把任意偏移向上对齐到 188 字节的 TS 包边界 */
    private static long alignToPacket(long offset) {
        long rem = offset % TS_PACKET_SIZE;
        return rem == 0 ? offset : offset + (TS_PACKET_SIZE - rem);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 8192) {
                return sb.toString();
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
