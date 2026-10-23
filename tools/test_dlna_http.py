"""Run the real DLNA HTTP server against loopback sockets with only Log stubbed.

Requires JDK 17; no emulator, Gradle or Android runtime needed.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
import com.nightmare.sunshine.dlna.DlnaHttpServer;
import com.nightmare.sunshine.dlna.DlnaUpnp;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class HttpRegression {
    static byte[] packets(int count, int seed) {
        byte[] b = new byte[count * 188];
        for (int i = 0; i < b.length; i++) b[i] = (byte)(seed + i / 188);
        return b;
    }
    static Socket request(DlnaHttpServer server, String extra) throws Exception {
        Socket s = new Socket("127.0.0.1", server.getPort());
        s.setSoTimeout(1200);
        s.getOutputStream().write(("GET /live.ts HTTP/1.1\r\nHost: localhost\r\n" + extra + "\r\n").getBytes(StandardCharsets.US_ASCII));
        return s;
    }
    static String headers(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int state = 0;
        while (state != 4) {
            int c = in.read();
            if (c < 0) throw new AssertionError("EOF in headers");
            out.write(c);
            state = c == (state % 2 == 0 ? '\r' : '\n') ? state + 1 : 0;
        }
        return out.toString("US-ASCII");
    }
    static void equal(byte[] want, InputStream in) throws Exception {
        byte[] got = in.readNBytes(want.length);
        if (!Arrays.equals(want, got)) throw new AssertionError("stream bytes differ: " + got.length + "/" + want.length);
    }
    static void test(String name, Checked action) throws Exception {
        action.run();
        System.out.println("PASS " + name);
    }
    interface Checked { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        int failed = 0;
        Map<String, Checked> cases = new LinkedHashMap<>();
        cases.put("small initial frame is sent without waiting for 512KB", () -> {
            DlnaHttpServer server = new DlnaHttpServer("video/mp2t");
            server.start(0);
            try {
                byte[] b = packets(4, 1);
                server.markKeyframe(); server.write(b);
                try (Socket s = request(server, "")) {
                    headers(s.getInputStream()); equal(b, s.getInputStream());
                    // No further production: the very last packet must still arrive.
                    byte[] tail = packets(1, 55); server.write(tail);
                    equal(tail, s.getInputStream());
                }
            } finally { server.stop(); }
        });
        cases.put("live responses declare a sender-paced sliding stream, not a seekable file", () -> {
            DlnaHttpServer server = new DlnaHttpServer("video/mp2t"); server.start(0);
            try (Socket s = request(server, "getcontentFeatures.dlna.org: 1\r\n")) {
                String h = headers(s.getInputStream());
                if (!h.contains("transferMode.dlna.org: Streaming\r\n"))
                    throw new AssertionError("missing DLNA streaming transfer mode");
                if (!h.contains("contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=8D100000000000000000000000000000\r\n"))
                    throw new AssertionError("missing sender-paced, sliding-start, growing-end flags");
                if (!h.contains("Accept-Ranges: none\r\n") || h.contains("Content-Length:"))
                    throw new AssertionError("live response must not advertise a finite seekable file");
            } finally { server.stop(); }
        });
        cases.put("UPnP metadata matches the HTTP live-stream capabilities", () -> {
            java.lang.reflect.Method method = DlnaUpnp.class.getDeclaredMethod("buildMetadata", String.class, String.class);
            method.setAccessible(true);
            String metadata = (String)method.invoke(null, "http://127.0.0.1/live.ts?a=1&b=2", "video/mp2t");
            if (!metadata.contains("object.item.videoItem.videoBroadcast"))
                throw new AssertionError("live screen is advertised as an ordinary file");
            if (!metadata.contains("DLNA.ORG_FLAGS=8D100000000000000000000000000000"))
                throw new AssertionError("metadata lacks live source flags");
            if (!metadata.contains("a=1&amp;b=2") || metadata.contains("duration="))
                throw new AssertionError("bad live metadata XML or finite duration");
        });
        cases.put("standard SSDP LOCATION header is parsed without a subnet port scan", () -> {
            java.lang.reflect.Field field = DlnaUpnp.class.getDeclaredField("LOCATION");
            field.setAccessible(true);
            java.util.regex.Pattern pattern = (java.util.regex.Pattern)field.get(null);
            java.util.regex.Matcher matcher = pattern.matcher("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLoCaTiOn: http://192.168.1.2:49152/device.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n");
            if (!matcher.find() || !matcher.group(1).trim().equals("http://192.168.1.2:49152/device.xml"))
                throw new AssertionError("SSDP uses HTTP headers, not <LOCATION> XML tags");
        });
        cases.put("new live client receives a short IDR preroll before live edge", () -> {
            DlnaHttpServer server = new DlnaHttpServer("video/mp2t"); server.start(0);
            try {
                byte[][] frames = new byte[4][];
                for (int i = 0; i < frames.length; i++) {
                    frames[i] = packets(3, 70 + i);
                    server.markKeyframe(); server.write(frames[i]);
                }
                try (Socket s = request(server, "")) {
                    headers(s.getInputStream()); equal(frames[1], s.getInputStream());
                }
            } finally { server.stop(); }
        });
        cases.put("retained byte range preserves exact offset and bounded length", () -> {
            DlnaHttpServer server = new DlnaHttpServer("video/mp2t"); server.start(0);
            try {
                byte[] b = packets(4000, 3); server.markKeyframe(); server.write(b);
                try (Socket s = request(server, "Range: bytes=189-752\r\n")) {
                    String h = headers(s.getInputStream());
                    if (!h.startsWith("HTTP/1.1 206") || !h.contains("Content-Range: bytes 189-752/"))
                        throw new AssertionError("incorrect range: " + h);
                    equal(Arrays.copyOfRange(b, 189, 753), s.getInputStream());
                    if (s.getInputStream().read() != -1) throw new AssertionError("body exceeds range");
                }
            } finally { server.stop(); }
        });
        cases.put("open-ended reconnect is a fresh live stream, not a global byte seek", () -> {
            DlnaHttpServer server = new DlnaHttpServer("video/mp2t"); server.start(0);
            try {
                byte[] first = packets(50, 1); server.write(first, true);
                byte[] latest = packets(3, 88); server.write(latest, true);
                try (Socket s = request(server, "Range: bytes=188-\r\n")) {
                    String h = headers(s.getInputStream());
                    if (!h.startsWith("HTTP/1.1 200")) throw new AssertionError("live offset is not global: " + h);
                    equal(first, s.getInputStream());
                }
            } finally { server.stop(); }
        });
        cases.put("stop unblocks live client promptly", () -> {
            DlnaHttpServer server = new DlnaHttpServer("video/mp2t"); server.start(0);
            try (Socket s = request(server, "")) {
                headers(s.getInputStream()); server.stop();
                if (s.getInputStream().read() != -1) throw new AssertionError("expected EOF");
            } finally { server.stop(); }
        });
        cases.put("HEAD returns headers and closes without a streaming body", () -> {
            DlnaHttpServer server = new DlnaHttpServer("video/mp2t"); server.start(0);
            try (Socket s = new Socket("127.0.0.1", server.getPort())) {
                s.setSoTimeout(1200);
                s.getOutputStream().write("HEAD /live.ts HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                headers(s.getInputStream());
                if (s.getInputStream().read() != -1) throw new AssertionError("HEAD must not stream a body");
            } finally { server.stop(); }
        });
        cases.put("ring rollover evicts overwritten IDRs and rejoins latest complete frame", () -> {
            DlnaHttpServer server = new DlnaHttpServer("video/mp2t"); server.start(0);
            try {
                server.write(packets(50000, 1), true);
                byte[] latest = packets(5, 99); server.write(latest, true);
                try (Socket s = request(server, "Range: bytes=188-\r\n")) {
                    headers(s.getInputStream()); equal(latest, s.getInputStream());
                }
            } finally { server.stop(); }
        });
        for (Map.Entry<String, Checked> c : cases.entrySet()) {
            try { test(c.getKey(), c.getValue()); }
            catch (Throwable e) { failed++; System.out.println("FAIL " + c.getKey() + ": " + e); }
        }
        if (failed > 0) throw new AssertionError(failed + " regression(s)");
    }
}
'''

with tempfile.TemporaryDirectory(prefix="dlna-http-test-") as directory:
    tmp = Path(directory)
    stub = tmp / "android/util/Log.java"
    stub.parent.mkdir(parents=True)
    stub.write_text("package android.util; public class Log { public static int i(String t,String m){return 0;} public static int w(String t,String m){return 0;} }", encoding="utf-8")
    harness = tmp / "HttpRegression.java"
    harness.write_text(JAVA, encoding="utf-8")
    jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin"
    subprocess.run([str(jdk / "javac"), "-encoding", "UTF-8", "-d", str(tmp), str(stub), str(harness), *[str(ROOT / "android/app/src/main/java/com/nightmare/sunshine/dlna" / name) for name in ("DlnaHttpServer.java", "DlnaUpnp.java")]], check=True)
    subprocess.run([str(jdk / "java"), "-cp", str(tmp), "HttpRegression"], check=True)
