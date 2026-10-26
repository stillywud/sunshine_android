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

    /** TS 包固定 188 字节。所有投递起点都必须对齐到包边界。 */
    private static final int TS_PACKET_SIZE = 188;

    /**
     * 收到 Range 请求但此刻没有新数据时，最多等这么久再决定怎么响应。
     *
     * 等一小会儿通常就能等到新帧（25fps 下 40ms 一帧），
     * 这样能回一个合法的 206 而不是空区间。
     */
    private static final long RANGE_WAIT_MS = 2000;

    /**
     * 续传时，如果请求位置落后超过这么多秒，就直接跳到最新。
     *
     * 正常播放时本来就有 1 秒左右抖动，需要宽容；但确实已经拖了几秒以上
     * 的历史，就该果断丢弃。单独用一个常量是为了避免把「正常抖动」
     * 也当成「太旧」而频繁跳帧。
     */
    private static final double RESUME_JUMP_SECONDS = 4.0;

    /** 落后上限的最小字节数，防止码率很低时阈值小到频繁跳帧。 */
    private static final long MIN_LAG_LIMIT_BYTES = 512 * 1024;

    /**
     * 背压：发送窗口，按「秒」定义，再按实测码率换算成字节。
     *
     * 这里必须用时间而不是固定字节数。曾经写死 512KB，在 918kbps 下只有
     * 4.5 秒的数据，窗口太小导致数据供给断续，播放器把「暂时没有新数据」
     * 判成「流结束」，实测只播 5 秒就 onCompletion 退出。
     *
     * 8 秒是实测出来的折中：既给播放器足够缓冲垫不至于断流，
     * 又不至于让它吞掉几十秒历史。
     */
    private static final double SEND_WINDOW_SECONDS = 8.0;

    /** 窗口的最小/最大字节数，防止码率极低/极高时窗口失去意义。 */
    private static final long MIN_SEND_WINDOW_BYTES = 2 * 1024 * 1024;
    private static final long MAX_SEND_WINDOW_BYTES = 16 * 1024 * 1024;

    /** 当前生效的发送窗口（字节）。 */
    private long sendWindowBytes() {
        long w = (long) (measuredBytesPerSec() * SEND_WINDOW_SECONDS);
        if (w < MIN_SEND_WINDOW_BYTES) {
            return MIN_SEND_WINDOW_BYTES;
        }
        return Math.min(w, MAX_SEND_WINDOW_BYTES);
    }

    /**
     * 直播流声明的「虚拟总长度」。
     *
     * 这个值只用于让播放器能立即开始解码（它需要 Content-Length 才肯播）。
     * 播放器读满这么多字节就会认为文件结束并退出播放，所以要给得足够大。
     *
     * 实测出的可用区间：
     *   - 不带 Content-Length → 30 秒超时报 handleError: -2，永不开始播
     *   - 190MB（按码率估 10 分钟）→ 能播，但播满 190MB 就 onCompletion 断开
     *   - 4GB / 2TB → 播放器直接报 537067523（数值被拒），完全不播
     *
     * 所以上限落在 190MB 和 4GB 之间。取 1GB：约 1 小时 @ 2Mbps，
     * 足够覆盖一次投屏会话，又远离被拒的阈值。
     */
    private static final long FAKE_STREAM_LENGTH = 1024L * 1024 * 1024;

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

    private final byte[] ring = new byte[RING_CAPACITY];
    /** 累计写入的总字节数（单调递增），用它换算环形缓冲区的绝对偏移 */
    private long totalWritten;
    private final Object lock = new Object();

    private int port;
    private final String contentType;

    /** 对照实验用：一段录制好的 TS 文件，通过 /static.ts 原样提供。 */
    private java.io.File staticFile;

    /** 设置对照实验用的静态文件。 */
    public void setStaticFile(java.io.File f) {
        this.staticFile = f;
    }

    /**
     * 原样提供静态 TS 文件。
     *
     * 和直播流的区别只有一点：长度固定、发完就关。
     * 其余（Content-Type、Range 支持）保持一致，这样对照才有意义。
     */
    private void serveStaticFile(Socket socket, OutputStream out, java.io.File f)
            throws IOException {
        long total = f.length();
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + total + "\r\n"
                + "Accept-Ranges: bytes\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.flush();
        Log.i(TAG, "静态文件对照实验：发送 " + total + " 字节");

        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
        Log.i(TAG, "静态文件对照实验：发送完成");
    }

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
     * 找出离 target 最近的关键帧位置，优先取「不晚于 target」的那个。
     *
     * 播放器续传必须从关键帧开始，否则解不出画面；但落点离它请求的位置
     * 太远都不行，两个方向都实测出过问题：
     *
     *   倒退太远 → 它收到比期望更早的数据，判非法直接断开（"播放失败 -2"）
     *   前进太多 → 中间一段数据被跳过，时间戳断层，播放器认为流已结束，
     *              直接 onCompletion 退出播放（实测跳过 31KB 就触发）
     *
     * 所以策略是：取 <= target 的最后一个关键帧；一个都没有时才退而取
     * 第一个 > target 的；实在没有就返回 target 本身。
     */
    private long nearestKeyframe(long target) {
        synchronized (lock) {
            Long before = null;
            Long after = null;
            for (Long k : keyframeHistory) {
                if (k <= target) {
                    before = k;
                } else if (after == null) {
                    after = k;
                }
            }
            if (before != null) {
                return before;
            }
            if (after != null) {
                return after;
            }
            return target;
        }
    }

    /** 找出「不早于 target 的第一个关键帧」，用于落后追赶。 */
    private long keyframeAtOrAfter(long target) {
        synchronized (lock) {
            for (Long k : keyframeHistory) {
                if (k >= target) {
                    return k;
                }
            }
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

    /** 累计已推送给接收端的字节数（真实出网流量，和编码产出量不同）。 */
    private volatile long servedBytes;

    public long servedBytes() {
        return servedBytes;
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
    private volatile int clientCount;

    public int clientCount() {
        return clientCount;
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
        clientCount++;
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

            // ---- 对照实验端点：原样吐出一段录制好的 TS 文件 ----
            //
            // 用途：把「TS 封装格式」和「直播流的持续/时序」两个变量分开。
            // 如果接收端能播这个静态文件，说明封装没问题，问题在直播时序；
            // 如果也播不了，说明封装本身就有问题。
            //
            // 文件由 DlnaStreamer 录制后写入，路径见 staticFile。
            if (requestLine.contains("/static.ts") && staticFile != null
                    && staticFile.exists()) {
                serveStaticFile(socket, out, staticFile);
                return;
            }

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

            // 关键：判断播放器请求的位置有没有「太旧」。
            //
            // 播放器会用 "Range: bytes=N-" 从它上次读到的位置续传。如果 N
            // 已经落后流末尾很多（比如几十秒），而我们老老实实从 N 开始推，
            // 那它要补完这几十秒的历史数据才可能追上实时——实际上永远追不上，
            // 因为它一边补一边又落后更多。
            //
            // 特殊情况：播放器首次连接时请求 "偏移 0"（或根本不给 Range），
            // 此时流可能已经跑了几十 MB。它期望的是「从头开始的完整文件」，
            // 我们绝不可能把全部历史都发过去，也不该发——投屏是实时的。
            //
            // 这里的处理是：一律从「最新关键帧」开始发，但必须用 206 +
            // 真实的 Content-Range 如实告知起始位置。播放器看到 Content-Range
            // 就知道这是从 N 开始的中段数据，会正常解码而不是等文件头。
            //
            // 历史坑：曾经在这种情况下回 200（不带 Content-Range），
            // 播放器拿到一个「大文件的中段」却没有位置信息，认为文件头缺失，
            // 干等 30 秒后报 handleError: -2——正是用户反馈的
            // 「一直缓冲然后播放失败」。
            total = streamLength();
            long lagBytes = total - startFrom;
            long maxLagBytes = Math.max(MIN_LAG_LIMIT_BYTES,
                    (long) (measuredBytesPerSec() * RESUME_JUMP_SECONDS));
            boolean tooOld = lagBytes > maxLagBytes;

            long alignedFrom;
            if (tooOld) {
                // 从最新的关键帧开始，等于直接丢弃中间的历史
                long target = total - Math.max(MIN_LAG_LIMIT_BYTES,
                        (long) (measuredBytesPerSec() * 0.3));
                alignedFrom = nearestKeyframe(target);
                if (alignedFrom < 0) {
                    alignedFrom = target;
                }
                Log.w(TAG, "请求位置太旧（落后 " + (lagBytes / 1024) + "KB ≈ "
                        + (lagBytes / Math.max(1, measuredBytesPerSec())) + " 秒），"
                        + "丢弃历史，从最新关键帧 " + alignedFrom + " 开始");
            } else {
                // 落后不多：从它请求的位置（对齐到最近关键帧）继续推
                alignedFrom = nearestKeyframe(startFrom);
                if (alignedFrom < 0 || alignedFrom > total) {
                    alignedFrom = startFrom;
                }
            }

            // 响应头策略：只要「实际发送起点」和「播放器请求的起点」不一致，
            // 就必须用 206 + Content-Range 如实声明真实位置。
            //
            // 播放器靠 Content-Range 判断自己拿到的是文件的哪一段。
            // 如果回 200（声称是完整文件）却从中段开始发，它拿不到文件头，
            // 会一直等到超时报 handleError: -2。
            //
            // 反过来，起点完全一致时才用 200 无限流（无 Content-Length），
            // 表示「这是个持续增长的直播源」。
            boolean partial = alignedFrom != startFrom || (hasRange && alignedFrom < total);

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
                // 对照实验发现的关键差异：静态文件带 Content-Length，
                // 接收端 0.2 秒就 onPrepared 并完整播完；而直播流不带，
                // 接收端一直等到 30 秒超时报 handleError: -2。
                //
                // 说明这个播放器（光元素/iJK）没有 Content-Length 就无法
                // 确定缓冲策略，宁可等到超时也不开始播。
                //
                // 但这个值是「播放器认为的流总长度」——它读满这么多字节就会
                // 判定文件结束、触发 onCompletion 退出播放。
                //
                // 踩过的坑：一开始按码率估算 10 分钟的量（约 190MB），
                // 结果播了 10 分钟就 onCompletion 断开了。
                //
                // 所以这里给一个实际不可能达到的上限（约 2TB）。
                // 播放器有了数字能立即开播，又永远不会读满而判结束。
                head.append("Content-Length: ").append(FAKE_STREAM_LENGTH).append("\r\n");
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
            streamFrom(out, alignedFrom, connId);
        } catch (Exception e) {
            Log.w(TAG, "连接结束 " + remote + ": " + e.getMessage());
        } finally {
            clientCount--;
            removeCursor(connId);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 从指定绝对偏移开始，把环形缓冲区里的数据持续推给客户端。 */
    private void streamFrom(OutputStream out, long startOffset, int connId) throws IOException {
        // 对齐到 TS 包边界（188 字节）。播放器从半个包里开始会直接解析失败。
        long cursor = alignToPacket(startOffset);
        long lastWrite = System.currentTimeMillis();

        // ---- 背压（backpressure）----
        //
        // 这段是整个推送逻辑的核心，修过两轮，把踩过的坑都记下来：
        //
        // 【第一轮】完全不限速：服务端以局域网极限速度（几十 Mbps）把环形
        // 缓冲里的历史数据全推过去。接收端播放器按字节数决定缓冲多少，
        // 一口气收到几十 MB 就先装满缓冲再慢慢播，画面永远滞后几十秒。
        //
        // 【第二轮】按「产出速率 × 经过时间」算发送预算：屏幕静止时产出
        // 速率会衰减到 0，预算不再增长，发送端永久卡在等待里。实测表现为
        // 「Screen Mirror 一直加载中、出网字节 92MB 后不再增加」。
        //
        // 【现在】真正的背压：只允许发送到「实时位置 + 窗口大小」。
        //   - 实时位置 = 流的当前末尾（totalWritten），有数据就前进
        //   - 静止时不前进，但也不会倒退，所以不会卡死，只是安静等待
        //   - 窗口给 1 秒的量，够播放器有缓冲垫，又不让它吞掉几十秒历史
        //
        // 这个模型不依赖任何速率估算，天然免疫「速率测错」的问题。
        //
        // 注意：这里不要再对起点做额外调整。
        // 曾经加过「起点落后超过窗口就拉到窗口内」，结果是：播放器请求
        // 偏移 0（首次连接的正常行为），而流已经跑了几十 MB，于是我们把
        // 起点拉到 22MB 处开始发。播放器收到的响应是 200（无 Content-Range），
        // 却拿到一个「大文件的中段」，它认为文件头缺失，等 30 秒后超时报
        // handleError: -2——正是用户反馈的「一直缓冲然后播放失败」。
        //
        // 起点必须在响应头生成时就定好（见 handle() 里的 alignedFrom），
        // streamFrom 只负责从这个位置往后按背压窗口发，不再改动起点。

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
                // 有多少发多少。背压窗口已经在下面统一控制发送节奏，
                // 这里不再单独做「落后太多就跳帧」——那个判断基于缓冲容量，
                // 和背压窗口的语义冲突，而且跳帧会让播放器收到时间戳跳跃
                // 的数据而判流异常退出。
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

                // 背压：只允许追到「实时位置 + 窗口」。
                //
                // totalWritten 就是流的当前末尾（实时位置）。cursor 是这条连接
                // 已经发到的位置。两者之差就是这条连接的落后量。
                //
                // 不再限制发送速度。
                //
                // 对照实验的结论：把同一段 TS 存成静态文件，接收端 0.2 秒
                // 就 onPrepared、完整播完 16 秒、正常 onCompletion。这说明
                // TS 封装和编码内容都没问题，播放器也完全吃得下快速数据。
                //
                // 之前加背压窗口是想压低延迟，结果适得其反：数据供给被卡成
                // 断续的滴灌，播放器把「暂时没有新数据」判成「流结束」，
                // 实测只播 5 秒就 onCompletion 退出；卡得再狠一点就是
                // 30 秒等不到数据报 handleError: -2。
                //
                // 所以这里恢复成「有多少发多少」，靠下面那个「落后太多就
                // 跳到最新关键帧」来控制延迟，而不是靠限制发送速度。
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
            servedBytes += chunk.length;
            updateCursor(connId, cursor);
            lastServedAt = System.currentTimeMillis();
            lastWrite = lastServedAt;
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
