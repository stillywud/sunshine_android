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

    private static final int TS_HEADER_SIZE = 4;
    private static final int PAYLOAD_CAPACITY = TS_PACKET_SIZE - TS_HEADER_SIZE;

    /** PTS 时钟频率 90kHz（MPEG-TS 规范） */
    private static final long PTS_CLOCK = 90_000L;

    /** 首帧 PTS 的起始偏移（微秒）。部分播放器不接受 0。 */
    private static final long PTS_START_US = 1_000_000L;

    private final int fps;
    private final boolean hasAudio;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);

    private final int[] continuity = new int[0x2000];

    private byte[] codecConfig;   // H.264 的 SPS/PPS（AVCDecoderConfigurationRecord）
    private byte[] audioConfig;   // AAC 的 AudioSpecificConfig

    private long videoPtsUs;
    private long audioPtsUs;
    /** 已产出的视频帧数，用来生成严格单调递增的 PTS */
    private long videoFrameIndex;
    private boolean patPmtWritten;

    public DlnaTsMuxer(int fps, boolean hasAudio) {
        this.fps = Math.max(1, fps);
        this.hasAudio = hasAudio;
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
        // PTS 从 1 秒起算（部分播放器不接受 0 作为首帧 PTS），
        // 之后按固定帧间隔严格递增。
        videoPtsUs = PTS_START_US + videoFrameIndex * (1_000_000L / fps);
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

        writePes(PID_VIDEO, payload, videoPtsUs, true);
        return take();
    }

    /**
     * 追加一帧音频。没有音频轨时不会被调用。
     */
    public synchronized byte[] pushAudio(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        byte[] data = readBuffer(buffer, info);
        if (data == null || data.length == 0) {
            return null;
        }

        boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        if (keyFrame) {
            audioConfig = data;
            return null;
        }

        audioPtsUs = info.presentationTimeUs > 0 ? info.presentationTimeUs
                : audioPtsUs + 23_220L; // 约 1024 samples @44.1kHz

        writePatPmtIfNeeded();
        writePes(PID_AUDIO, data, audioPtsUs, false);
        return take();
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
        Log.i(TAG, "PAT/PMT 已写入, audio=" + hasAudio);
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
        if (hasAudio) {
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

    private void writePes(int pid, byte[] payload, long ptsUs, boolean video) {
        long pts = (ptsUs * PTS_CLOCK) / 1_000_000L;

        ByteArrayOutputStream pes = new ByteArrayOutputStream(payload.length + 32);
        pes.write(0x00); pes.write(0x00); pes.write(0x01);
        pes.write(video ? 0xE0 : 0xC0);   // stream_id

        // PES_packet_length：视频允许 0（表示不定长），音频要真实长度
        int pesLen = payload.length + 8 + 5; // flags(3) + PTS(5) + payload
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

        writePesPayload(pid, pesBytes);
    }

    private void writePesPayload(int pid, byte[] data) {
        int offset = 0;
        boolean first = true;
        while (offset < data.length) {
            boolean last = (data.length - offset) <= PAYLOAD_CAPACITY;
            int n = Math.min(PAYLOAD_CAPACITY, data.length - offset);

            byte[] packet = new byte[TS_PACKET_SIZE];
            packet[0] = (byte) SYNC_BYTE;
            packet[1] = (byte) ((first ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
            packet[2] = (byte) (pid & 0xFF);

            if (last && n < PAYLOAD_CAPACITY) {
                // 用 adaptation field 补齐，避免浪费
                int stuffing = PAYLOAD_CAPACITY - n - 1;
                packet[3] = (byte) (0x30 | (continuity[pid] & 0x0F)); // AF + payload
                packet[4] = (byte) stuffing;
                if (stuffing > 0) {
                    packet[5] = 0x00; // flags
                    for (int i = 6; i < TS_HEADER_SIZE + 1 + stuffing; i++) {
                        packet[i] = (byte) 0xFF;
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
