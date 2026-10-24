package com.nightmare.sunshine.dlna;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DLNA 投屏的媒体管线：采集 -> 编码 -> 封装 TS -> HTTP 提供给接收端。
 *
 * 和现有的 Moonlight 串流管线完全独立，互不干扰：
 *   Moonlight: MediaProjection -> GL旋转 -> MediaCodec -> RTP
 *   DLNA:      MediaProjection -> MediaCodec -> TS -> 本地 HTTP
 *
 * 之所以不复用 Moonlight 那条，是因为它的输出是裸 H.264 打 RTP，
 * 而 DLNA 需要「看起来像视频文件」的 TS 容器，两者终点不同。
 * 独立实现风险更低，也不会把已经调好的串流链路搞坏。
 */
public class DlnaStreamer {
    private static final String TAG = "DlnaStreamer";

    // Keep the existing 720p25 / 3 Mbps profile while isolating transport and
    // receiver buffering changes. Configured FPS does not bound encoder output
    // on every device; observed FPS must be measured separately.
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 25;
    /**
     * 接收端播放器的起播缓冲阈值按字计量(两次独立实测都指向约 1.4MB:
     * 4.3s×2.6Mbps 与 6.4s×1.8Mbps),不是按时间。把码率提到 6Mbps 后,
     * 同样的字节阈值只折合 ~2 秒,从流开头加入的滞后随之降到 3 秒级,
     * 不再需要任何播放中重载。
     */
    private static final int BITRATE = 6_000_000;
    private static final int I_FRAME_INTERVAL = 2; // 秒
    private static final String MIME_TS = "video/mp2t";

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final Object transportLock = new Object();

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private Surface inputSurface;
    private DlnaVideoSource videoSource;
    private volatile DlnaPlaybackMonitor playbackMonitor;
    private DlnaTsMuxer muxer;
    private DlnaHttpServer httpServer;
    private Thread drainThread;

    private DlnaUpnp.Renderer target;
    private String mediaUrl;
    private boolean lowLatency;
    /** 当前推送给接收端的地址（含变体查询参数）；漂移恢复时递增。 */
    private volatile String livePushUrl;
    private final AtomicInteger liveRepush = new AtomicInteger();
    /** 最近一次重推的重载看门狗线程。 */
    private volatile Thread reloadWatchdog;
    /**
     * Stop 与 SetAVTransportURI 之间的结算延迟:接收端旧播放器的销毁是异步的,
     * 立刻重推会让新播放器与销毁竞态,渲染管线(GL 着色器/解码器配置)损坏,
     * 画面永久停在加载界面直至 30 秒超时 -2。
     */
    private static final long RELOAD_SETTLE_MS = 800;
    /**
     * 漂移恢复的保守门槛:任何重载对接收端都是一次 30 秒级的事故风险,
     * 只有确实落后到难以观看时才值得赌一次。
     */
    private static final long RELOAD_DRIFT_MS = 4_000;
    private static final long RELOAD_COOLDOWN_MS = 120_000;
    private static final long RELOAD_WATCHDOG_POLL_MS = 2_000;
    /** 覆盖接收端 30 秒加载超时后再加余量,然后才允许唯一一次重试。 */
    private static final int RELOAD_WATCHDOG_SAMPLES = 20;
    private static final int RELOAD_MAX_RETRIES = 1;

    /** 音频采集（可选）。null 或未运行时表示纯视频投屏。 */
    private DlnaAudioCapture audioCapture;
    /** 音频不可用的原因，供界面提示 */
    private String audioError;
    /** 已产出的音频帧数，只用于日志 */
    private volatile long audioFrameCount;

    // ---- 运行统计（给界面调试用）----
    /** 已产出的视频帧数 */
    private volatile long videoFrameCount;
    /** 当前实测码率（bits/秒），按最近一段窗口计算 */
    private volatile long measuredBitrate;
    /** 已发送给接收端的总字节数（即接收端实际拉走的数据量） */
    private volatile long bytesServed;
    /** 接收端连接数，用于判断接收端是否在持续拉流 */
    private volatile int activeClients;
    /** 接收端最近一次拉流的时刻 */
    private volatile long lastClientActivityAt;

    public DlnaStreamer(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public String getTargetName() {
        return target != null ? target.name : "";
    }

    /** 当前是否带音频 */
    public boolean hasAudio() {
        return audioCapture != null && audioCapture.isRunning();
    }

    /** 音频不可用的原因（没有音频时才有意义） */
    public String getAudioError() {
        return audioError != null ? audioError : "";
    }

    /**
     * 运行统计，给界面调试用。
     *
     * 字段用逗号分隔（外层 status 串用竖线分隔）：
     *   码率bps,视频帧,音频帧,出网字节,连接数,接收端是否活跃(0/1),积压秒数
     *
     * 「积压秒数」是个关键指标：它等于（编码产出的流长度 − 接收端已拉走的字节）
     * 换算成时间。数值大说明接收端拉不过来，画面会明显落后于实时。
     */
    public String getStats() {
        long backlog = httpServer != null ? httpServer.lagSeconds() : 0;
        boolean clientActive = httpServer != null
                && httpServer.clientCount() > 0
                && System.currentTimeMillis() - httpServer.lastServedAt() < 3000;
        return measuredBitrate + "," + videoFrameCount + "," + audioFrameCount
                + "," + (httpServer != null ? httpServer.servedBytes() : 0)
                + "," + (httpServer != null ? httpServer.clientCount() : 0)
                + "," + (clientActive ? 1 : 0)
                + "," + backlog;
    }

    private String audioSuffix() {
        return hasAudio() ? "（含音频）" : "（无音频：" + getAudioError() + "）";
    }

    /** 本机在局域网里的 IPv4 地址，用于拼流地址。 */
    public static String localIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("169.254.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "获取本机 IP 失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 启动投屏。
     *
     * @param resultCode MediaProjection 授权结果
     * @param data       授权 Intent
     * @param renderer   目标 DLNA 接收端
     * @return 成功返回 null，失败返回原因
     */
    public String start(int resultCode, Intent data, DlnaUpnp.Renderer renderer) {
        return start(resultCode, data, renderer, true);
    }

    public String start(int resultCode, Intent data, DlnaUpnp.Renderer renderer, boolean lowLatency) {
        if (!running.compareAndSet(false, true)) {
            return "投屏已经在运行";
        }
        stopping.set(false);

        try {
            target = renderer;
            this.lowLatency = lowLatency;

            // 1. 屏幕采集
            MediaProjectionManager mgr =
                    (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(resultCode, data);
            if (projection == null) {
                throw new IllegalStateException("MediaProjection 创建失败");
            }

            // 2. 编码器（H.264，输出到 Surface）
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            // KEY_FRAME_RATE describes the coding rate; it does not throttle
            // a 60 Hz Surface source. Drop excess frames before encoding so
            // receivers are not forced to decode >2x the configured budget.
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, (float) FPS);
            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                format.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f);
            } else {
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, lowLatency ? 1 : I_FRAME_INTERVAL);
            }
            // Request the codec's lowest supported pipeline latency. Hardware
            // encoders may otherwise retain several input frames even though
            // the transport is already live and paced.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.setInteger(MediaFormat.KEY_LATENCY, 0);
            }

            // 屏幕内容静止时，Surface 上没有新帧，编码器就彻底停止出帧。
            // 此时 HTTP 流不再增长，接收端缓冲耗尽后报 "handleError: -2" 断开
            // （实测：不设这个参数时，屏幕静止下视频只产出 1 帧/秒）。
            //
            // 让编码器在无新帧时按固定间隔重复上一帧，流就永远是活的。
            // 这是录屏类应用的标配参数。
            //
            // 副作用：编码器输出的「重复帧」也会被算进真实帧数，所以静止时
            // 观测到的帧率会接近这个间隔对应的值，而不是 0。这是正常的，
            // 不代表码率浪费——重复帧几乎没有数据量（几十字节）。
            if (!lowLatency) {
                format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                        1_000_000L / FPS);
            }


            encoder = MediaCodec.createEncoderByType("video/avc");
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();

            // 3. TS 封装器 + HTTP 服务
            //    hasAudio 先给 false：音频能否采集要等下面尝试过才知道。
            //    采到之后立刻 setHasAudio(true)，PMT 会跟着重写。
            int[] size = screenSize();
            muxer = new DlnaTsMuxer(FPS, false, DlnaAudioCapture.SAMPLE_RATE);
            httpServer = new DlnaHttpServer(MIME_TS);
            int port = httpServer.start(0);

            String ip = localIpAddress();
            if (ip == null) {
                throw new IllegalStateException("无法获取本机 IP，请检查网络");
            }
            String mimeType = lowLatency ? DlnaHlsStore.MIME_TYPE : MIME_TS;
            mediaUrl = "http://" + ip + ":" + port + (lowLatency ? "/live.m3u8" : "/live.ts");
            Log.i(TAG, "流地址: " + mediaUrl);

            // 4. 必须先注册 MediaProjection 回调，再 createVirtualDisplay。
            //
            //    Android 14 起这是硬性要求，否则抛：
            //      IllegalStateException: Must register a callback before starting
            //      capture, to manage resources in response to MediaProjection states.
            //
            //    回调的作用是让系统在用户主动停止投屏（下拉通知栏点停止）时
            //    能通知到我们，好把编码器、HTTP 服务一并释放。
            //
            //    注意：MediaProjection 的回调必须注册在「有 Looper 的线程」上，
            //    否则内部 Handler 建不出来，回调等于没注册，后面照样报上面那个错。
            //    这里调用方是后台线程，所以显式传一个主线程 Handler。
            android.os.Handler mainHandler =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "系统要求停止投屏（用户从通知栏停止）");
                    stop();
                }
            }, mainHandler);
            Log.i(TAG, "MediaProjection 回调已注册");

            // 5. 虚拟显示器，把屏幕内容投到编码器的 Surface
            Surface captureSurface = inputSurface;
            int captureWidth = size[0];
            int captureHeight = size[1];
            if (lowLatency) {
                videoSource = new DlnaVideoSource(inputSurface, size[0], size[1], WIDTH, HEIGHT, FPS,
                        error -> mainHandler.post(() -> {
                            Log.w(TAG, "DLNA video source failed: " + error.getMessage());
                            stop();
                        }));
                captureSurface = videoSource.start();
                captureWidth = videoSource.getCaptureWidth();
                captureHeight = videoSource.getCaptureHeight();
            }
            virtualDisplay = projection.createVirtualDisplay(
                    "sunshine-dlna",
                    captureWidth, captureHeight, size[2],
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    captureSurface, null, null);

            // 6. 开始把编码输出喂给 muxer
            startDraining();

            // 7. 音频轨（可选）。失败不影响视频，只是没声音。
            startAudio(projection);

            // 8. 推送 URL 给接收端
            boolean pushed;
            synchronized (transportLock) {
                if (stopping.get() || !running.get()) {
                    return "投屏已停止";
                }
                liveRepush.set(0);
                livePushUrl = mediaUrl;
                pushed = DlnaUpnp.play(renderer, mediaUrl, mimeType, RELOAD_SETTLE_MS);
                if (pushed && lowLatency && !stopping.get()) {
                    playbackMonitor = new DlnaPlaybackMonitor(renderer, mediaUrl,
                            RELOAD_DRIFT_MS, RELOAD_COOLDOWN_MS, () -> {
                        synchronized (transportLock) {
                            if (stopping.get() || !running.get() || target != renderer) {
                                return;
                            }
                            repushVaried(renderer, mimeType, "接收端播放落后");
                        }
                    });
                    playbackMonitor.start();
                }
            }
            if (!pushed) {
                stop();
                return "推送到「" + renderer.name + "」失败";
            }

            Log.i(TAG, "投屏已启动 -> " + renderer.name + audioSuffix());
            return null;
        } catch (Exception e) {
            Log.e(TAG, "启动失败", e);
            stop();
            return "启动失败: " + e.getMessage();
        }
    }

    /**
     * 用变体查询参数把当前直播重新推给接收端,并挂一个重载看门狗。
     *
     * 同一地址重复 SetAVTransportURI 会被接收端忽略;附加变体参数让它当作
     * 新媒体重载。重载可能与旧播放器的异步销毁竞态(解码器以 0x0 尺寸配置
     * 失败、回退软解、周期跳帧、30 秒加载超时 -2),因此除了 Stop 与
     * SetAVTransportURI 之间的结算延迟,还会在看门狗确认位置推进失败时
     * 有限次自动重推。
     */
    private void repushVaried(DlnaUpnp.Renderer renderer, String mimeType, String reason) {
        String varied = mediaUrl + "?r=" + liveRepush.incrementAndGet();
        Log.w(TAG, reason + ";变体重推回到直播边缘: " + varied);
        if (DlnaUpnp.play(renderer, varied, mimeType, RELOAD_SETTLE_MS)) {
            livePushUrl = varied;
            DlnaPlaybackMonitor monitor = playbackMonitor;
            if (monitor != null) {
                monitor.updateExpectedUrl(varied);
            }
            watchReload(varied, mimeType, 0);
        } else {
            Log.w(TAG, reason + "重推失败");
        }
    }

    /**
     * 重载看门狗:重推后接收端会重新从 0 开始缓冲并推进位置。若传输状态
     * 一直是 PLAYING 而位置始终为 0,说明重载撞上了销毁/初始化竞态(接收端
     * 将在 30 秒加载超时后报 -2),此时再重推一次给播放器一次干净的初始化。
     */
    private void watchReload(String pushedUrl, String mimeType, int attempt) {
        if (attempt >= RELOAD_MAX_RETRIES) {
            Log.w(TAG, "重载看门狗达到重试上限,等待下一次漂移恢复: " + pushedUrl);
            return;
        }
        Thread watchdog = new Thread(() -> {
            long previousPosition = -1;
            boolean advanced = false;
            for (int sampled = 0; sampled < RELOAD_WATCHDOG_SAMPLES; sampled++) {
                try {
                    Thread.sleep(RELOAD_WATCHDOG_POLL_MS);
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (stopping.get() || !running.get()) {
                    return;
                }
                DlnaUpnp.PlaybackSnapshot snapshot = DlnaUpnp.getPlaybackSnapshot(target);
                if (snapshot == null || !"PLAYING".equals(snapshot.transportState)) {
                    continue;
                }
                if (!DlnaPlaybackMonitor.sameMediaUri(snapshot.mediaUri, pushedUrl)) {
                    return;
                }
                if (snapshot.positionMs > 0 && snapshot.positionMs > previousPosition) {
                    advanced = true;
                    break;
                }
                previousPosition = snapshot.positionMs;
            }
            if (advanced || stopping.get() || !running.get()) {
                return;
            }
            Log.w(TAG, "重载后接收端位置未推进,自动重推(第 " + (attempt + 1) + " 次)");
            synchronized (transportLock) {
                if (stopping.get() || !running.get() || target == null) {
                    return;
                }
                repushVaried(target, mimeType, "重载看门狗");
            }
        }, "DlnaReloadWatchdog");
        watchdog.setDaemon(true);
        reloadWatchdog = watchdog;
        watchdog.start();
    }

    /**
     * 启动音频轨。失败只记日志并留下原因，视频照常投。
     *
     * 注意用的是 AudioPlaybackCapture（系统播放声音），不是麦克风——
     * 麦克风会独占硬件且录到的是环境声，投屏场景下完全不对。
     */
    private void startAudio(MediaProjection projection) {
        audioError = null;
        DlnaAudioCapture capture = new DlnaAudioCapture();
        String error = capture.start(projection, (data, codecConfig, presentationTimeUs) -> {
            synchronized (muxer) {
                byte[] ts = muxer.pushAudioRaw(data, codecConfig, presentationTimeUs);
                if (ts != null) {
                    httpServer.write(ts);
                }
            }
            if (!codecConfig) {
                audioFrameCount++;
            }
        });

        if (error != null) {
            audioError = error;
            Log.w(TAG, "音频不可用: " + error);
            return;
        }

        audioCapture = capture;
        // 让 PMT 声明音频流（必须在有音频帧之前）
        muxer.setHasAudio(true);
        Log.i(TAG, "音频轨已启用");
    }

    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        DlnaPlaybackMonitor monitor = playbackMonitor;
        playbackMonitor = null;
        if (monitor != null) {
            monitor.stop();
        }
        Thread watchdog = reloadWatchdog;
        reloadWatchdog = null;
        if (watchdog != null) {
            watchdog.interrupt();
        }
        DlnaVideoSource source = videoSource;
        videoSource = null;
        if (source != null) {
            source.stop();
        }
        running.set(false);

        try {
            if (audioCapture != null) {
                audioCapture.stop();
            }
        } catch (Exception ignored) {
        }
        audioCapture = null;

        try {
            synchronized (transportLock) {
                if (target != null) {
                    DlnaUpnp.stop(target);
                }
            }
        } catch (Exception ignored) {
        }

        if (keyframeThread != null) {
            try {
                keyframeThread.join(800);
            } catch (InterruptedException ignored) {
            }
            keyframeThread = null;
        }

        if (drainThread != null) {
            try {
                drainThread.join(1500);
            } catch (InterruptedException ignored) {
            }
            drainThread = null;
        }

        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
        } catch (Exception ignored) {
        }
        virtualDisplay = null;

        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        } catch (Exception ignored) {
        }
        encoder = null;

        try {
            if (inputSurface != null) {
                inputSurface.release();
            }
        } catch (Exception ignored) {
        }
        inputSurface = null;

        try {
            if (httpServer != null) {
                httpServer.stop();
            }
        } catch (Exception ignored) {
        }
        httpServer = null;

        try {
            if (projection != null) {
                projection.stop();
            }
        } catch (Exception ignored) {
        }
        projection = null;

        muxer = null;
        mediaUrl = null;
        Log.i(TAG, "投屏已停止");
    }

    /** 定时请求关键帧的线程：屏幕静止时编码器不出帧，流会断 */
    private Thread keyframeThread;

    private void startDraining() {
        // 独立的定时器线程，每秒请求一个关键帧。
        //
        // 不能在 drain 线程里 setParameters：dequeueOutputBuffer 会阻塞，
        // 把请求和取数据放同一个线程会导致关键帧请求延迟甚至死锁。
        keyframeThread = new Thread(() -> {
            while (running.get()) {
                try {
                    android.os.Bundle p = new android.os.Bundle();
                    p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    encoder.setParameters(p);
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(lowLatency ? 500 : 1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "DlnaKeyframe");
        keyframeThread.setDaemon(true);
        keyframeThread.start();

        startStatsThread();

        drainThread = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long frameCount = 0;
            long lastLog = 0;
            long lastCodecPtsUs = -1;
            long backwardsCodecPts = 0;
            long timingWindowAtNs = System.nanoTime();
            long timingWindowFrames = 0;
            long ageMinUs = Long.MAX_VALUE;
            long ageMaxUs = Long.MIN_VALUE;

            // 最近一帧的编码数据（含是否关键帧），静止时用来重复发送
            byte[] lastFrame = null;
            boolean lastWasKey = false;
            // 上一次「真正发出一帧」（真实帧或补帧）的时刻
            long lastSentAt = System.currentTimeMillis();

            // 补帧的最小间隔 = 一个帧周期。真实帧发出后会刷新 lastSentAt，
            // 所以补帧只在「编码器真空闲」时发生，不会和真实帧叠加。
            final long repeatIntervalMs = Math.max(2, 1000L / FPS);
            // 编码器自己已经有 KEY_REPEAT_PREVIOUS_FRAME_AFTER 在补帧（每帧周期
            // 一个）。这里的兜底只在它完全失效时才该生效，所以门槛放到 8 个
            // 帧周期——正常情况永远触发不到，避免两层补帧叠加抬高帧率和码率。
            final long repeatTriggerMs = repeatIntervalMs * 8;

            while (running.get()) {
                try {
                    int index = encoder.dequeueOutputBuffer(info, 10_000);
                    if (index >= 0) {
                        ByteBuffer buf = encoder.getOutputBuffer(index);
                        if (buf != null && info.size > 0) {
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                byte[] csd = new byte[info.size];
                                buf.position(info.offset);
                                buf.get(csd, 0, info.size);
                                muxer.setVideoCodecConfig(csd);
                                Log.i(TAG, "收到 SPS/PPS, " + csd.length + " 字节");
                            } else {
                                long dequeuedAtNs = System.nanoTime();
                                long ageUs = dequeuedAtNs / 1000L - info.presentationTimeUs;
                                ageMinUs = Math.min(ageMinUs, ageUs);
                                ageMaxUs = Math.max(ageMaxUs, ageUs);
                                if (lastCodecPtsUs >= 0 && info.presentationTimeUs <= lastCodecPtsUs) {
                                    backwardsCodecPts++;
                                }
                                lastCodecPtsUs = info.presentationTimeUs;
                                timingWindowFrames++;
                                boolean keyFrame =
                                        (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                                // 留一份裸数据，静止时重复发
                                byte[] raw = new byte[info.size];
                                ByteBuffer dup = buf.duplicate();
                                dup.position(info.offset);
                                dup.get(raw, 0, info.size);
                                lastFrame = raw;
                                lastWasKey = keyFrame;

                                synchronized (muxer) {
                                    emitFrame(muxer.pushVideo(buf, info), keyFrame);
                                }
                                frameCount++;
                                videoFrameCount = frameCount;
                                lastSentAt = System.currentTimeMillis();
                                long now = lastSentAt;
                                if (now - lastLog > 5000) {
                                    lastLog = now;
                                    long elapsedNs = dequeuedAtNs - timingWindowAtNs;
                                    Log.i(TAG, "timing fps=" + (elapsedNs > 0
                                            ? timingWindowFrames * 1_000_000_000.0 / elapsedNs : 0)
                                            + " codecAgeUs=" + ageMinUs + ".." + ageMaxUs
                                            + " nonMonotonicCodecPts=" + backwardsCodecPts);
                                    timingWindowAtNs = dequeuedAtNs;
                                    timingWindowFrames = 0;
                                    ageMinUs = Long.MAX_VALUE;
                                    ageMaxUs = Long.MIN_VALUE;
                                    Log.i(TAG, "已编码 " + frameCount + " 帧视频, "
                                            + audioFrameCount + " 帧音频, 流总长 "
                                            + httpServer.streamLength() + " 字节");
                                }
                            }
                        }
                        encoder.releaseOutputBuffer(index, false);
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "编码器输出格式: " + encoder.getOutputFormat());
                    }

                    // 兜底：连续多个帧间隔都没有新帧，说明屏幕静止、编码器停帧。
                    // 重发上一帧，让 HTTP 流保持增长，接收端不会缓冲耗尽后断开。
                    // （KEY_REPEAT_PREVIOUS_FRAME_AFTER 在部分编码器上不生效，
                    //   所以不能只靠它。）
                    //
                    // 注意这里用的是「距上次真正发帧」的时间，真实帧发出后会刷新
                    // lastSentAt，所以补帧只在真空闲时发生，不会和真实帧叠加。
                    long now = System.currentTimeMillis();
                    if (!lowLatency && lastFrame != null && now - lastSentAt >= repeatTriggerMs) {
                        // 补帧不计入 videoFrameCount：那是「真实编码帧」的统计，
                        // 混进补帧会让帧率看起来比配置高，误导排查。
                        synchronized (muxer) {
                            emitFrame(muxer.pushVideoRaw(lastFrame, lastWasKey), lastWasKey);
                        }
                        lastSentAt = now;
                    }
                    synchronized (muxer) {
                        byte[] clock = muxer.pollClock();
                        if (clock != null) { httpServer.write(clock); }
                    }
                } catch (IllegalStateException e) {
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "取编码输出异常: " + e.getMessage());
                }
            }
        }, "DlnaDrain");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    /** 把一批 TS 数据写入 HTTP 流；关键帧还要登记位置供新连接对齐。 */
    private void emitFrame(byte[] ts, boolean keyFrame) {
        if (ts == null) {
            return;
        }
        httpServer.write(ts, keyFrame);
    }

    /**
     * 每秒统计一次真实出网码率。
     *
     * 用「已推送给接收端的字节数」而不是「编码产出的字节数」，
     * 因为两者含义不同：前者是真实带宽占用，后者只是编码器产量。
     * 接收端拉不动的时候，两者会拉开差距，这个差距就是画面延迟的来源。
     */
    private void startStatsThread() {
        Thread t = new Thread(() -> {
            long lastBytes = 0;
            long lastAt = System.currentTimeMillis();
            while (running.get()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                if (httpServer == null) {
                    continue;
                }
                long now = System.currentTimeMillis();
                long bytes = httpServer.servedBytes();
                long elapsed = now - lastAt;
                if (elapsed > 0) {
                    measuredBitrate = (bytes - lastBytes) * 8000L / elapsed;
                }
                lastBytes = bytes;
                lastAt = now;

                // 每秒一条，字段顺序和 getStats() 一致，方便对照
                Log.i(TAG, "统计 " + getStats());
            }
        }, "DlnaStats");
        t.setDaemon(true);
        t.start();
    }

    /** 返回 [宽, 高, dpi]，按当前实际显示方向。 */
    private int[] screenSize() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        int w = WIDTH;
        int h = HEIGHT;
        int dpi = 320;
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
                w = b.width();
                h = b.height();
            } else {
                wm.getDefaultDisplay().getRealMetrics(dm);
                w = dm.widthPixels;
                h = dm.heightPixels;
            }
            dm = context.getResources().getDisplayMetrics();
            dpi = dm.densityDpi > 0 ? dm.densityDpi : 320;
        }
        return new int[]{lowLatency ? w : WIDTH, lowLatency ? h : HEIGHT, dpi};
    }
}
