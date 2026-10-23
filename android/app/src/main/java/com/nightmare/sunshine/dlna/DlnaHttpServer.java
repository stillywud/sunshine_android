package com.nightmare.sunshine.dlna;

import android.util.Log;

import java.io.ByteArrayInputStream;
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
 * 1. 必须支持 Range 请求。播放器普遍会先发 `Range: bytes=0-` 探测，
 *    不支持就会反复重试直到报错。
 *
 * 2. 必须返回正确的 Content-Type。TS 用 video/mp2t。
 *
 * 3. 实时流不能设 Content-Length（长度未知），用 Connection: close 让它读到底。
 *    但如果请求带了明确的 Range，就按 Range 返回 206。
 *
 * 4. 环形缓冲区：编码器持续写入，HTTP 线程从某个位置开始读。
 *    播放器断开重连时从当前位置继续，不会从头再来（那样会一直播旧画面）。
 */
public class DlnaHttpServer {
    private static final String TAG = "DlnaHttp";

    /** 环形缓冲区容量。约 8MB，够缓冲十几秒的 4Mbps 流。 */
    private static final int RING_CAPACITY = 32 * 1024 * 1024;

    /** TS 包固定 188 字节。所有投递起点都必须对齐到包边界。 */
    private static final int TS_PACKET_SIZE = 188;

    /**
     * 收到 Range 请求但此刻没有新数据时，最多等这么久再决定怎么响应。
     *
     * 等一小会儿通常就能等到新帧（25fps 下 40ms 一帧），
     * 这样能回一个合法的 206 而不是空区间。
     */
    private static final long RANGE_WAIT_MS = 2000;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

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

    /**
     * 找出「不早于 target 的最近关键帧位置」。
     *
     * 播放器续传必须从关键帧开始，否则解不出画面。
     * 但如果目标位置附近没有更晚的关键帧（说明它请求的是很久以前的数据），
     * 绝不能回退到 0 —— 那会让播放器收到"比它期望的偏移更早"的数据，
     * 直接判为非法并断开（实测报"播放失败 -2"）。
     * 这时返回 target 本身，让它从期望位置继续。
     */
    private long keyframeAtOrAfter(long target) {
        synchronized (lock) {
            for (Long k : keyframeHistory) {
                if (k >= target) {
                    return k;
                }
            }
            // 没有更晚的关键帧：用请求位置本身（不要倒退到 0）
            return target;
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
    public void write(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        synchronized (lock) {
            int offset = 0;
            while (offset < data.length) {
                int pos = (int) (totalWritten % RING_CAPACITY);
                int n = Math.min(data.length - offset, RING_CAPACITY - pos);
                System.arraycopy(data, offset, ring, pos, n);
                totalWritten += n;
                offset += n;
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

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
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

            // 支持 Range 续传。
            //
            // 播放器会记住已收到的字节数，断线或缓冲不足时用
            // "Range: bytes=N-" 从 N 继续拉。如果这里不认 Range、
            // 一律回 200 全量，它会认为流异常直接 reset（实测表现为
            // 播一段后报"播放失败 -2"）。
            //
            // 所以：认 Range，回 206 + Content-Range，从 N 继续推。
            // 但要保证 N 落在关键帧上，否则解不出画面。
            long startFrom = 0;
            boolean hasRange = rangeHeader != null && rangeHeader.startsWith("bytes=");
            if (hasRange) {
                String spec = rangeHeader.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                if (dash > 0) {
                    try {
                        startFrom = Long.parseLong(spec.substring(0, dash).trim());
                    } catch (NumberFormatException ignored) {
                        startFrom = 0;
                    }
                }
            }

            long total = streamLength();
            if (startFrom > total) {
                startFrom = total;
            }

            // 播放器把当前已有的数据读完，会立刻用 "Range: bytes=<total>-" 再来要。
            // 这是实时流，此刻确实还没有新数据，只能等——绝不能回一个
            // "Content-Range: bytes <total>-<total-1>/<total>"（end < start 是非法值）。
            // 实测播放器解析到这个头会立刻报 handleError: -2 并断开，
            // 而屏幕静止时编码器停帧，这种情况每 15 秒就发生一次。
            if (hasRange) {
                long deadline = System.currentTimeMillis() + RANGE_WAIT_MS;
                while (running && streamLength() <= startFrom
                        && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                total = streamLength();
            }

            // 续传点必须对齐到关键帧，否则播放器解不出画面
            long alignedFrom = keyframeAtOrAfter(startFrom);
            if (alignedFrom < 0 || alignedFrom > total) {
                alignedFrom = startFrom;
            }

            // 只有「起始点之后确实还有数据」才回 206 + Content-Range。
            // 没有数据可给时按 200 无限流处理：不带 Content-Length、
            // 不带 Content-Range，播放器就当它是个没完的直播源继续读。
            boolean partial = hasRange && alignedFrom < total;

            Log.i(TAG, "从 " + alignedFrom + " 开始推流（请求偏移 " + startFrom
                    + "，关键帧对齐后 " + alignedFrom + "，总长 " + total
                    + (partial ? "，206" : "，200 直播流") + "）");

            StringBuilder head = new StringBuilder();
            if (partial) {
                head.append("HTTP/1.1 206 Partial Content\r\n");
                head.append("Content-Range: bytes ").append(alignedFrom)
                        .append("-").append(total - 1).append("/").append(total)
                        .append("\r\n");
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

            streamFrom(out, alignedFrom);
        } catch (Exception e) {
            Log.w(TAG, "连接结束 " + remote + ": " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 从指定绝对偏移开始，把环形缓冲区里的数据持续推给客户端。 */
    private void streamFrom(OutputStream out, long startOffset) throws IOException {
        // 对齐到 TS 包边界（188 字节）。播放器从半个包里开始会直接解析失败。
        long cursor = alignToPacket(startOffset);
        long lastWrite = System.currentTimeMillis();

        while (running) {
            byte[] chunk = null;
            synchronized (lock) {
                // 只在"起始点"对齐包边界，后续数据本身是连续的 TS 包流，
                // 不需要每次再对齐（之前的写法会把不足 188 字节的尾巴无限等待，
                // 导致码率极低、播放器一直缓冲）。
                long available = totalWritten - cursor;
                if (available <= 0) {
                    // 没有新数据，等一会儿（或超时退出）
                    try {
                        lock.wait(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (System.currentTimeMillis() - lastWrite > 120000) {
                        // 2 分钟没有新数据才关闭。
                        //
                        // 屏幕完全静止时，某编码器（实测雷电模拟器）会彻底停帧，
                        // 这时不能主动断开——播放器只要连接还在就会继续显示最后
                        // 一帧；一断就报"播放失败 -2"。
                        Log.w(TAG, "等待新数据超时，关闭连接 (cursor=" + cursor
                                + " total=" + totalWritten + ")");
                        return;
                    }
                    continue;
                }
                // 播放器落后太多时跳到最新的关键帧（不能跳到任意位置，
                // 否则解不出画面）
                if (available > RING_CAPACITY * 0.6) {
                    long skipTo = keyframeAtOrAfter(totalWritten - RING_CAPACITY / 3);
                    if (skipTo < 0) {
                        skipTo = totalWritten - RING_CAPACITY / 3;
                    }
                    Log.w(TAG, "客户端落后 " + available + " 字节，跳到关键帧 " + skipTo);
                    cursor = skipTo;
                    available = totalWritten - cursor;
                }
                // 有多少发多少，不再强行按 188 对齐切片
                // （对齐只在连接起始点做一次，见 streamFrom 开头）
                int n = (int) Math.min(available, 64 * 1024);
                if (n <= 0) {
                    try {
                        lock.wait(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                chunk = new byte[n];
                int pos = (int) (cursor % RING_CAPACITY);
                int firstPart = Math.min(n, RING_CAPACITY - pos);
                System.arraycopy(ring, pos, chunk, 0, firstPart);
                if (firstPart < n) {
                    System.arraycopy(ring, 0, chunk, firstPart, n - firstPart);
                }
            }

            out.write(chunk);
            out.flush();
            cursor += chunk.length;
            lastWrite = System.currentTimeMillis();
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
