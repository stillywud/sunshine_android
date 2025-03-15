package com.nightmare.sunshine;

import android.app.AlertDialog;
import android.content.Context;
//import android.hardware.input.IInputManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
//import android.view.MotionEventHidden;
import android.view.Surface;
import android.widget.EditText;
import android.widget.Toast;

//import com.connect_screen.mirror.MediaProjectionService;
//import com.connect_screen.mirror.State;
//import com.connect_screen.mirror.shizuku.ServiceUtils;
//import com.connect_screen.mirror.shizuku.ShizukuUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

//import dev.rikka.tools.refine.Refine;

// 代码拷贝自 v2025.122.141614
public class SunshineServer {
    //    public static AutoRotateAndScaleForMoonlight autoRotateAndScaleForMoonlight;
    private static int screenWidth;
    private static int screenHeight;
    private static int originalVolume;

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
        SunshineServer.context = context;
    }

    static String TAG = "SunshineServer";

    public static void receiveMap(HashMap<String, Object> map) {
        // Process the received map
        for (String key : map.keySet()) {
            Log.d(TAG, "Key: " + key + " Value: " + map.get(key));
        }
    }

    /**
     * @noinspection DataFlowIssue
     */
    public static MediaFormat createMediaFormat(HashMap<String, Object> map) {
        for (String key : map.keySet()) {
            Log.d(TAG, "Key: " + key + " Value: " + map.get(key));
        }
//        struct config_t {
//            int width;  // Video width in pixels
//            int height;  // Video height in pixels
//            int framerate;  // Requested framerate, used in individual frame bitrate budget calculation
//            int bitrate;  // Video bitrate in kilobits (1000 bits) for requested framerate
//            int slicesPerFrame;  // Number of slices per frame
//            int numRefFrames;  // Max number of reference frames
//
//    /* Requested color range and SDR encoding colorspace, HDR encoding colorspace is always BT.2020+ST2084
//       Color range (encoderCscMode & 0x1) : 0 - limited, 1 - full
//       SDR encoding colorspace (encoderCscMode >> 1) : 0 - BT.601, 1 - BT.709, 2 - BT.2020 */
//            int encoderCscMode;
//
//            int videoFormat;  // 0 - H.264, 1 - HEVC, 2 - AV1
//
//    /* Encoding color depth (bit depth): 0 - 8-bit, 1 - 10-bit
//       HDR encoding activates when color depth is higher than 8-bit and the display which is being captured is operating in HDR mode */
//            int dynamicRange;
//
//            int chromaSamplingType;  // 0 - 4:2:0, 1 - 4:4:4
//
//            int enableIntraRefresh;  // 0 - disabled, 1 - enabled
//        };
        int width = (int) map.get("width");
        int height = (int) map.get("height");
        int bitrate = (int) map.get("bitrate") * 1000;
        int frameRate = (int) map.get("framerate");
        int videoFormat = (int) map.get("videoFormat");
        int colorstandard = (int) map.get("colorstandard");
        // full_range
        int full_range = (int) map.get("full_range");
        boolean isHevc = videoFormat == 1;
        String mime = isHevc ? "video/hevc" : "video/avc";
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        // KEY_OPERATING_RATE
        // KEY_MAX_FPS_TO_ENCODER
        // KEY_COMPLEXITY
        // KEY_MAX_B_FRAMES
        // KEY_PROFILE
        // KEY_LEVEL
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 100_000);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        if (isHevc) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51);
        } else {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42);
        }
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorstandard);
        // full_range
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, full_range);
        format.setInteger(MediaFormat.KEY_LATENCY, 0);
        // KEY_COLOR_TRANSFER
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        Log.d(TAG, "MediaFormat: " + format);

        return format;
    }

    static MediaCodec createCodec(MediaFormat format) {
        try {
            MediaCodec codec = MediaCodec.createEncoderByType(Objects.requireNonNull(format.getString(MediaFormat.KEY_MIME)));
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return codec;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create codec", e);
            return null;
        }
    }

    static Surface createSurface(HashMap<String, Object> map) {
        MediaFormat format = createMediaFormat(map);
        MediaCodec codec = createCodec(format);
        assert codec != null;
        codec.start();
        return codec.createInputSurface();
    }

    // 添加新的回调方法，当需要 PIN 码时被 C++ 代码调用
    public static void onPinRequested() {
        // 使用 Handler 将回调切换到主线程
        new Handler(Looper.getMainLooper()).post(() -> {
            Context context = SunshineServer.context;
            if (context == null) {
                return;
            }

            // 创建一个输入框
            final EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            // 限制输入长度为4位
            InputFilter[] filters = new InputFilter[1];
            filters[0] = new InputFilter.LengthFilter(4);
            input.setFilters(filters);

            // 创建对话框
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("请输入PIN码")
                    .setMessage("请输入4位数字PIN码")
                    .setView(input)
                    .setPositiveButton("确定", (dialog, which) -> {
                        String pin = input.getText().toString();
                        if (pin.length() == 4) {
                            // 这里添加处理PIN码的逻辑
                            // 例如：调用native方法将PIN码传递给C++代码
                            submitPin(pin);
                        } else {
                            Toast.makeText(context, "请输入4位数字PIN码", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", (dialog, which) -> dialog.cancel())
                    .show();
//            submitPin("1234");
        });
    }

    // 添加提交PIN码的native方法
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
            SunshineServer.startAudioRecording(audioRecord, framesPerPacket);
        }

        // 将 AudioRecord 传递给 SunshineServer 进行处理
        VirtualDisplay virtualDisplay = mediaProjection.createVirtualDisplay("sunshine",
                width, height, 160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
//        if (ShizukuUtils.hasPermission()) {
//            inputManager = ServiceUtils.getInputManager();
//            screenWidth = width;
//            screenHeight = height;
//        }
//        new Handler(Looper.getMainLooper()).post(() -> {
//            State.startNewJob(new ProjectViaMoonlight(width, height, frameRate, packetDuration, surface));
//        });
//        if (shouldMute && State.currentActivity.get() != null) {
//            AudioManager audioManager = (AudioManager) State.currentActivity.get().getSystemService(Context.AUDIO_SERVICE);
//            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
//            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
//        }
    }

    public static void stopVirtualDisplay() {
        new Handler(Looper.getMainLooper()).post(() -> {
//            CreateVirtualDisplay.powerOnScreen();
//            CreateVirtualDisplay.restoreAspectRatio();
            // TODO 研究一下 IWindowManager.setForcedDisplaySize 直接在 NeoDe 中调用就行
//            InputRouting.moveImeToDefault();
            // TODO 这个可以设置对应显示器的键盘
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

    private static Map<Integer, PointerStatus> pointers = new HashMap<>();

    // 添加处理触摸事件的静态方法
//    public static void handleTouchPacket(int eventType, int rotation, int pointerId,
//                                        float x, float y, float pressureOrDistance,
//                                        float contactAreaMajor, float contactAreaMinor) {
//        if (inputManager == null) {
//            return;
//        }
//        int displayRotation = State.mirrorVirtualDisplay.getDisplay().getRotation();
//        // 根据屏幕旋转调整坐标
//        float adjustedX = x * screenWidth;
//        float adjustedY = y * screenHeight;
//        switch (displayRotation) {
//            case Surface.ROTATION_90:
//                adjustedX = y * screenHeight;
//                adjustedY = (1 - x) * screenWidth;
//                break;
//            case Surface.ROTATION_180:
//                adjustedX = (1 - x) * screenWidth;
//                adjustedY = (1 - y) * screenHeight;
//                break;
//            case Surface.ROTATION_270:
//                adjustedX = (1 - y) * screenHeight;
//                adjustedY = x * screenWidth;
//                break;
//        }
//        pointerId = pointerId % 10;
//        switch (eventType) {
//            case 0x01: // LI_TOUCH_EVENT_DOWN
//                handleTouchEventDown(pointerId, adjustedX, adjustedY);
//                break;
//            case 0x02: // LI_TOUCH_EVENT_UP
//                handleTouchEventUp(pointerId, adjustedX, adjustedY, false);
//                break;
//            case 0x03: // LI_TOUCH_EVENT_MOVE
//                handleTouchEventMove(pointerId, adjustedX, adjustedY);
//                break;
//            case 0x04: // LI_TOUCH_EVENT_CANCEL
//                handleTouchEventUp(pointerId, adjustedX, adjustedY, true);
//                break;
//            case 0x07: // LI_TOUCH_EVENT_CANCEL_ALL
//                handleTouchEventCancelAll();
//                break;
//            default:
//                android.util.Log.e("SunshineServer", "未知的触摸事件类型: " + eventType);
//        }
//    }

//    private static void handleTouchEventDown(int pointerId, float x, float y) {
//        if (!bufferedMove.isEmpty()) {
//            bufferedMove.clear();
//            triggerTouchEventMove();
//        }
//        if (!pointers.containsKey(pointerId)) {
//            PointerStatus pointerStatus = new PointerStatus();
//            pointerStatus.x = x;
//            pointerStatus.y = y;
//            pointers.put(pointerId, pointerStatus);
//        }
//
//        int action = pointers.size() == 1 ? MotionEvent.ACTION_DOWN :
//                                    MotionEvent.ACTION_POINTER_DOWN | (pointerId << 8);
//        // 构造 MotionEvent
//        long downTime = SystemClock.uptimeMillis();
//        long eventTime = SystemClock.uptimeMillis();
//
//
//        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointers.size()];
//        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointers.size()];
//
//        int index = 0;
//        for (Integer k : pointers.keySet()) {
//            PointerStatus status = pointers.get(k);
//            properties[index] = new MotionEvent.PointerProperties();
//            properties[index].id = k;  // 保持id为原始的pointerId
//            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
//
//            coords[index] = new MotionEvent.PointerCoords();
//            coords[index].x = status.x;
//            coords[index].y = status.y;
//            coords[index].pressure = 1.0f;
//            index++;
//        }
//
//        // 构造 MotionEvent
//        MotionEvent event = MotionEvent.obtain(
//            downTime,
//            eventTime,
//            action,
//            pointers.size(), // 使用实际的触摸点数量
//            properties,
//            coords,
//            0, // metaState
//            0, // buttonState
//            1.0f, // xPrecision
//            1.0f, // yPrecision
//            0, // deviceId
//            0, // edgeFlags
//            InputDevice.SOURCE_TOUCHSCREEN,
//            0 // flags
//        );
//        if (State.mirrorVirtualDisplay != null) {
//            MotionEventHidden motionEventHidden = Refine.unsafeCast(event);
//            motionEventHidden.setDisplayId(State.mirrorVirtualDisplay.getDisplay().getDisplayId());
//            inputManager.injectInputEvent(event, 0);
//            android.util.Log.d("SunshineServer", "inject down: " + event);
//        }
//    }

//    private static void handleTouchEventUp(int pointerId, float x, float y, boolean cancelled) {
//        PointerStatus status = pointers.get(pointerId);
//        if(status == null) {
//            return;
//        }
//        if (!bufferedMove.isEmpty()) {
//            bufferedMove.clear();
//            triggerTouchEventMove();
//        }
//        status.x = x;
//        status.y = y;
//
//        // 确定动作类型
//        int action = pointers.size() == 1 ? MotionEvent.ACTION_UP :
//                MotionEvent.ACTION_POINTER_UP | (pointerId << 8);
//
//        // 构造 MotionEvent
//        long downTime = SystemClock.uptimeMillis();
//        long eventTime = SystemClock.uptimeMillis();
//
//        // 创建包含所有活跃触摸点的属性数组
//        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointers.size()];
//        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointers.size()];
//
//        int index = 0;
//        for (Integer k : pointers.keySet()) {
//            PointerStatus ps = pointers.get(k);
//            properties[index] = new MotionEvent.PointerProperties();
//            properties[index].id = k;  // 保持id为原始的pointerId
//            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
//
//            coords[index] = new MotionEvent.PointerCoords();
//            coords[index].x = ps.x;
//            coords[index].y = ps.y;
//            coords[index].pressure = k == pointerId ? 0.0f : 1.0f;
//            index++;
//        }
//
//        // 构造并注入 MotionEvent
//        MotionEvent event = MotionEvent.obtain(
//            downTime,
//            eventTime,
//            action,
//            pointers.size(), // 使用实际的触摸点数量
//            properties,
//            coords,
//            0, // metaState
//            0, // buttonState
//            1.0f, // xPrecision
//            1.0f, // yPrecision
//            0, // deviceId
//            0, // edgeFlags
//            InputDevice.SOURCE_TOUCHSCREEN,
//            cancelled ? MotionEvent.FLAG_CANCELED : 0 // flags
//        );
//
//        pointers.remove(pointerId);
//
//        if (State.mirrorVirtualDisplay != null) {
//            MotionEventHidden motionEventHidden = Refine.unsafeCast(event);
//            motionEventHidden.setDisplayId(State.mirrorVirtualDisplay.getDisplay().getDisplayId());
//            inputManager.injectInputEvent(event, 0);
//            android.util.Log.d("SunshineServer", "inject up: " + event);
//        }
//    }

    private static Set<Integer> bufferedMove = new HashSet<>();

    private static void handleTouchEventMove(int pointerId, float x, float y) {
        PointerStatus status = pointers.get(pointerId);
        if (status == null) {
            return;
        }

        if (bufferedMove.contains(pointerId)) {
            bufferedMove.clear();
            triggerTouchEventMove();
        } else {
            bufferedMove.add(pointerId);
        }

        // 更新指针位置
        status.x = x;
        status.y = y;
    }

//    private static void handleTouchEventCancelAll() {
//        // 取消所有触摸事件
//        long downTime = SystemClock.uptimeMillis();
//        long eventTime = SystemClock.uptimeMillis();
//
//
//        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointers.size()];
//        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointers.size()];
//
//        int index = 0;
//        for (Integer k : pointers.keySet()) {
//            PointerStatus status = pointers.get(k);
//            properties[index] = new MotionEvent.PointerProperties();
//            properties[index].id = k;
//            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
//
//            coords[index] = new MotionEvent.PointerCoords();
//            coords[index].x = status.x;
//            coords[index].y = status.y;
//            coords[index].pressure = 1.0f;
//            index++;
//        }
//
//        MotionEvent event = MotionEvent.obtain(
//            downTime,
//            eventTime,
//            MotionEvent.ACTION_CANCEL,
//            pointers.size(),
//            properties,
//            coords,
//            0, 0, 1.0f, 1.0f, 0, 0,
//            InputDevice.SOURCE_TOUCHSCREEN,
//            0
//        );
//        pointers.clear();
//
//        if (State.mirrorVirtualDisplay != null) {
//            MotionEventHidden motionEventHidden = Refine.unsafeCast(event);
//            motionEventHidden.setDisplayId(State.mirrorVirtualDisplay.getDisplay().getDisplayId());
//            inputManager.injectInputEvent(event, 0);
//            android.util.Log.d("SunshineServer", "inject cancel: " + event);
//        }
//    }

    private static void triggerTouchEventMove() {
        if (!bufferedMove.isEmpty()) {
            bufferedMove.clear();
            triggerTouchEventMove();
        }
        long downTime = SystemClock.uptimeMillis();
        long eventTime = SystemClock.uptimeMillis();

        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointers.size()];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointers.size()];

        int index = 0;
        for (Integer k : pointers.keySet()) {
            PointerStatus status = pointers.get(k);
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = k;
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;

            coords[index] = new MotionEvent.PointerCoords();
            coords[index].x = status.x;
            coords[index].y = status.y;
            coords[index].pressure = 1.0f;
            index++;
        }

        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                MotionEvent.ACTION_MOVE,
                pointers.size(),
                properties,
                coords,
                0, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
        );

//        if (State.mirrorVirtualDisplay != null) {
//            MotionEventHidden motionEventHidden = Refine.unsafeCast(event);
//            motionEventHidden.setDisplayId(State.mirrorVirtualDisplay.getDisplay().getDisplayId());
//            inputManager.injectInputEvent(event, 0);
//            android.util.Log.d("SunshineServer", "inject move: " + event);
//        }

    }

    // 添加显示编码器错误的方法
//    public static void showEncoderError(String errorMessage) {
//        new Handler(Looper.getMainLooper()).post(() -> {
//            Context context = State.currentActivity.get();
//            if (context == null) {
//                return;
//            }
//
//            new AlertDialog.Builder(context)
//                .setTitle("无法配置编码器")
//                .setMessage(errorMessage)
//                .setPositiveButton("确定", (dialog, which) -> {
//                    // 关闭对话框后停止虚拟显示
//                    stopVirtualDisplay();
//                })
//                .setCancelable(false)
//                .show();
//        });
//    }

}
