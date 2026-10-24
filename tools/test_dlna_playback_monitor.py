"""JDK 17 drift regressions: real loopback SOAP, deterministic time, only Log stubbed."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
import com.nightmare.sunshine.dlna.DlnaUpnp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

public class PlaybackMonitorRegression {
    static final String SERVICE = "urn:schemas-upnp-org:service:AVTransport:1";
    static final String SOAP = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String EXPECTED = "http://sender.invalid/live.m3u8?session=one&anchor=two";
    static final String MONITOR = "com.nightmare.sunshine.dlna.DlnaPlaybackMonitor";
    interface Checked { void run() throws Exception; }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static Class<?> requiredClass(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException missing) {
            throw new AssertionError("missing playback drift recovery: " + name, missing);
        }
    }

    static Object query(DlnaUpnp.Renderer renderer) throws Exception {
        try {
            return DlnaUpnp.class.getMethod("getPlaybackSnapshot", DlnaUpnp.Renderer.class)
                    .invoke(null, renderer);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("missing bounded standard UPnP playback snapshot API", missing);
        }
    }

    static Object field(Object snapshot, String name) throws Exception {
        check(snapshot != null, "missing playback snapshot");
        return snapshot.getClass().getField(name).get(snapshot);
    }

    static class Tracker {
        final Object target;
        final Method observe;
        Tracker() throws Exception {
            Class<?> type = requiredClass(MONITOR + "$DriftTracker");
            Constructor<?> constructor = type.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            target = constructor.newInstance(EXPECTED);
            observe = type.getDeclaredMethod("observe", long.class, String.class, long.class, String.class);
            observe.setAccessible(true);
        }
        boolean sample(long wall, String state, long position, String uri) throws Exception {
            return (Boolean) observe.invoke(target, wall, state, position, uri);
        }
        boolean playing(long wall, long position) throws Exception {
            return sample(wall, "PLAYING", position, EXPECTED);
        }
        void warm(long wall, long position) throws Exception {
            for (int index = 0; index <= 5; index++) {
                check(!playing(wall + index * 2000, position + index * 2000), "startup recovered");
            }
        }
    }

    static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String envelope(String action, String fields) {
        return "<s:Envelope xmlns:s=\"" + SOAP + "\"><s:Body><u:" + action
                + "Response xmlns:u=\"" + SERVICE + "\">" + fields + "</u:" + action
                + "Response></s:Body></s:Envelope>";
    }

    static String transport(String state, String status, String speed) {
        return envelope("GetTransportInfo", "<CurrentTransportState>" + state
                + "</CurrentTransportState><CurrentTransportStatus>" + status
                + "</CurrentTransportStatus><CurrentSpeed>" + speed + "</CurrentSpeed>");
    }

    static String position(String time, String uri) {
        return envelope("GetPositionInfo", "<Track>1</Track><TrackDuration>00:00:00</TrackDuration>"
                + "<TrackMetaData></TrackMetaData><TrackURI>" + xml(uri) + "</TrackURI><RelTime>"
                + time + "</RelTime><AbsTime>NOT_IMPLEMENTED</AbsTime><RelCount>2147483647</RelCount>"
                + "<AbsCount>2147483647</AbsCount>");
    }

    static String defaultReply(String action) {
        if (action.equals("GetTransportInfo")) return transport("PLAYING", "OK", "1");
        if (action.equals("GetPositionInfo")) return position("00:06:08", "");
        return envelope("GetMediaInfo", "<NrTracks>1</NrTracks><MediaDuration>00:00:00</MediaDuration>"
                + "<CurrentURI>" + xml(EXPECTED) + "</CurrentURI><CurrentURIMetaData/>"
                + "<NextURI/><NextURIMetaData/><PlayMedium>NETWORK</PlayMedium>"
                + "<RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_IMPLEMENTED</WriteStatus>");
    }

    static class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "loopback-soap");
            thread.setDaemon(true);
            return thread;
        });
        final List<String> actions = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile Function<String, String> reply = PlaybackMonitorRegression::defaultReply;
        volatile String errorAction = "";
        volatile int errorCode = 500;
        volatile long delayMs;
        volatile boolean chunked;
        volatile boolean trickle;
        volatile CountDownLatch gate;

        Fixture() throws IOException {
            server.createContext("/control", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        DlnaUpnp.Renderer renderer() {
            DlnaUpnp.Renderer renderer = new DlnaUpnp.Renderer();
            renderer.avTransportControlUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/control";
            return renderer;
        }

        void handle(HttpExchange exchange) throws IOException {
            try {
                check(exchange.getRequestMethod().equals("POST"), "SOAP must POST");
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                check(contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/xml")
                        && contentType.toLowerCase(Locale.ROOT).contains("utf-8"), "SOAP content type");
                String header = exchange.getRequestHeaders().getFirst("SOAPACTION");
                check(header != null && header.startsWith("\"" + SERVICE + "#") && header.endsWith("\""),
                        "quoted standard SOAPACTION");
                String action = header.substring(SERVICE.length() + 2, header.length() - 1);
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                Element document = factory.newDocumentBuilder().parse(
                        new ByteArrayInputStream(exchange.getRequestBody().readAllBytes())).getDocumentElement();
                check(SOAP.equals(document.getNamespaceURI()), "SOAP envelope namespace");
                Element body = (Element) document.getElementsByTagNameNS(SOAP, "Body").item(0);
                Element method = (Element) body.getFirstChild();
                check(action.equals(method.getLocalName()) && SERVICE.equals(method.getNamespaceURI()),
                        "action schema matches header");
                check(method.getElementsByTagName("InstanceID").getLength() == 1
                        && method.getElementsByTagName("InstanceID").item(0).getTextContent().equals("0"),
                        "InstanceID must be zero");
                if (action.startsWith("Get")) {
                    check(method.getChildNodes().getLength() == 1, "query only takes InstanceID");
                    check(action.equals("GetTransportInfo") || action.equals("GetPositionInfo")
                            || action.equals("GetMediaInfo"),
                            "monitor must only use standard transport/position queries");
                }
                actions.add(action);
                entered.countDown();
                if (gate != null) gate.await();
                if (delayMs > 0) Thread.sleep(delayMs);
                byte[] bytes = reply.apply(action).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=utf-8");
                exchange.sendResponseHeaders(action.equals(errorAction) ? errorCode : 200,
                        chunked ? 0 : bytes.length == 0 ? -1 : bytes.length);
                if (trickle) {
                    for (int offset = 0; offset < bytes.length; offset += 16) {
                        exchange.getResponseBody().write(bytes, offset, Math.min(16, bytes.length - offset));
                        exchange.getResponseBody().flush();
                        Thread.sleep(100);
                    }
                } else {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException disconnected) {
            } catch (Exception | AssertionError invalid) {
                failure.set(invalid);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        }

        public void close() {
            server.stop(0);
            executor.shutdownNow();
            check(failure.get() == null, "invalid query request: " + failure.get());
        }
    }

    static void registerQueries(Map<String, Checked> cases) {
        cases.put("query-success-and-schema", () -> {
            try (Fixture fixture = new Fixture()) {
                fixture.reply = action -> action.equals("GetTransportInfo") ? defaultReply(action)
                        : position("00:06:08", EXPECTED);
                Object snapshot = query(fixture.renderer());
                check(field(snapshot, "transportState").equals("PLAYING"), "transport state");
                check(field(snapshot, "positionMs").equals(368000L), "RelTime in milliseconds");
                check(field(snapshot, "mediaUri").equals(EXPECTED), "XML-decoded TrackURI");
                check(fixture.actions.equals(Arrays.asList("GetTransportInfo", "GetPositionInfo", "GetTransportInfo")),
                        "transport revalidated after position, no private/control actions");
            }
        });
        cases.put("query-empty-uri-namespaces-and-fractions", () -> {
            try (Fixture fixture = new Fixture()) {
                check(field(query(fixture.renderer()), "mediaUri").equals(EXPECTED), "empty TrackURI uses CurrentURI");
                check(fixture.actions.equals(Arrays.asList("GetTransportInfo", "GetPositionInfo", "GetMediaInfo", "GetTransportInfo")),
                        "GetMediaInfo is the standard empty-TrackURI fallback");
                fixture.reply = action -> !action.equals("GetPositionInfo") ? defaultReply(action)
                        : "<env:Envelope xmlns:env=\"" + SOAP + "\"><env:Body>"
                        + "<GetPositionInfoResponse xmlns=\"" + SERVICE + "\">"
                        + "<RelTime>123:45:56.7899</RelTime><TrackURI><![CDATA[" + EXPECTED
                        + "]]></TrackURI></GetPositionInfoResponse></env:Body></env:Envelope>";
                Object snapshot = query(fixture.renderer());
                check(field(snapshot, "positionMs").equals(445556789L), "fractional and long hours");
                check(field(snapshot, "mediaUri").equals(EXPECTED), "CDATA URI");
                fixture.reply = action -> !action.equals("GetPositionInfo") ? defaultReply(action)
                        : envelope(action, "<u:RelTime>00:06:08</u:RelTime><u:TrackURI/>");
                check(field(query(fixture.renderer()), "positionMs").equals(368000L), "qualified fields");
            }
        });
        cases.put("query-rechecks-transport-after-position", () -> {
            try (Fixture fixture = new Fixture()) {
                for (String state : Arrays.asList("PAUSED_PLAYBACK", "STOPPED", "TRANSITIONING")) {
                    AtomicReference<String> currentState = new AtomicReference<>("PLAYING");
                    fixture.reply = action -> {
                        if (action.equals("GetPositionInfo")) {
                            currentState.set(state);
                            return position("00:06:08", "");
                        }
                        if (action.equals("GetTransportInfo")) return transport(currentState.get(), "OK", "1");
                        return defaultReply(action);
                    };
                    Object snapshot = query(fixture.renderer());
                    check(snapshot == null || state.equals(field(snapshot, "transportState")),
                            "stale PLAYING overrides state change during position query: " + state);
                }
                fixture.reply = action -> {
                    if (action.equals("GetPositionInfo")) fixture.errorAction = "GetTransportInfo";
                    return defaultReply(action);
                };
                check(query(fixture.renderer()) == null, "failed final transport confirmation must fail closed");
            }
        });
        cases.put("query-media-ownership-fallback-fails-closed", () -> {
            try (Fixture fixture = new Fixture()) {
                Object snapshot = query(fixture.renderer());
                check(fixture.actions.contains("GetMediaInfo"), "empty TrackURI requires ownership fallback");
                check(field(snapshot, "mediaUri").equals(EXPECTED), "fallback resolves ownership");
                for (String uri : Arrays.asList("", "NOT_IMPLEMENTED")) {
                    fixture.reply = action -> !action.equals("GetMediaInfo") ? defaultReply(action)
                            : envelope(action, "<CurrentURI>" + uri + "</CurrentURI>");
                    check(query(fixture.renderer()) == null, "both URIs unknown must fail closed");
                }
                fixture.reply = PlaybackMonitorRegression::defaultReply;
                fixture.errorAction = "GetMediaInfo";
                check(query(fixture.renderer()) == null, "unsupported ownership fallback must fail closed");
                fixture.errorAction = "";
                fixture.reply = action -> !action.equals("GetMediaInfo") ? defaultReply(action)
                        : envelope(action, "<CurrentURI>http://other.invalid/movie.m3u8</CurrentURI>");
                snapshot = query(fixture.renderer());
                check(field(snapshot, "mediaUri").equals("http://other.invalid/movie.m3u8"),
                        "foreign CurrentURI must not be replaced by expected URL");
            }
        });
        cases.put("query-nonplaying-and-status", () -> {
            try (Fixture fixture = new Fixture()) {
                for (String state : Arrays.asList("PAUSED_PLAYBACK", "STOPPED", "TRANSITIONING", "NO_MEDIA_PRESENT")) {
                    fixture.actions.clear();
                    fixture.reply = action -> transport(state, "OK", "1");
                    check(field(query(fixture.renderer()), "transportState").equals(state), "preserve " + state);
                    check(fixture.actions.equals(Arrays.asList("GetTransportInfo")), "no position query while " + state);
                }
                fixture.reply = action -> transport("PLAYING", "ERROR_OCCURRED", "1");
                check(query(fixture.renderer()) == null, "transport error is not playback evidence");
                fixture.reply = action -> transport("PLAYING", "OK", "2");
                check(query(fixture.renderer()) == null, "intentional non-normal speed is not drift");
            }
        });
        cases.put("query-unsupported-http-errors-and-faults", () -> {
            try (Fixture fixture = new Fixture()) {
                for (String action : Arrays.asList("GetTransportInfo", "GetPositionInfo", "GetMediaInfo")) {
                    fixture.errorAction = action;
                    for (int status : new int[] {404, 500, 501}) {
                        fixture.errorCode = status;
                        check(query(fixture.renderer()) == null, "HTTP " + status + " must fail closed");
                    }
                }
                fixture.errorAction = "";
                fixture.reply = action -> "<s:Envelope xmlns:s=\"" + SOAP + "\"><s:Body>"
                        + "<s:Fault><faultcode>s:Client</faultcode><detail><errorCode>401</errorCode>"
                        + "</detail></s:Fault></s:Body></s:Envelope>";
                check(query(fixture.renderer()) == null, "HTTP 200 SOAP fault must fail closed");
            }
        });
        cases.put("query-invalid-position-and-overflow", () -> {
            try (Fixture fixture = new Fixture()) {
                for (String time : Arrays.asList("NOT_IMPLEMENTED", "", "-00:00:01", "00:60:00", "00:00:60",
                        "00:00", "00:00:01junk", "00:00:01.", "00:00:01.NaN", "999999999999999999999:00:00",
                        "2562047788016:00:00", "00:00:01:02")) {
                    fixture.reply = action -> action.equals("GetTransportInfo") ? defaultReply(action) : position(time, "");
                    check(query(fixture.renderer()) == null, "reject invalid RelTime: " + time);
                }
            }
        });
        cases.put("query-invalid-xml-and-external-entities", () -> {
            try (Fixture fixture = new Fixture()) {
                String valid = position("00:06:08", "");
                List<String> invalid = Arrays.asList(
                        valid.replace(SOAP, "urn:foreign:soap"),
                        valid.replace(SERVICE, "urn:foreign:service"),
                        valid.replace("GetPositionInfoResponse", "GetMediaInfoResponse"),
                        valid.replace("<RelTime>", "<RelTime xmlns=\"urn:foreign:field\">"),
                        valid.replace("<RelTime>", "<RelTime>00:06:08</RelTime><RelTime>"),
                        valid.replace("<TrackURI></TrackURI>", ""),
                        valid.replace("</RelTime>", "</WrongTag>"),
                        "<!DOCTYPE s:Envelope [<!ENTITY external SYSTEM \""
                                + fixture.renderer().avTransportControlUrl + "\">]>"
                                + valid.replace("00:06:08", "&external;"));
                for (String body : invalid) {
                    fixture.reply = action -> action.equals("GetTransportInfo") ? defaultReply(action) : body;
                    check(query(fixture.renderer()) == null, "reject malformed/ambiguous XML");
                }
            }
        });
        cases.put("query-body-limit-fixed-and-chunked", () -> {
            try (Fixture fixture = new Fixture()) {
                fixture.reply = action -> defaultReply(action) + " ".repeat(65536);
                for (boolean chunked : new boolean[] {false, true}) {
                    fixture.chunked = chunked;
                    check(query(fixture.renderer()) == null, "64 KiB response bound");
                }
            }
        });
        cases.put("query-deadline-slow-and-trickling", () -> {
            try (Fixture fixture = new Fixture()) {
                fixture.delayMs = 3500;
                long started = System.nanoTime();
                check(query(fixture.renderer()) == null, "slow headers must time out");
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2400, "slow query exceeds bound");
            }
            try (Fixture fixture = new Fixture()) {
                fixture.trickle = true;
                long started = System.nanoTime();
                check(query(fixture.renderer()) == null, "trickling body must time out");
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2400, "trickle bypassed deadline");
            }
        });
        cases.put("query-foreign-media-is-preserved", () -> {
            try (Fixture fixture = new Fixture()) {
                fixture.reply = action -> action.equals("GetTransportInfo") ? defaultReply(action)
                        : position("00:06:08", "http://other.invalid/movie.m3u8");
                Object snapshot = query(fixture.renderer());
                Tracker tracker = new Tracker();
                tracker.warm(0, 368000);
                check(!tracker.sample(14000, (String) field(snapshot, "transportState"),
                        (Long) field(snapshot, "positionMs"), (String) field(snapshot, "mediaUri")),
                        "foreign media must not trigger recovery");
            }
        });
        cases.put("existing-control-success-semantics", () -> {
            try (Fixture fixture = new Fixture()) {
                fixture.errorAction = "Stop";
                fixture.reply = action -> "";
                check(DlnaUpnp.play(fixture.renderer(), EXPECTED, "application/vnd.apple.mpegurl"),
                        "play must still tolerate failed Stop and empty successful responses");
                check(fixture.actions.equals(Arrays.asList("Stop", "SetAVTransportURI", "Play")),
                        "existing control sequence is unchanged");
            }
        });
    }

    static void registerTracker(Map<String, Checked> cases) {
        cases.put("rebuffer-deficit-two-observations", () -> {
            Tracker tracker = new Tracker();
            tracker.warm(0, 100000);
            check(!tracker.playing(12000, 110000), "one deficit must not recover");
            check(tracker.playing(14000, 112000), "new delay persists after playback resumes");
            check(!tracker.playing(16000, 112000), "recovery must reset and warm up");
        });
        cases.put("steady-quantized-playback", () -> {
            for (long phase : new long[] {0, 250, 999}) {
                Tracker tracker = new Tracker();
                for (int index = 0; index < 100; index++) {
                    long wall = index * 2000L + index % 5 * 173L;
                    long position = 368000 + (wall + phase) / 1000 * 1000;
                    check(!tracker.playing(wall, position), "one-second quantization is not drift");
                }
            }
        });
        cases.put("startup-buffering-and-unproven-position", () -> {
            Tracker stuck = new Tracker();
            for (long wall = 0; wall <= 60000; wall += 2000) {
                check(!stuck.playing(wall, 0), "never-advancing position is not supported evidence");
            }
            Tracker startup = new Tracker();
            for (long wall = 0; wall <= 8000; wall += 2000) {
                check(!startup.playing(wall, 0), "startup buffering must not recover");
            }
            for (long wall = 10000; wall <= 60000; wall += 2000) {
                check(!startup.playing(wall, wall - 8000), "do not retain startup buffering as drift");
            }
        });
        cases.put("learn-best-offset-and-clear-isolated-deficit", () -> {
            Tracker tracker = new Tracker();
            tracker.warm(0, 100000);
            check(!tracker.playing(12000, 110000), "first candidate");
            check(!tracker.playing(14000, 114000), "catch-up clears candidate");
            check(!tracker.playing(16000, 114000), "must require consecutive candidates");
            check(!tracker.playing(18000, 120000), "learn improved best offset");
            check(!tracker.playing(20000, 120000), "new baseline first candidate");
            check(tracker.playing(22000, 122000), "new deficit relative to best, not startup offset");
        });
        cases.put("cooldown-and-recovery-rewarm", () -> {
            Tracker tracker = new Tracker();
            tracker.warm(0, 100000);
            check(!tracker.playing(12000, 110000), "first candidate");
            check(tracker.playing(14000, 110000), "initial recovery");
            tracker.warm(16000, 200000);
            for (long wall = 28000; wall <= 34000; wall += 2000) {
                check(!tracker.playing(wall, 210000), "20s cooldown plus two fresh candidates");
            }
            check(tracker.playing(36000, 210000), "new post-recovery drift can recover after cooldown");
        });
        cases.put("pause-stop-unsupported-and-clock-reset", () -> {
            for (String state : Arrays.asList("PAUSED_PLAYBACK", "STOPPED", "TRANSITIONING", null)) {
                Tracker tracker = new Tracker();
                tracker.warm(0, 100000);
                check(!tracker.playing(12000, 110000), "first candidate");
                check(!tracker.sample(14000, state, 110000, EXPECTED), "invalid/non-playing resets");
                tracker.warm(50000, 110000);
                check(!tracker.playing(62000, 122000), "resume must not include pause/query gap");
            }
            Tracker tracker = new Tracker();
            tracker.warm(0, 100000);
            check(!tracker.playing(8000, 110000), "backward clock resets");
            check(!tracker.playing(8000, 110000), "duplicate timestamp is not a second observation");
            check(!tracker.playing(10000, -1), "unsupported position resets");
        });
        cases.put("backward-seek-and-cooldown-survives-reset", () -> {
            Tracker tracker = new Tracker();
            tracker.warm(0, 100000);
            check(!tracker.playing(12000, 50000), "seek backwards starts a new baseline");
            tracker.warm(14000, 52000);
            check(!tracker.playing(26000, 62000), "seek first candidate");
            check(tracker.playing(28000, 62000), "post-seek new deficit");
            check(!tracker.sample(30000, "PAUSED_PLAYBACK", -1, ""), "pause after recovery");
            tracker.warm(32000, 100000);
            check(!tracker.playing(44000, 110000), "reset must not erase cooldown");
            check(!tracker.playing(46000, 110000), "cooldown remains at 18s");
        });
        cases.put("foreign-uri-latches-until-expected-content", () -> {
            Tracker tracker = new Tracker();
            tracker.warm(0, 100000);
            check(!tracker.playing(12000, 110000), "first candidate");
            check(!tracker.sample(14000, "PLAYING", 110000, EXPECTED + "-foreign"), "foreign URI cancels candidate");
            for (long wall = 16000; wall <= 40000; wall += 2000) {
                check(!tracker.sample(wall, "PLAYING", 110000, ""), "empty URI cannot disprove known foreign content");
            }
            check(!tracker.sample(42000, "PLAYING", 200000, EXPECTED), "expected content can return");
            for (long wall = 44000; wall <= 52000; wall += 2000) {
                check(!tracker.playing(wall, 200000 + wall - 42000), "fresh expected-content warm-up");
            }
            check(!tracker.playing(54000, 210000), "fresh first candidate");
            check(tracker.playing(56000, 210000), "known expected content can recover again");
        });
        cases.put("unknown-ownership-never-recovers", () -> {
            Tracker tracker = new Tracker();
            tracker.warm(0, 100000);
            check(!tracker.playing(12000, 110000), "first deficit");
            for (long wall = 14000; wall <= 50000; wall += 2000) {
                check(!tracker.sample(wall, "PLAYING", 110000, ""), "unknown ownership cannot recover");
            }
        });
        cases.put("recovery-query-variant-uri-still-matches-expected-stream", () -> {
            Tracker tracker = new Tracker();
            tracker.warm(0, 100000);
            // A varied-URL recovery re-push may be reported back by the
            // receiver with or without the extra recovery parameter; both are
            // the same stream and must not latch foreign media. Positions
            // stall two seconds behind the learned offset to raise a deficit.
            check(!tracker.sample(12000, "PLAYING", 110000, EXPECTED + "&r=1"),
                    "recovery variant must match the expected stream");
            check(tracker.sample(14000, "PLAYING", 110000, EXPECTED),
                    "recovery fires while the receiver reports a variant");
            Tracker stranger = new Tracker();
            stranger.warm(0, 100000);
            check(!stranger.sample(12000, "PLAYING", 110000, "http://sender.invalid/other/live.m3u8"),
                    "different path is foreign");
            check(!stranger.sample(14000, "PLAYING", 110000, "http://sender.invalid/other/live.m3u8"),
                    "foreign deficit sample one");
            check(!stranger.sample(16000, "PLAYING", 110000, "http://sender.invalid/other/live.m3u8"),
                    "foreign content never recovers");
        });
        cases.put("updateExpectedUrl-redirects-acceptance-to-recovered-stream", () -> {
            Class<?> type = requiredClass(MONITOR + "$DriftTracker");
            Constructor<?> constructor = type.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            Object target = constructor.newInstance(EXPECTED);
            Method observe = type.getDeclaredMethod("observe", long.class, String.class, long.class, String.class);
            observe.setAccessible(true);
            Method update = type.getDeclaredMethod("updateExpectedUrl", String.class);
            update.setAccessible(true);
            String varied = EXPECTED + "&r=3";
            update.invoke(target, varied);
            check(!(Boolean)observe.invoke(target, 0L, "PLAYING", 100000L, varied), "warm one");
            check(!(Boolean)observe.invoke(target, 2000L, "PLAYING", 102000L, EXPECTED), "warm two accepts prior URI");
            check(!(Boolean)observe.invoke(target, 4000L, "PLAYING", 104000L, varied), "warm three");
            check(!(Boolean)observe.invoke(target, 6000L, "PLAYING", 106000L, EXPECTED), "warm four");
            check(!(Boolean)observe.invoke(target, 8000L, "PLAYING", 108000L, varied), "warm five");
            check(!(Boolean)observe.invoke(target, 10000L, "PLAYING", 110000L, varied), "warm six stabilises");
            check(!(Boolean)observe.invoke(target, 12000L, "PLAYING", 110000L, EXPECTED), "deficit sample one");
            check((Boolean)observe.invoke(target, 14000L, "PLAYING", 110000L, varied),
                    "recovery fires against the updated expectation");
        });
    }

    static class Running implements AutoCloseable {
        final Object monitor;
        final Method start;
        final Method stop;
        Running(Fixture fixture, Runnable callback) throws Exception {
            Class<?> type = requiredClass(MONITOR);
            monitor = type.getConstructor(DlnaUpnp.Renderer.class, String.class, Runnable.class)
                    .newInstance(fixture.renderer(), EXPECTED, callback);
            start = type.getMethod("start");
            stop = type.getMethod("stop");
        }
        void start() throws Exception { start.invoke(monitor); }
        public void close() throws Exception { stop.invoke(monitor); }
    }

    static void registerWorker(Map<String, Checked> cases) {
        cases.put("worker-idempotent-start-and-interruptible-stop", () -> {
            try (Fixture fixture = new Fixture()) {
                AtomicInteger callbacks = new AtomicInteger();
                try (Running running = new Running(fixture, callbacks::incrementAndGet)) {
                    running.start();
                    running.start();
                    check(fixture.entered.await(2, TimeUnit.SECONDS), "worker did not poll");
                    Thread.sleep(300);
                    check(fixture.actions.size() == 4, "start must not spawn duplicate workers");
                    long started = System.nanoTime();
                    running.close();
                    check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 250, "stop blocks");
                    int stoppedAt = fixture.actions.size();
                    Thread.sleep(2200);
                    check(fixture.actions.size() == stoppedAt, "stopped worker still polls");
                    check(callbacks.get() == 0, "callback after stop");
                    running.start();
                    Thread.sleep(300);
                    check(fixture.actions.size() == stoppedAt + 4, "explicit restart must work");
                }
            }
        });
        cases.put("worker-stop-inflight-and-replace-generation", () -> {
            try (Fixture fixture = new Fixture()) {
                fixture.gate = new CountDownLatch(1);
                AtomicInteger callbacks = new AtomicInteger();
                try (Running running = new Running(fixture, callbacks::incrementAndGet)) {
                    running.start();
                    check(fixture.entered.await(2, TimeUnit.SECONDS), "no in-flight query");
                    long started = System.nanoTime();
                    running.close();
                    check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 250, "stop waits for network");
                    fixture.gate.countDown();
                    Thread.sleep(300);
                    check(fixture.actions.equals(Arrays.asList("GetTransportInfo")), "cancelled query continued");
                    check(callbacks.get() == 0, "cancelled query recovered");
                    running.start();
                    Thread.sleep(300);
                    check(fixture.actions.size() == 5, "retired worker must not clear replacement");
                }
            }
        });
        cases.put("worker-live-recovery-and-fail-closed-guards", () -> {
            try (Fixture playing = new Fixture(); Fixture paused = new Fixture();
                    Fixture foreign = new Fixture(); Fixture unsupported = new Fixture(); Fixture failed = new Fixture()) {
                AtomicInteger positionReads = new AtomicInteger();
                playing.reply = action -> !action.equals("GetPositionInfo") ? defaultReply(action)
                        : position("00:01:" + (40 + Math.min(positionReads.getAndIncrement(), 5) * 2), "");
                paused.reply = action -> transport("PAUSED_PLAYBACK", "OK", "1");
                AtomicInteger foreignReads = new AtomicInteger();
                foreign.reply = action -> !action.equals("GetPositionInfo") ? defaultReply(action)
                        : position("00:01:" + (40 + Math.min(foreignReads.getAndIncrement(), 5) * 2), EXPECTED + "-foreign");
                unsupported.reply = action -> !action.equals("GetPositionInfo") ? defaultReply(action)
                        : position("NOT_IMPLEMENTED", "");
                AtomicInteger failedReads = new AtomicInteger();
                failed.reply = action -> {
                    if (!action.equals("GetPositionInfo")) return defaultReply(action);
                    int index = failedReads.getAndIncrement();
                    if (index >= 6) failed.errorAction = "GetTransportInfo";
                    return position("00:01:" + (40 + Math.min(index, 5) * 2), "");
                };
                AtomicInteger recovered = new AtomicInteger();
                AtomicInteger forbidden = new AtomicInteger();
                CountDownLatch recovery = new CountDownLatch(1);
                AtomicReference<Running> owner = new AtomicReference<>();
                try (Running good = new Running(playing, () -> {
                            recovered.incrementAndGet();
                            try { owner.get().close(); }
                            catch (Exception failure) { throw new RuntimeException(failure); }
                            recovery.countDown();
                        });
                        Running pauseGuard = new Running(paused, forbidden::incrementAndGet);
                        Running foreignGuard = new Running(foreign, forbidden::incrementAndGet);
                        Running unsupportedGuard = new Running(unsupported, forbidden::incrementAndGet);
                        Running failedGuard = new Running(failed, forbidden::incrementAndGet)) {
                    owner.set(good);
                    good.start();
                    pauseGuard.start();
                    foreignGuard.start();
                    unsupportedGuard.start();
                    failedGuard.start();
                    check(recovery.await(22, TimeUnit.SECONDS), "persistent new drift did not invoke callback");
                    Thread.sleep(250);
                    check(recovered.get() == 1, "callback must be single-shot before reset");
                    check(forbidden.get() == 0, "paused/foreign/unsupported/failed query recovered");
                    check(playing.actions.stream().allMatch(action -> action.startsWith("Get")), "monitor sent controls");
                }
            }
        });
    }

    public static void main(String[] args) throws Exception {
        Map<String, Checked> cases = new LinkedHashMap<>();
        registerQueries(cases);
        registerTracker(cases);
        registerWorker(cases);
        int passed = 0;
        int failed = 0;
        for (Map.Entry<String, Checked> entry : cases.entrySet()) {
            if (args.length != 0 && Arrays.stream(args).noneMatch(entry.getKey()::contains)) continue;
            try {
                entry.getValue().run();
                passed++;
                System.out.println("PASS " + entry.getKey());
            } catch (Throwable failure) {
                failed++;
                System.out.println("FAIL " + entry.getKey() + ": " + failure);
            }
        }
        System.out.println(passed + " passed, " + failed + " failed");
        check(passed + failed > 0, "no matching regressions");
        check(failed == 0, failed + " regression(s)");
    }
}
'''


def main():
    with tempfile.TemporaryDirectory(prefix="dlna-playback-test-") as directory:
        temporary = Path(directory)
        stub = temporary / "android/util/Log.java"
        stub.parent.mkdir(parents=True)
        stub.write_text(
            "package android.util; public class Log { "
            "public static int i(String tag,String message){return 0;} "
            "public static int w(String tag,String message){return 0;} }",
            encoding="utf-8",
        )
        harness = temporary / "PlaybackMonitorRegression.java"
        harness.write_text(JAVA, encoding="utf-8")
        sources = ROOT / "android/app/src/main/java/com/nightmare/sunshine/dlna"
        java_sources = [sources / name for name in (
            "DlnaUpnp.java", "DlnaHttpServer.java", "DlnaHlsStore.java",
        )]
        monitor = sources / "DlnaPlaybackMonitor.java"
        if monitor.exists():
            java_sources.append(monitor)
        jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin"
        subprocess.run([
            str(jdk / "javac"), "--release", "17", "-encoding", "UTF-8",
            "-d", str(temporary), str(stub), str(harness), *map(str, java_sources),
        ], check=True)
        subprocess.run([
            str(jdk / "java"), "-cp", str(temporary), "PlaybackMonitorRegression", *sys.argv[1:],
        ], check=True, timeout=120)


if __name__ == "__main__":
    main()
