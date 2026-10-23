"""Validate live TS clock and frame boundaries using the real Java muxer."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
import com.nightmare.sunshine.dlna.DlnaTsMuxer;
import java.io.*;
import java.util.*;
public class MuxRegression {
    static long previousPcr = -1;
    static void check(byte[] ts, byte[] raw, boolean key) throws Exception {
        ByteArrayOutputStream pes = new ByteArrayOutputStream();
        boolean foundPcr = false;
        for (int i = 0; i < ts.length; i += 188) {
            if ((ts[i] & 255) != 0x47) throw new AssertionError("TS sync");
            int pid = ((ts[i+1] & 31) << 8) | (ts[i+2] & 255);
            if (pid != 256) continue;
            int af = (ts[i+3] >> 4) & 3;
            int start = i + 4;
            if ((af & 2) != 0) {
                int length = ts[i+4] & 255;
                if (length > 0 && (ts[i+5] & 0x10) != 0) {
                    long base = ((ts[i+6]&255L)<<25) | ((ts[i+7]&255L)<<17)
                        | ((ts[i+8]&255L)<<9) | ((ts[i+9]&255L)<<1) | ((ts[i+10]&255L)>>7);
                    if (base <= previousPcr) throw new AssertionError("non-monotonic PCR");
                    previousPcr = base; foundPcr = true;
                    if (key && (ts[i+5]&0x40) == 0) throw new AssertionError("IDR missing random access flag");
                }
                start += 1 + length;
            }
            if ((af & 1) != 0) pes.write(ts, start, i + 188 - start);
        }
        if (!foundPcr) throw new AssertionError("PMT declares video PCR PID but no PCR is present");
        byte[] p = pes.toByteArray();
        int payload = 9 + (p[8] & 255);
        byte[] aud = {0,0,0,1,9,(byte)0xf0};
        if (!Arrays.equals(aud, Arrays.copyOfRange(p, payload, payload+6)))
            throw new AssertionError("missing H264 access unit delimiter");
        if (!Arrays.equals(raw, Arrays.copyOfRange(p, payload+6, p.length)))
            throw new AssertionError("PES payload changed at packet boundary");
    }
    public static void main(String[] args) throws Exception {
        DlnaTsMuxer mux = new DlnaTsMuxer(25, false);
        for (int length : new int[]{4, 160, 161, 162, 170, 171, 172, 173, 174, 175, 176, 180, 184, 350, 65536}) {
            byte[] raw = new byte[length]; Arrays.fill(raw, (byte)0x65);
            raw[0]=0;raw[1]=0;raw[2]=1;
            check(mux.pushVideoRaw(raw, true),raw,true);
            Thread.sleep(2);
        }
        Thread.sleep(85);
        java.lang.reflect.Method heartbeat;
        try { heartbeat = DlnaTsMuxer.class.getMethod("pollClock"); }
        catch (NoSuchMethodException e) { throw new AssertionError("idle video needs a PCR heartbeat"); }
        byte[] clock = (byte[])heartbeat.invoke(mux);
        if (clock == null || clock.length != 188 || (clock[3] & 0x30) != 0x20
                || (clock[5] & 0x10) == 0) throw new AssertionError("PCR heartbeat must be adaptation-only");
        if (heartbeat.invoke(mux) != null) throw new AssertionError("PCR heartbeat not rate limited");
        System.out.println("PASS PCR clock, random access, AUD, 15 PES boundary sizes");
    }
}
'''
with tempfile.TemporaryDirectory(prefix="dlna-mux-test-") as directory:
    tmp = Path(directory)
    sources = []
    for name, body in {
        "android/util/Log.java": "package android.util; public class Log { public static int i(String t,String m){return 0;} }",
        "android/media/MediaFormat.java": "package android.media; public class MediaFormat {}",
        "android/media/MediaCodec.java": "package android.media; public class MediaCodec { public static final int BUFFER_FLAG_KEY_FRAME=1; public static class BufferInfo { public int offset,size,flags; public long presentationTimeUs; } }",
        "MuxRegression.java": JAVA,
    }.items():
        path = tmp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        sources.append(str(path))
    jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin"
    subprocess.run([str(jdk / "javac"), "-encoding", "UTF-8", "-d", str(tmp), *sources, str(ROOT / "android/app/src/main/java/com/nightmare/sunshine/dlna/DlnaTsMuxer.java")], check=True)
    subprocess.run([str(jdk / "java"), "-cp", str(tmp), "MuxRegression"], check=True)
