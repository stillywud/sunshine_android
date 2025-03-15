package com.nightmare.sunshine;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.util.HashMap;
import java.util.Objects;

public class MediaHelper {

    private static final String TAG = "MediaHelper";

    /**
     * @noinspection DataFlowIssue
     * 这里我本来想的是在 Java 侧创建 MediaFormat，然后传递给 C++ 侧
     * 但是 C++ 侧需要直接操作 MediaCodec，并且操作的是 AMediaCodec
     * Java 这把 MediaCodec 传过去没办法转换
     * translate
     * I originally wanted to create MediaFormat on the Java side and then pass it to the C++ side
     * But the C++ side needs to operate on MediaCodec directly, and it operates on AMediaCodec
     * Java can't convert this MediaCodec
     */
    public static MediaFormat createMediaFormat(HashMap<String, Object> map) {
        for (String key : map.keySet()) {
            Log.d(TAG, "Key: " + key + " Value: " + map.get(key));
        }
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
}
