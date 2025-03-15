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
import android.view.Surface;
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

    // 添加发送音频样本的方法
    public static native void postAudioSample(float[] audioData, int sampleCount);


    // surface created by MediaCodec
    public static void createVirtualDisplay(int width, int height, int frameRate, int packetDuration, Surface surface, boolean shouldMute) {
        // create virtual display by MediaProjection
        // 通过 MediaProjection 创建虚拟显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 配置音频捕获参数
            int sampleRate = 48000; // 与您的Opus配置匹配
            int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            int audioFormat = AudioFormat.ENCODING_PCM_FLOAT;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2;

            // 计算每个数据包的帧数 (每个通道的样本数)
            // packetDuration 是毫秒，所以需要除以1000转换为秒
            int framesPerPacket = (int) (sampleRate * packetDuration / 1000.0f);
            AudioPlaybackCaptureConfiguration config = null;
            config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
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
            audioRecord.startRecording();
            NativeBridge.startAudioRecording(audioRecord, framesPerPacket);
        }

        VirtualDisplay virtualDisplay = mediaProjection.createVirtualDisplay(
                "sunshine",
                width, height, 160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
        );
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
