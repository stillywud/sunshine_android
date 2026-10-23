package com.nightmare.sunshine.dlna;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DLNA 投屏的音频采集：捕获系统正在播放的声音，编码成 AAC。
 *
 * 用 AudioPlaybackCapture 而不是麦克风。这两者的区别很关键：
 *   麦克风（MediaRecorder.AudioSource.MIC）录的是环境声，而且会独占麦克风，
 *   投屏时用户没法正常打电话/录音，听到的也不是设备真正在放的声音。
 *   AudioPlaybackCapture 走的是系统内部回采，录的就是扬声器输出的那份音频，
 *   不占用麦克风硬件。
 *
 * 硬性限制：AudioPlaybackCaptureConfiguration 从 API 29（Android 10）才有。
 * 更低的系统上系统级音频回采没有对普通应用开放的方案，只能降级为纯视频。
 */
public class DlnaAudioCapture {
    private static final String TAG = "DlnaAudio";

    /** 采样率。48kHz 是投屏/电视接收端最通用的值。 */
    public static final int SAMPLE_RATE = 48_000;
    private static final int CHANNEL_COUNT = 2;
    private static final int BITRATE = 128_000;

    /** AAC 一帧固定 1024 个采样点，muxer 用它推导 PTS。 */
    private static final int SAMPLES_PER_FRAME = 1024;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private AudioRecord record;
    private MediaCodec encoder;
    private Thread thread;

    /** 编码产出的回调：codecConfig=true 表示这是 AudioSpecificConfig（CSD），不是音频帧 */
    public interface OnFrame {
        void onFrame(byte[] data, boolean codecConfig, long presentationTimeUs);
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 尝试启动音频采集。
     *
     * @return 成功返回 null；失败返回原因（调用方据此决定是否只推视频）
     */
    public String start(MediaProjection projection, OnFrame callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 及以下没有对普通应用开放的系统音频回采。
            // 只能不推音频，绝不能退回麦克风——那会占用麦克风且录到的是环境声。
            return "Android " + Build.VERSION.RELEASE
                    + " 不支持捕获系统声音（需要 Android 10 及以上）";
        }
        if (!running.compareAndSet(false, true)) {
            return "音频采集已在运行";
        }

        try {
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();

            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                throw new IllegalStateException("AudioRecord 不支持 48kHz 立体声");
            }

            record = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                    .setBufferSizeInBytes(minBuf * 2)
                    .build();

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord 初始化失败（可能缺少录音权限）");
            }

            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            record.startRecording();

            startLoop(callback);
            Log.i(TAG, "音频采集已启动 " + SAMPLE_RATE + "Hz/" + CHANNEL_COUNT + "ch AAC");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "音频采集启动失败: " + e.getMessage(), e);
            String reason = e.getMessage();
            stop();
            return "音频采集失败: " + reason;
        }
    }

    private void startLoop(OnFrame callback) {
        // Read one AAC frame (~21 ms), rather than a device-dependent multiple
        // of the minimum recording buffer (often 80+ ms of capture latency).
        final int bytesPerPcmFrame = CHANNEL_COUNT * 2;
        final byte[] pcm = new byte[SAMPLES_PER_FRAME * bytesPerPcmFrame];

        thread = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running.get()) {
                try {
                    int n = record.read(pcm, 0, pcm.length);
                    if (n > 0) {
                        long captureStartUs = System.nanoTime() / 1000L
                                - (n / bytesPerPcmFrame) * 1_000_000L / SAMPLE_RATE;
                        int offset = 0;
                        while (running.get() && offset < n) {
                            // Retain this PCM block until accepted. Previously a
                            // temporarily busy encoder silently dropped it and
                            // the muxer's frame-count clock hid the missing time.
                            drain(encoder, info, callback);
                            int inIndex = encoder.dequeueInputBuffer(10_000);
                            if (inIndex < 0) { continue; }
                            ByteBuffer ib = encoder.getInputBuffer(inIndex);
                            if (ib == null) {
                                throw new IllegalStateException("AAC input buffer unavailable");
                            }
                            ib.clear();
                            int count = Math.min(ib.remaining(), n - offset);
                            count -= count % bytesPerPcmFrame;
                            if (count == 0) { throw new IllegalStateException("AAC input buffer too small"); }
                            ib.put(pcm, offset, count);
                            long ptsUs = captureStartUs
                                    + (offset / bytesPerPcmFrame) * 1_000_000L / SAMPLE_RATE;
                            encoder.queueInputBuffer(inIndex, 0, count, ptsUs, 0);
                            offset += count;
                        }
                    }
                    drain(encoder, info, callback);
                } catch (IllegalStateException e) {
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "音频采集异常: " + e.getMessage());
                }
            }
        }, "DlnaAudio");
        thread.setDaemon(true);
        thread.start();
    }

    private void drain(MediaCodec codec, MediaCodec.BufferInfo info, OnFrame callback) {
        while (true) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "AAC 输出格式: " + codec.getOutputFormat());
                continue;
            }
            if (index < 0) {
                continue;
            }
            ByteBuffer buf = codec.getOutputBuffer(index);
            if (buf != null && info.size > 0) {
                byte[] data = new byte[info.size];
                buf.position(info.offset);
                buf.get(data, 0, info.size);
                boolean csd = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                callback.onFrame(data, csd, info.presentationTimeUs);
            }
            codec.releaseOutputBuffer(index, false);
        }
    }

    public void stop() {
        running.set(false);

        if (thread != null) {
            try {
                thread.join(800);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        try {
            if (record != null) {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
            }
        } catch (Exception ignored) {
        }
        try {
            if (record != null) {
                record.release();
            }
        } catch (Exception ignored) {
        }
        record = null;

        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        } catch (Exception ignored) {
        }
        encoder = null;
        Log.i(TAG, "音频采集已停止");
    }
}
