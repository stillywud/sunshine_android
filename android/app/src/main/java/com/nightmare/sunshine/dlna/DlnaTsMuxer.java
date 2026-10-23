package com.nightmare.sunshine.dlna;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 把 H.264（以及可选的 AAC）裸流封装成 MPEG-TS。
 *
 * 为什么需要自己封装：DLNA 接收端（电视/投屏 App）绝大多数只接受
 * 「看起来像视频文件」的容器。裸 H.264 或者 RTP 包它们不认，
 * 所以必须打成 TS（DLNA 生态里兼容性最好的容器，比 HLS/FLV 都好）。
 *
 * TS 结构（最小可用集）：
 *   PID 0x0000  PAT  —— 指向 PMT
 *   PID 0x1000  PMT  —— 声明有哪些流、各自 PID 和编码类型
 *   PID 0x0100  PES  —— H.264 视频
 *   PID 0x0101  PES  —— AAC 音频（可选）
 *
 * 每个 TS packet 固定 188 字节，以 0x47 开头。
 */
public class DlnaTsMuxer {
    private static final String TAG = "DlnaTsMuxer";

    private static final int TS_PACKET_SIZE = 188;
    private static final int SYNC_BYTE = 0x47;

    private static final int PID_PAT = 0x0000;
    private static final int PID_PMT = 0x1000;
    private static final int PID_VIDEO = 0x0100;
    private static final int PID_AUDIO = 0x0101;

    /** PMT 里声明的编码类型 */
    private static final int STREAM_TYPE_H264 = 0x1B;
    private static final int STREAM_TYPE_AAC = 0x0F;

    /** AAC 一帧固定 1024 个采样点，音频 PTS 按它递增 */
    private static final int AAC_SAMPLES_PER_FRAME = 1024;
    /** ADTS 头（无 CRC）固定 7 字节 */
    private static final int ADTS_HEADER_SIZE = 7;
    /** AAC LC 在 ADTS profile 字段里的取值是 1（字段值 = profile+1） */
    private static final int ADTS_PROFILE_AAC_LC = 1;
    /** 声道数，和 DlnaAudioCapture 保持一致 */
    private static final int CHANNEL_COUNT = 2;
    /** 采样率索引表，ADTS 头里用 4 bit 编码 */
    private static final int[] AAC_SAMPLE_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000,
            22050, 16000, 12000, 11025, 8000, 7350
    };

    private static final int TS_HEADER_SIZE = 4;
    private static final int PAYLOAD_CAPACITY = TS_PACKET_SIZE - TS_HEADER_SIZE;

    /** PTS 时钟频率 90kHz（MPEG-TS 规范） */
    private static final long PTS_CLOCK = 90_000L;

    /** 首帧 PTS 的起始偏移（微秒）。部分播放器不接受 0。 */
    private static final long PTS_START_US = 1_000_000L;
    private static final byte[] ACCESS_UNIT_DELIMITER = {0, 0, 0, 1, 9, (byte) 0xF0};

    private final int fps;
    /**
     * 是否有音频轨。
     *
     * 不能用 final：音频能不能采到取决于系统版本和权限，
     * 要等 DlnaAudioCapture.start() 跑完才知道，之后再 setHasAudio() 修正。
     */
    private volatile boolean hasAudioField;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);

    private final int[] continuity = new int[0x2000];

    private byte[] codecConfig;   // H.264 的 SPS/PPS（AVCDecoderConfigurationRecord）
    private byte[] audioConfig;   // AAC 的 AudioSpecificConfig

    private long videoPtsUs;
    private long audioPtsUs;
    /** 已产出的视频帧数，用来生成严格单调递增的 PTS */
    private long videoFrameIndex;
    /** 已产出的音频帧数，同样用来推导 PTS */
    private long audioFrameIndex;
    /** 音频采样率，ADTS 头需要；未启用音频时为 0 */
    private int audioSampleRate;
    private boolean patPmtWritten;

    /**
     * 两条轨的统一时间基准（System.nanoTime 的微秒表示）。
     *
     * 视频和音频是两条独立线程产出的，启动时刻天然不同（实测差了 47 秒）。
     * 如果两条轨各自从 PTS_START_US 起算，音视频的时间轴会彻底错开，
     * 播放器表现为画面和声音不同步，而且 PTS 跨度远超真实时长。
     *
     * 所以用「第一条出帧的轨的时刻」作为共同零点，另一条轨的首帧 PTS
     * 按它自己的真实启动延迟顺延。这样两条轨的时间轴就对齐了。
     */
    private volatile long epochUs = -1;
    /** 音频首帧相对共用零点的 PTS 基准（微秒），-1 表示还没定 */
    private long audioBaseUs = -1;
    private long lastAudioPtsUs;
    /** 上一个视频帧的 PTS，用于强制单调递增 */
    private long lastVideoPtsUs;
    private long lastPcrNs;
    private long lastPcrBase = -1;

    /** Keep the declared PCR PID alive even when a static screen produces no video. */
    public synchronized byte[] pollClock() {
        if (epochUs < 0 || System.nanoTime() - lastPcrNs < 80_000_000L) { return null; }
        byte[] packet = new byte[TS_PACKET_SIZE];
        java.util.Arrays.fill(packet, (byte) 0xFF);
        packet[0] = 0x47;
        packet[1] = (byte) (PID_VIDEO >>> 8);
        packet[2] = (byte) PID_VIDEO;
        // Adaptation-only packets repeat the previous payload's continuity counter.
        packet[3] = (byte) (0x20 | ((continuity[PID_VIDEO] + 15) & 15));
        packet[4] = (byte) 183;
        packet[5] = 0x10;
        writePcr(packet);
        return packet;
    }

    private void writePcr(byte[] packet) {
        long now = System.nanoTime();
        long clockUs = now / 1000L - epochUs + PTS_START_US;
        long base = Math.max(lastPcrBase + 1, clockUs * PTS_CLOCK / 1_000_000L);
        lastPcrBase = base;
        lastPcrNs = now;
        base &= 0x1FFFFFFFFL;
        packet[6] = (byte) (base >>> 25);
        packet[7] = (byte) (base >>> 17);
        packet[8] = (byte) (base >>> 9);
        packet[9] = (byte) (base >>> 1);
        packet[10] = (byte) (((base & 1) << 7) | 0x7E);
        packet[11] = 0;
    }

    public DlnaTsMuxer(int fps, boolean hasAudio) {
        this(fps, hasAudio, 48_000);
    }

    public DlnaTsMuxer(int fps, boolean hasAudio, int audioSampleRate) {
        this.fps = Math.max(1, fps);
        this.hasAudioField = hasAudio;
        this.audioSampleRate = audioSampleRate > 0 ? audioSampleRate : 48_000;
    }

    /**
     * 编码器报告 SPS/PPS 时调用。
     * 这些数据要放在每个关键帧前面，否则播放器解不出画面。
     */
    public void setVideoCodecConfig(byte[] csd) {
        this.codecConfig = csd;
        this.patPmtWritten = false; // 新的配置要重新写 PAT/PMT
    }

    public void setAudioCodecConfig(byte[] csd) {
        this.audioConfig = csd;
        this.patPmtWritten = false;
    }

    /**
     * 声明是否有音频轨。
     *
     * 音频能不能采到要启动之后才知道，所以不能在构造时就定死。
     * 调用后强制重写 PAT/PMT，让接收端拿到正确的流组成。
     */
    public void setHasAudio(boolean value) {
        this.hasAudioField = value;
        this.patPmtWritten = false;
    }

    /**
     * 追加一帧视频。返回自上次 take() 之后累积的 TS 字节。
     */
    public synchronized byte[] pushVideo(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        byte[] data = readBuffer(buffer, info);
        if (data == null || data.length == 0) {
            return null;
        }
        return pushVideoRaw(data, (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
    }

    /**
     * 直接追加一帧已编码的 H.264 裸数据（Annex-B）。
     *
     * 除了编码器正常出帧，屏幕静止时还会被用来「重发上一帧」，
     * 让流保持增长，避免接收端缓冲耗尽后断开。
     */
    public synchronized byte[] pushVideoRaw(byte[] data, boolean keyFrame) {
        if (data == null || data.length == 0) {
            return null;
        }

        // Annex-B 起始码 + SPS/PPS 放在关键帧前面
        byte[] payload;
        if (keyFrame && codecConfig != null) {
            payload = concat(codecConfig, data);
        } else {
            payload = data;
        }

        // PTS 完全由帧计数推导，不用编码器的 presentationTimeUs。
        //
        // 实测这台设备上编码器给的时间戳不单调递增（同一批帧可能拿到相同值，
        // 甚至回退），直接透传会让 muxer 报 "non monotonically increasing dts"，
        // 播放器解析出 88 秒的虚假时长后卡在缓冲。
        //
        // 投屏是实时流，帧率固定，用「帧序号 × 帧间隔」生成 PTS 最稳，
        // 也天然保证严格单调递增。
        //
        // 但两条轨必须共用同一个时间零点，否则音视频各算各的会错开
        // （实测差了 47 秒，表现为音画不同步）。见 epochUs 的注释。
        //
        // 这里还有一层：不能用「帧序号 × 标称帧率」算 PTS，因为实际出帧速度
        // 可能远低于标称值（模拟器上实测只跑到 10.8fps，配置是 25fps）。
        // 那样算出来的时间轴会跑得比真实时间快，音频按真实采样率走，
        // 两条轨就会越差越远。
        //
        // 做法：视频 PTS 直接取真实经过时间。但要防抖动/回退——
        // 系统时间在卡顿时可能不走或跳变，PTS 一旦回退播放器就出错，
        // 所以强制比上一帧至少大 1ms。
        long pts = elapsedUsSince(epochFor(true)) + PTS_START_US;
        if (pts <= lastVideoPtsUs) {
            pts = lastVideoPtsUs + 1_000L;
        }
        lastVideoPtsUs = pts;
        videoPtsUs = pts;
        videoFrameIndex++;

        // 关键帧是播放器唯一能开始解码的位置（关键帧前面带 SPS/PPS）。
        // 播放器随时可能中途接入或断线重连，所以每个关键帧前都重发 PAT/PMT，
        // 让它无论从哪一帧开始都能立刻拿到节目表和流类型。
        if (keyFrame) {
            writePat();
            writePmt();
            patPmtWritten = true;
        } else {
            writePatPmtIfNeeded();
        }

        // Explicit AU boundaries let TS demuxers emit a frame without scanning
        // ahead into the next slice. Put AUD before SPS/PPS on random access.
        payload = concat(ACCESS_UNIT_DELIMITER, payload);
        writePes(PID_VIDEO, payload, videoPtsUs, true, keyFrame);
        return take();
    }

    /**
     * 追加一帧音频（AAC 裸帧）。没有音频轨时不会被调用。
     *
     * 这里要自己加 ADTS 头：MediaCodec 输出的是裸 AAC 帧，
     * 而 TS 里虽然理论上可以只靠 AudioSpecificConfig 解，
     * 但不少 DLNA 接收端解析 AAC 时依赖 ADTS 的帧边界和采样率字段。
     * 加上 7 字节头兼容性最好，代价可以忽略。
     */
    public synchronized byte[] pushAudioRaw(byte[] aacFrame, boolean codecConfig) {
        return pushAudioRaw(aacFrame, codecConfig, -1);
    }

    /** Preserve the common monotonic capture clock, including gaps in capture. */
    public synchronized byte[] pushAudioRaw(byte[] aacFrame, boolean codecConfig,
                                           long presentationTimeUs) {
        if (aacFrame == null || aacFrame.length == 0) {
            return null;
        }

        if (codecConfig) {
            audioConfig = aacFrame;
            patPmtWritten = false;
            Log.i(TAG, "收到 AAC AudioSpecificConfig, " + aacFrame.length + " 字节");
            return null;
        }

        // 音频 PTS 用「采样点计数」推导，这本身就是最准确的时间基准：
        // AAC 每帧固定 1024 采样点，48kHz 下一帧正好 21.33ms，
        // 天然严格单调、天然与真实播放速度一致，比读系统时钟更稳。
        //
        // 关键是要和视频共用同一个时间零点，否则视频先跑几十秒、
        // 音频后启动时，两条轨的时间轴会错开。
        if (presentationTimeUs >= 0) {
            // MediaCodec interpolates AAC frame PTS from PCM input timestamps.
            // Counting output AAC frames instead would compress dropped/paused
            // input intervals and progressively misalign audio with live video.
            audioPtsUs = Math.max(lastAudioPtsUs + 1_000L,
                    PTS_START_US + presentationTimeUs - epochFor(false));
        } else if (audioBaseUs < 0) {
            // 首帧：记下相对共用零点的真实启动延迟
            audioBaseUs = PTS_START_US + elapsedUsSince(epochFor(false));
        }
        if (presentationTimeUs < 0) {
            audioPtsUs = audioBaseUs
                    + audioFrameIndex * 1_000_000L * AAC_SAMPLES_PER_FRAME / audioSampleRate;
        }
        lastAudioPtsUs = audioPtsUs;
        audioFrameIndex++;

        writePatPmtIfNeeded();

        byte[] adts = addAdtsHeader(aacFrame);
        writePes(PID_AUDIO, adts, audioPtsUs, false, false);
        return take();
    }

    /**
     * 取得本轨首帧相对 PTS_START_US 的偏移（微秒），并维护两轨共用的时间零点。
     *
     * 视频和音频由两条独立线程产出，启动时刻天然不同（实测差了 47 秒）。
     * 如果两条轨都从 PTS_START_US 起算，时间轴就彻底错开，播放器表现为
     * 音画不同步。所以：
     *   - 第一条出帧的轨把当前时刻记为共用零点
     *   - 后到的轨也从同一个零点起算，它的首帧 PTS 自然就带上了启动延迟
     *
     * 这样两条轨的 PTS 都等于「相对共用零点的真实经过时间」，
     * 天然与真实时间一致，也不会因为实际帧率低于标称值而漂移。
     */
    private long epochFor(boolean video) {
        if (epochUs < 0) {
            epochUs = System.nanoTime() / 1000L;
        }
        return epochUs;
    }

    /** 相对给定零点的真实经过时间（微秒）。 */
    private long elapsedUsSince(long epoch) {
        return System.nanoTime() / 1000L - epoch;
    }

    /** 给裸 AAC 帧加 7 字节 ADTS 头（无 CRC）。 */
    private byte[] addAdtsHeader(byte[] aac) {
        int frameLen = aac.length + ADTS_HEADER_SIZE;
        byte[] out = new byte[frameLen];

        int freqIdx = 3; // 默认 48000
        for (int i = 0; i < AAC_SAMPLE_RATES.length; i++) {
            if (AAC_SAMPLE_RATES[i] == audioSampleRate) {
                freqIdx = i;
                break;
            }
        }

        // ADTS 头的字段是按 bit 拼的，这里逐字节写清楚，别省：
        //   [0]     syncword 高 8 位
        //   [1]     syncword 低 4 位 | ID=0(MPEG-4) | layer=00 | protection_absent=1
        //   [2]     profile(2) | sampling_frequency_index(4) | private(1) | 声道高位(1)
        //   [3]     声道低 2 位 | 4 个标志位 | aac_frame_length 高 2 位
        //   [4]     aac_frame_length 中 8 位
        //   [5]     aac_frame_length 低 3 位 | buffer_fullness 高 5 位
        //   [6]     buffer_fullness 低 6 位 | raw_data_blocks-1
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xF1;
        out[2] = (byte) ((ADTS_PROFILE_AAC_LC << 6)
                | (freqIdx << 2)
                | ((CHANNEL_COUNT >> 2) & 0x01));
        out[3] = (byte) (((CHANNEL_COUNT & 0x03) << 6)
                | ((frameLen >> 11) & 0x03));
        out[4] = (byte) ((frameLen >> 3) & 0xFF);
        // buffer_fullness 填 0x7FF 表示 VBR（码率不固定），这是 AAC 实时流的标准做法
        out[5] = (byte) (((frameLen & 0x07) << 5) | 0x1F);
        out[6] = (byte) 0xFC;

        System.arraycopy(aac, 0, out, ADTS_HEADER_SIZE, aac.length);
        return out;
    }

    /** 取出并清空累积的字节 */
    public synchronized byte[] take() {
        if (out.size() == 0) {
            return null;
        }
        byte[] result = out.toByteArray();
        out.reset();
        return result;
    }

    // ------------------------------------------------------------------
    // PAT / PMT
    // ------------------------------------------------------------------

    private void writePatPmtIfNeeded() {
        if (patPmtWritten) {
            return;
        }
        writePat();
        writePmt();
        patPmtWritten = true;
        Log.i(TAG, "PAT/PMT 已写入, audio=" + hasAudioField);
    }

    private void writePat() {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        sec.write(0x00);                  // table_id: program_association_section
        sec.write(0xB0);                  // section_syntax_indicator=1, '0', reserved=11, length高5位
        sec.write(0x0D);                  // section_length = 13
        sec.write(0x00); sec.write(0x01); // transport_stream_id = 1
        sec.write(0xC1);                  // reserved=11, version=0, current_next=1
        sec.write(0x00);                  // section_number
        sec.write(0x00);                  // last_section_number
        // program_number = 1 -> PMT PID
        sec.write(0x00); sec.write(0x01);
        sec.write(0xE0 | ((PID_PMT >> 8) & 0x1F));
        sec.write(PID_PMT & 0xFF);

        byte[] body = sec.toByteArray();
        int crc = crc32Mpeg(body);
        byte[] withCrc = concat(body, new byte[]{
                (byte) (crc >>> 24), (byte) (crc >>> 16),
                (byte) (crc >>> 8), (byte) crc});

        writeSection(PID_PAT, withCrc, 0x00);
    }

    private void writePmt() {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        sec.write(0x02);                  // table_id: program_map_section
        // section_length 稍后回填
        sec.write(0xB0);
        sec.write(0x00);
        sec.write(0x00); sec.write(0x01); // program_number = 1
        sec.write(0xC1);                  // reserved, version 0, current_next 1
        sec.write(0x00);                  // section_number
        sec.write(0x00);                  // last_section_number
        // PCR_PID 用视频 PID
        sec.write(0xE0 | ((PID_VIDEO >> 8) & 0x1F));
        sec.write(PID_VIDEO & 0xFF);
        // program_info_length = 0
        sec.write(0xF0); sec.write(0x00);

        // 视频流
        sec.write(STREAM_TYPE_H264);
        sec.write(0xE0 | ((PID_VIDEO >> 8) & 0x1F));
        sec.write(PID_VIDEO & 0xFF);
        sec.write(0xF0); sec.write(0x00); // ES_info_length = 0

        // 音频流
        if (hasAudioField) {
            sec.write(STREAM_TYPE_AAC);
            sec.write(0xE0 | ((PID_AUDIO >> 8) & 0x1F));
            sec.write(PID_AUDIO & 0xFF);
            sec.write(0xF0); sec.write(0x00);
        }

        byte[] body = sec.toByteArray();
        int sectionLength = body.length - 3 + 4; // 减去前 3 字节，加上 CRC
        body[1] = (byte) (0xB0 | ((sectionLength >> 8) & 0x0F));
        body[2] = (byte) (sectionLength & 0xFF);

        int crc = crc32Mpeg(body);
        byte[] withCrc = concat(body, new byte[]{
                (byte) (crc >>> 24), (byte) (crc >>> 16),
                (byte) (crc >>> 8), (byte) crc});

        writeSection(PID_PMT, withCrc, 0x02);
    }

    /** 把 PSI section 切成 TS packet 写出（PUSI=1，pointer_field=0） */
    private void writeSection(int pid, byte[] section, int tableId) {
        int offset = 0;
        boolean first = true;
        while (offset < section.length) {
            byte[] packet = new byte[TS_PACKET_SIZE];
            packet[0] = (byte) SYNC_BYTE;
            packet[1] = (byte) ((first ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
            packet[2] = (byte) (pid & 0xFF);
            packet[3] = (byte) (0x10 | (continuity[pid] & 0x0F));
            continuity[pid] = (continuity[pid] + 1) & 0x0F;

            int pos = TS_HEADER_SIZE;
            if (first) {
                packet[pos++] = 0x00; // pointer_field
            }
            int room = TS_PACKET_SIZE - pos;
            int n = Math.min(room, section.length - offset);
            System.arraycopy(section, offset, packet, pos, n);
            offset += n;

            // 剩余空间用 0xFF 填充（PSI 规范要求）
            for (int i = pos + n; i < TS_PACKET_SIZE; i++) {
                packet[i] = (byte) 0xFF;
            }
            out.write(packet, 0, TS_PACKET_SIZE);
            first = false;
        }
    }

    // ------------------------------------------------------------------
    // PES
    // ------------------------------------------------------------------

    private void writePes(int pid, byte[] payload, long ptsUs, boolean video, boolean keyFrame) {
        long pts = (ptsUs * PTS_CLOCK) / 1_000_000L;

        ByteArrayOutputStream pes = new ByteArrayOutputStream(payload.length + 32);
        pes.write(0x00); pes.write(0x00); pes.write(0x01);
        pes.write(video ? 0xE0 : 0xC0);   // stream_id

        // PES_packet_length 的定义：从本字段之后到 PES 包结尾的字节数。
        // 这之后是 flags 3 字节 + PTS 5 字节 + payload，所以是 payload + 8。
        //
        // 这里曾经写成 payload + 8 + 5，多算了 5 字节，导致每个音频 PES 都
        // 声明得比实际长。ffmpeg 解析时报 "PES packet size mismatch"、
        // "Packet corrupt (stream = 1)"，接收端表现为有画面但没声音。
        //
        // 视频允许填 0（表示长度不定，由 TS 包边界决定），音频必须给真实值。
        int pesLen = payload.length + 8;
        if (video) {
            pes.write(0x00); pes.write(0x00);
        } else {
            pes.write((pesLen >> 8) & 0xFF);
            pes.write(pesLen & 0xFF);
        }

        pes.write(0x80);                  // '10' + PES_scrambling=0 + PES_priority=0
        pes.write(0x80);                  // PTS_DTS_flags = '10'（只带 PTS）
        pes.write(0x05);                  // PES_header_data_length = 5
        writePts(pes, pts, 0x2);          // '0010' = 只有 PTS

        pes.write(payload, 0, payload.length);
        byte[] pesBytes = pes.toByteArray();

        writePesPayload(pid, pesBytes, video ? pts : -1, keyFrame);
    }

    private void writePesPayload(int pid, byte[] data, long pcrBase, boolean keyFrame) {
        int offset = 0;
        boolean first = true;
        while (offset < data.length) {
            boolean withPcr = first && pcrBase >= 0;
            // adaptation_field_length + flags + 6-byte PCR require 8 bytes.
            int capacity = PAYLOAD_CAPACITY - (withPcr ? 8 : 0);
            int n = Math.min(capacity, data.length - offset);

            byte[] packet = new byte[TS_PACKET_SIZE];
            packet[0] = (byte) SYNC_BYTE;
            packet[1] = (byte) ((first ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
            packet[2] = (byte) (pid & 0xFF);

            if (withPcr || n < PAYLOAD_CAPACITY) {
                // 用 adaptation field 补齐，避免浪费
                int stuffing = PAYLOAD_CAPACITY - n - 1;
                packet[3] = (byte) (0x30 | (continuity[pid] & 0x0F)); // AF + payload
                packet[4] = (byte) stuffing;
                if (stuffing > 0) {
                    packet[5] = (byte) (withPcr ? (0x10 | (keyFrame ? 0x40 : 0)) : 0);
                    for (int i = 6; i < TS_HEADER_SIZE + 1 + stuffing; i++) {
                        packet[i] = (byte) 0xFF;
                    }
                    if (withPcr) {
                        writePcr(packet);
                    }
                }
                System.arraycopy(data, offset, packet,
                        TS_HEADER_SIZE + 1 + stuffing, n);
            } else {
                packet[3] = (byte) (0x10 | (continuity[pid] & 0x0F)); // 只有 payload
                System.arraycopy(data, offset, packet, TS_HEADER_SIZE, n);
            }

            continuity[pid] = (continuity[pid] + 1) & 0x0F;
            out.write(packet, 0, TS_PACKET_SIZE);

            offset += n;
            first = false;
        }
    }

    /** 写 5 字节 PTS 字段 */
    private void writePts(ByteArrayOutputStream os, long pts, int prefix) {
        os.write((prefix << 4) | (int) (((pts >> 30) & 0x07) << 1) | 1);
        os.write((int) ((pts >> 22) & 0xFF));
        os.write((int) (((pts >> 15) & 0x7F) << 1) | 1);
        os.write((int) ((pts >> 7) & 0xFF));
        os.write((int) ((pts & 0x7F) << 1) | 1);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static byte[] readBuffer(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (info.size <= 0) {
            return null;
        }
        ByteBuffer dup = buffer.duplicate();
        dup.position(info.offset);
        dup.limit(info.offset + info.size);
        byte[] data = new byte[info.size];
        dup.get(data);
        return data;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** MPEG-2 CRC32（多项式 0x04C11DB7，不反射） */
    private static int crc32Mpeg(byte[] data) {
        int crc = 0xFFFFFFFF;
        for (byte b : data) {
            crc ^= ((int) b & 0xFF) << 24;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80000000) != 0) {
                    crc = (crc << 1) ^ 0x04C11DB7;
                } else {
                    crc <<= 1;
                }
            }
        }
        return crc;
    }
}
