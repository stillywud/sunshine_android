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

    /** 编码参数。1080p 对多数投屏接收端够用，码率不宜太高。 */
    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final int FPS = 25;
    private static final int BITRATE = 1_200_000;
    private static final int I_FRAME_INTERVAL = 1; // 秒
    private static final String MIME_TS = "video/mp2t";

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private Surface inputSurface;
    private DlnaTsMuxer muxer;
    private DlnaHttpServer httpServer;
    private Thread drainThread;

    private DlnaUpnp.Renderer target;
    private String mediaUrl;

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
        if (!running.compareAndSet(false, true)) {
            return "投屏已经在运行";
        }

        try {
            target = renderer;

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
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

            // 屏幕内容静止时，Surface 上没有新帧，编码器就彻底停止出帧。
            // 此时 HTTP 流不再增长，接收端缓冲耗尽后报 "handleError: -2" 断开
            // （实测：静止 60 秒只产出 2 帧，播放器随即报错）。
            //
            // 让编码器在无新帧时按固定间隔重复上一帧，流就永远是活的。
            // 这是录屏类应用的标配参数，个别编码器不认时会被忽略，不影响启动。
            format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                    1_000_000L / FPS);

            encoder = MediaCodec.createEncoderByType("video/avc");
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();

            // 3. TS 封装器 + HTTP 服务
            muxer = new DlnaTsMuxer(FPS, false);
            httpServer = new DlnaHttpServer(MIME_TS);
            int port = httpServer.start(0);

            String ip = localIpAddress();
            if (ip == null) {
                throw new IllegalStateException("无法获取本机 IP，请检查网络");
            }
            mediaUrl = "http://" + ip + ":" + port + "/live.ts";
            Log.i(TAG, "流地址: " + mediaUrl);

            // 4. 虚拟显示器，把屏幕内容投到编码器的 Surface
            int[] size = screenSize();
            virtualDisplay = projection.createVirtualDisplay(
                    "sunshine-dlna",
                    size[0], size[1], size[2],
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null);

            // 5. 开始把编码输出喂给 muxer
            startDraining();

            // 6. 推送 URL 给接收端
            boolean pushed = DlnaUpnp.play(renderer, mediaUrl, MIME_TS);
            if (!pushed) {
                stop();
                return "推送到「" + renderer.name + "」失败";
            }

            Log.i(TAG, "投屏已启动 -> " + renderer.name);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "启动失败", e);
            stop();
            return "启动失败: " + e.getMessage();
        }
    }

    public void stop() {
        running.set(false);

        try {
            if (target != null) {
                DlnaUpnp.stop(target);
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
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "DlnaKeyframe");
        keyframeThread.setDaemon(true);
        keyframeThread.start();

        drainThread = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long frameCount = 0;
            long lastLog = 0;

            // 最近一帧的编码数据（含是否关键帧），静止时用来重复发送
            byte[] lastFrame = null;
            boolean lastWasKey = false;
            long lastFrameAt = System.currentTimeMillis();

            while (running.get()) {
                try {
                    int index = encoder.dequeueOutputBuffer(info, 200);
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
                                boolean keyFrame =
                                        (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                                // 留一份裸数据，静止时重复发
                                byte[] raw = new byte[info.size];
                                ByteBuffer dup = buf.duplicate();
                                dup.position(info.offset);
                                dup.get(raw, 0, info.size);
                                lastFrame = raw;
                                lastWasKey = keyFrame;

                                emitFrame(muxer.pushVideo(buf, info), keyFrame);
                                frameCount++;
                                lastFrameAt = System.currentTimeMillis();
                                long now = lastFrameAt;
                                if (now - lastLog > 5000) {
                                    lastLog = now;
                                    Log.i(TAG, "已编码 " + frameCount + " 帧, 流总长 "
                                            + httpServer.streamLength() + " 字节");
                                }
                            }
                        }
                        encoder.releaseOutputBuffer(index, false);
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "编码器输出格式: " + encoder.getOutputFormat());
                    }

                    // 兜底：超过 2 个帧间隔没有新帧，说明屏幕静止、编码器停帧。
                    // 重发上一帧，让 HTTP 流保持增长，接收端不会缓冲耗尽后断开。
                    // （KEY_REPEAT_PREVIOUS_FRAME_AFTER 在部分编码器上不生效，
                    //   所以不能只靠它。）
                    long now = System.currentTimeMillis();
                    if (lastFrame != null && now - lastFrameAt > 2L * 1000L / FPS) {
                        emitFrame(muxer.pushVideoRaw(lastFrame, lastWasKey), lastWasKey);
                        lastFrameAt = now;
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
        if (keyFrame) {
            // 这批数据以 PAT/PMT + SPS/PPS 开头，
            // 记下它的起始位置，新连接从这里开始推，
            // 播放器才能立刻拿到节目表和解码参数
            httpServer.markKeyframe();
        }
        httpServer.write(ts);
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
        // 编码器是 16:9，采集尺寸保持一致，避免缩放
        return new int[]{WIDTH, HEIGHT, dpi};
    }
}
