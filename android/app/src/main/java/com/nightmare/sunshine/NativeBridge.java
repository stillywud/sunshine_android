package com.nightmare.sunshine;

import android.app.AlertDialog;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * NativeBridge 代码修改源 https://gitee.com/connect-screen/connect-screen
 * 做了大量的优化和修改
 */

public class NativeBridge {

    static {
        System.loadLibrary("sunshine");
    }

    public static native void start();
    public static native void stop();

    public static MediaProjection mediaProjection;

    public static native void setSunshineName(String sunshineName);

    public static native void setPkeyPath(String path);

    public static native void setCertPath(String path);

    public static native void setFileStatePath(String path);

    static Context context;

    public static void init(Context context) {
        NativeBridge.context = context;
    }

    static String TAG = "SunshineServer";

    static void onPinRequested() {

    }

    public static native void submitPin(String pin);

    /**
     * 设置画面旋转模式（存 SharedPreferences，跨进程共享）
     * 0=默认（不旋转），1=左转90°，2=180°，3=右转90°
     */
    public static void setRotationMode(int mode) {
        if (context != null) {
            context.getSharedPreferences("sunshine_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("rotation_mode", mode).apply();
        }
    }

    /**
     * 读取画面旋转模式（供 native 层通过 JNI 调用）
     */
    public static int getRotationMode() {
        if (context != null) {
            return context.getSharedPreferences("sunshine_prefs", Context.MODE_PRIVATE)
                    .getInt("rotation_mode", 0);
        }
        return 0;
    }

    /**
     * 设置画面填充方式（存 SharedPreferences，跨进程共享）
     * 0=适应（等比缩放，可能留黑边），1=裁剪铺满（裁掉超出部分，无黑边）
     */
    public static void setFillMode(int mode) {
        if (context != null) {
            context.getSharedPreferences("sunshine_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("fill_mode", mode).apply();
        }
    }

    /** 读取画面填充方式（供 native 层通过 JNI 调用） */
    public static int getFillMode() {
        if (context != null) {
            return context.getSharedPreferences("sunshine_prefs", Context.MODE_PRIVATE)
                    .getInt("fill_mode", 0);
        }
        return 0;
    }

    /**
     * 返回设备当前屏幕方向：1=竖屏(Configuration.ORIENTATION_PORTRAIT)，
     * 2=横屏(Configuration.ORIENTATION_LANDSCAPE)，0=未知
     */
    public static int getDeviceOrientation() {
        if (context != null) {
            return context.getResources().getConfiguration().orientation;
        }
        return 0;
    }

    /**
     * 逻辑屏幕宽度（px）。GL 旋转管线用它创建与源画面对齐的 VirtualDisplay，
     * 避免系统按客户端请求尺寸做 letterbox。
     */
    public static int getScreenWidth() {
        if (context != null) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            return metrics.widthPixels;
        }
        return 0;
    }

    /** 逻辑屏幕高度（px），见 getScreenWidth()。 */
    public static int getScreenHeight() {
        if (context != null) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            return metrics.heightPixels;
        }
        return 0;
    }

    /**
     * 屏幕内容相对自然方向的旋转角（0/90/180/270），顺时针。
     * 目前仅用于日志诊断，旋转模式由用户显式选择。
     */
    public static int getDisplayRotation() {
        if (context != null) {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                switch (display.getRotation()) {
                    case Surface.ROTATION_90:
                        return 90;
                    case Surface.ROTATION_180:
                        return 180;
                    case Surface.ROTATION_270:
                        return 270;
                    default:
                        return 0;
                }
            }
        }
        return 0;
    }

    // 添加发送音频样本的方法
    public static native void postAudioSample(float[] audioData, int sampleCount);

    /** native 侧帧到达通知（由 SurfaceTexture 回调触发） */
    public static native void onCaptureFrameAvailable();

    /** 在 Java 主线程创建一个 SurfaceTexture + Surface，供 native 采集使用。 */
    public static Object[] createCaptureSurface(int width, int height) {
        final android.graphics.SurfaceTexture surfaceTexture =
                new android.graphics.SurfaceTexture(false);
        surfaceTexture.setDefaultBufferSize(width, height);

        // Frame callbacks must not share the Flutter UI thread's Looper: under
        // load the UI thread delays dispatch, and the capture loop stalls.
        android.os.HandlerThread callbackThread =
                new android.os.HandlerThread("SunshineFrameCb");
        callbackThread.start();
        final android.os.Handler handler = new android.os.Handler(callbackThread.getLooper());
        surfaceTexture.setOnFrameAvailableListener(st -> {
            try {
                onCaptureFrameAvailable();
            } catch (Throwable ignored) {
            }
        }, handler);

        Surface surface = new Surface(surfaceTexture);
        return new Object[]{surfaceTexture, surface, callbackThread};
    }

    /** 释放采集 Surface（native 渲染循环退出时调用）。 */
    public static void releaseCaptureSurface(android.graphics.SurfaceTexture surfaceTexture,
                                             Surface surface,
                                             android.os.HandlerThread callbackThread) {
        try {
            if (surfaceTexture != null) surfaceTexture.setOnFrameAvailableListener(null);
        } catch (Exception ignored) {
        }
        try {
            if (surface != null) surface.release();
        } catch (Exception ignored) {
        }
        try {
            if (surfaceTexture != null) surfaceTexture.release();
        } catch (Exception ignored) {
        }
        if (callbackThread != null) {
            callbackThread.quitSafely();
        }
    }

    // surface created by MediaCodec
    public static void createVirtualDisplay(int width, int height, int frameRate, int packetDuration, Surface surface, boolean shouldMute) {
        // Audio playback capture is optional. On some devices (e.g. OnePlus on
        // Android 12) AudioRecord creation is denied by the audio policy, which
        // throws IllegalStateException from startRecording(). That used to abort
        // this method before createVirtualDisplay() below ran, so video never
        // started at all and the client showed a black screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startAudioCapture(packetDuration);
        }

        VirtualDisplay virtualDisplay = mediaProjection.createVirtualDisplay(
                "sunshine",
                width, height, 160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
        );
        Log.i(TAG, "VirtualDisplay created: " + width + "x" + height
                + " display=" + (virtualDisplay != null));
    }

    /** 启动音频回采；失败只记日志，不影响视频链路。 */
    private static void startAudioCapture(int packetDuration) {
        try {
            int sampleRate = 48000; // 与您的Opus配置匹配
            int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            int audioFormat = AudioFormat.ENCODING_PCM_FLOAT;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2;

            // 计算每个数据包的帧数 (每个通道的样本数)
            // packetDuration 是毫秒，所以需要除以1000转换为秒
            int framesPerPacket = (int) (sampleRate * packetDuration / 1000.0f);
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            AudioRecord audioRecord = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build();
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized, continuing without audio capture");
                audioRecord.release();
                return;
            }
            audioRecord.startRecording();
            NativeBridge.startAudioRecording(audioRecord, framesPerPacket);
        } catch (Exception e) {
            Log.w(TAG, "Audio capture unavailable, continuing without it", e);
        }
    }

    public static void stopVirtualDisplay() {
        new Handler(Looper.getMainLooper()).post(() -> {
//            CreateVirtualDisplay.powerOnScreen();
//            CreateVirtualDisplay.restoreAspectRatio();

//            IWindowManager windowManager = ServiceUtils.getWindowManager();
//            windowManager.setDisplayImePolicy(Display.DEFAULT_DISPLAY, 0);
//            if (originalVolume != 0 && MediaProjectionService.instance != null) {
//                State.log("恢复音量: " + originalVolume);
//                AudioManager audioManager = (AudioManager) MediaProjectionService.instance.getSystemService(Context.AUDIO_SERVICE);
//                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
//                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
//            }
//            if (autoRotateAndScaleForMoonlight != null) {
//                autoRotateAndScaleForMoonlight.stop();
//                autoRotateAndScaleForMoonlight = null;
//            }
//            if (State.mirrorVirtualDisplay != null) {
//                State.mirrorVirtualDisplay.release();
//                State.mirrorVirtualDisplay = null;
//                ExitAll.execute(MediaProjectionService.instance, true);
//            }
        });
    }

    // 添加新方法用于启动音频录制
    public static native void startAudioRecording(AudioRecord audioRecord, int framesPerPacket);

    public static native void enableH265();

    private static class PointerStatus {
        public float x = 0;

        public float y = 0;
    }


}
