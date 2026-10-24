"""Decode independently fetched HLS segments produced by the actual TS muxer.

Requires JDK 17 and FFmpeg with libx264/AAC. No emulator is needed.
"""
from pathlib import Path
import os
import re
import shutil
import struct
import subprocess
import tempfile

from analyze_dlna_ts import analyze

ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
import com.nightmare.sunshine.dlna.DlnaHttpServer;
import com.nightmare.sunshine.dlna.DlnaTsMuxer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class HlsMuxerRegression {
    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        DlnaTsMuxer muxer = new DlnaTsMuxer(25, true, 48000);
        muxer.setVideoCodecConfig(Files.readAllBytes(directory.resolve("config.h264")));
        DlnaHttpServer server = new DlnaHttpServer("video/mp2t");
        server.start(0);
        String base = "http://127.0.0.1:" + server.getPort() + "/";
        Set<String> published = new LinkedHashSet<>();
        java.lang.reflect.Field epoch = DlnaTsMuxer.class.getDeclaredField("epochUs");
        epoch.setAccessible(true);
        try (DataInputStream video = new DataInputStream(Files.newInputStream(directory.resolve("video.bin")));
             DataInputStream audio = new DataInputStream(Files.newInputStream(directory.resolve("audio.bin")))) {
            List<byte[]> audioFrames = new ArrayList<>();
            while (audio.available() > 0) audioFrames.add(audio.readNBytes(audio.readInt()));
            int audioIndex = 0;
            int videoIndex = 0;
            while (video.available() > 0) {
                boolean keyframe = video.readBoolean();
                byte[] frame = video.readNBytes(video.readInt());
                long mediaTimeUs = videoIndex * 40000L;
                epoch.setLong(muxer, System.nanoTime() / 1000L - mediaTimeUs);
                server.write(muxer.pushVideoRaw(frame, keyframe), keyframe);
                while (audioIndex < audioFrames.size() && audioIndex * 1024000000L / 48000 <= mediaTimeUs) {
                    long capture = epoch.getLong(muxer) + audioIndex * 1024000000L / 48000;
                    server.write(muxer.pushAudioRaw(audioFrames.get(audioIndex++), false, capture));
                }
                videoIndex++;
                // Refresh like a receiver: the advertised live window is
                // bounded, so distinct segments accumulate across polls.
                if (videoIndex % 25 == 0) collectSegments(base, published);
            }
            collectSegments(base, published);
            StringBuilder playlistBuilder = new StringBuilder("#EXTM3U\n");
            for (String name : published) playlistBuilder.append(name).append('\n');
            String playlist = playlistBuilder.toString();
            Files.writeString(directory.resolve("playlist.m3u8"), playlist);
            int count = 0;
            for (String line : playlist.split("\n")) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                try (InputStream input = new URL(base + line).openStream()) {
                    byte[] bytes = input.readAllBytes();
                    if (bytes.length < 564 || bytes[0] != 0x47 || bytes[188] != 0x47
                            || (bytes[1] & 31) != 0 || bytes[2] != 0
                            || (bytes[189] & 31) != 16 || bytes[190] != 0) {
                        throw new AssertionError("segment does not start with real PAT and PMT");
                    }
                    Files.write(directory.resolve(line), bytes);
                    count++;
                }
            }
            if (count < 6) throw new AssertionError("too few independently decodable segments: " + count);
            System.out.println("PASS actual muxer interleaves IDR/P-frame/AAC bytes across " + count + " HTTP segments");
        } finally { server.stop(); }
    }

    static void collectSegments(String base, Set<String> seen) throws Exception {
        // A real receiver tolerates "no usable live window yet" responses and
        // retries, so collection simply skips them.
        try {
            java.net.HttpURLConnection connection =
                    (java.net.HttpURLConnection)new URL(base + "live.m3u8").openConnection();
            int code = connection.getResponseCode();
            if (code != 200) { return; }
            for (String line : new String(connection.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (!line.isEmpty() && !line.startsWith("#")) seen.add(line.trim());
            }
        } catch (IOException retryable) {
            // retry on the next poll
        }
    }
}
'''


def nal_units(data):
    starts = list(re.finditer(b"\x00\x00\x00\x01|\x00\x00\x01", data))
    for index, match in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(data)
        payload = data[match.end():end]
        if payload:
            yield payload[0] & 31, b"\x00\x00\x00\x01" + payload


ffmpeg = shutil.which("ffmpeg")
if not ffmpeg:
    candidate = Path(r"C:\apps\ffmpeg-4.3-win64-static\bin\ffmpeg.exe")
    if candidate.exists():
        ffmpeg = str(candidate)
if not ffmpeg:
    raise SystemExit("FFmpeg with libx264 is required for the real segment decode test")

with tempfile.TemporaryDirectory(prefix="dlna-hls-muxer-") as directory:
    temporary = Path(directory)
    video = temporary / "source.h264"
    audio = temporary / "source.aac"
    subprocess.run([ffmpeg, "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25",
                    "-t", "5", "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                    "-profile:v", "baseline", "-x264-params", "keyint=13:min-keyint=13:scenecut=0:bframes=0:aud=1",
                    "-threads", "1", "-f", "h264", str(video)], check=True)
    subprocess.run([ffmpeg, "-v", "error", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
                    "-t", "5", "-c:a", "aac", "-b:a", "128k", "-f", "adts", str(audio)], check=True)
    frames = []
    config = {}
    current = bytearray()
    keyframe = False
    for kind, nal in nal_units(video.read_bytes()):
        if kind == 9:
            if current:
                frames.append((keyframe, bytes(current)))
                current.clear()
                keyframe = False
        elif kind in (7, 8):
            config[kind] = nal
        else:
            current.extend(nal)
            keyframe = keyframe or kind == 5
    if current:
        frames.append((keyframe, bytes(current)))
    (temporary / "config.h264").write_bytes(config[7] + config[8])
    with (temporary / "video.bin").open("wb") as output:
        for keyframe, frame in frames:
            output.write(struct.pack(">?I", keyframe, len(frame)) + frame)
    encoded_audio = audio.read_bytes()
    offset = 0
    with (temporary / "audio.bin").open("wb") as output:
        while offset < len(encoded_audio):
            header = encoded_audio[offset:offset + 7]
            if header[:2] != b"\xff\xf1":
                raise AssertionError("unexpected ADTS header")
            length = ((header[3] & 3) << 11) | (header[4] << 3) | (header[5] >> 5)
            frame = encoded_audio[offset + 7:offset + length]
            output.write(struct.pack(">I", len(frame)) + frame)
            offset += length
    sources = []
    for name, body in {
        "android/util/Log.java": "package android.util; public class Log { public static int i(String tag,String text){return 0;} public static int w(String tag,String text){return 0;} }",
        "android/media/MediaFormat.java": "package android.media; public class MediaFormat {}",
        "android/media/MediaCodec.java": "package android.media; public class MediaCodec { public static final int BUFFER_FLAG_KEY_FRAME=1; public static class BufferInfo { public int offset,size,flags; public long presentationTimeUs; } }",
        "HlsMuxerRegression.java": JAVA,
    }.items():
        destination = temporary / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(body, encoding="utf-8")
        sources.append(str(destination))
    package = ROOT / "android/app/src/main/java/com/nightmare/sunshine/dlna"
    sources.extend(str(package / name) for name in ["DlnaHttpServer.java", "DlnaHlsStore.java", "DlnaTsMuxer.java"])
    jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin"
    subprocess.run([str(jdk / "javac"), "-encoding", "UTF-8", "-d", str(temporary), *sources], check=True)
    subprocess.run([str(jdk / "java"), "-cp", str(temporary), "HlsMuxerRegression", str(temporary)], check=True)
    segments = [temporary / line for line in (temporary / "playlist.m3u8").read_text().splitlines()
                if line and not line.startswith("#")]
    combined = b"".join(segment.read_bytes() for segment in segments)
    result = analyze(combined)
    if result["continuity_errors"]:
        raise AssertionError(result["continuity_errors"])
    if not {0, 4096, 256, 257}.issubset(result["packets_by_pid"]):
        raise AssertionError("missing program/video/audio PID")
    for segment in segments:
        decoded = subprocess.run([ffmpeg, "-v", "error", "-i", str(segment), "-f", "null", "-"],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        if decoded.stderr:
            raise AssertionError(decoded.stderr.decode("utf-8", "replace"))
    print("PASS each HLS segment independently decodes real H264/AAC without errors; cross-segment TS continuity holds")
