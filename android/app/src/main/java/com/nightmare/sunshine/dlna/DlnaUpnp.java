package com.nightmare.sunshine.dlna;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * DLNA / UPnP 设备发现与 AVTransport 推送。
 *
 * 全部逻辑就是两步：
 *   1. 发 SSDP M-SEARCH（组播 239.255.255.250:1900），收 MediaRenderer 的响应
 *   2. 对选中的设备发 SOAP：SetAVTransportURI + Play
 *
 * 没有引入 Cling 之类的库，因为只用到这两个动作，自己发 UDP + HTTP 更轻，
 * 也不会和现有的 MediaProjection/编码管线抢依赖。
 */
public class DlnaUpnp {
    private static final String TAG = "DlnaUpnp";
    private static final String AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String SOAP_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final int QUERY_TIMEOUT_MS = 1500;
    private static final int QUERY_BODY_LIMIT = 64 * 1024;
    private static final Pattern REL_TIME = Pattern.compile(
            "([0-9]+):([0-5][0-9]):([0-5][0-9])(?:\\.([0-9]+))?");

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    private static final Pattern LOCATION =
            Pattern.compile("^LOCATION\\s*:\\s*(\\S+)\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern SERVER =
            Pattern.compile("<SERVER>(.*?)</SERVER>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ST =
            Pattern.compile("<ST>(.*?)</ST>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FRIENDLY_NAME =
            Pattern.compile("<friendlyName>(.*?)</friendlyName>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CONTROL_URL =
            Pattern.compile("<controlURL>(.*?)</controlURL>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern UDN =
            Pattern.compile("<UDN>(.*?)</UDN>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 一个 DLNA 接收端 */
    public static class Renderer {
        public String name = "";
        public String location = "";
        public String udn = "";
        public String avTransportControlUrl = "";

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 搜索局域网内的 DLNA 接收端（MediaRenderer）。
     *
     * 两条路径并用：
     *   1. SSDP 组播（标准做法，真机上工作良好）
     *   2. 单播扫描（雷电等模拟器的虚拟 NAT 不转发组播，必须靠这个兜底）
     *
     * @param timeoutMs 等待响应的时长，建议 3000~5000
     */
    public static List<Renderer> discover(int timeoutMs) {
        List<Renderer> result = new ArrayList<>();
        Map<String, Renderer> byUdn = new LinkedHashMap<>();

        // 最快路径：先用上次成功过的地址试试（接收端 App 常换端口，
        // 但如果没换就能秒出结果）
        // Reuse the last successful renderer first. Its port may change after
        // a receiver restart, so normal SSDP/subnet discovery still follows.
        Renderer remembered = rememberedRenderer;
        if (remembered != null && remembered.location != null
                && !remembered.location.isEmpty()) {
            try {
                if (enrich(remembered)) {
                    String key = (remembered.udn == null || remembered.udn.isEmpty())
                            ? remembered.location : remembered.udn;
                    byUdn.put(key, remembered);
                    Log.i(TAG, "reuse renderer " + remembered.location);
                }
            } catch (Exception ignored) {
                // Fall through to SSDP and subnet discovery.
            }
        }

        // A remembered renderer is already a usable result. Give SSDP a short
        // chance to refresh it, but do not make reconnects wait the full scan.
        ssdpSearch(byUdn.isEmpty() ? timeoutMs : Math.min(timeoutMs, 250), byUdn);
        if (!byUdn.isEmpty()) {
            result.addAll(byUdn.values());
            Log.i(TAG, "reuse/discovery completed with " + result.size() + " renderer(s)");
            return result;
        }

        // 组播没找到设备时，退化为单播扫描本网段
        if (byUdn.isEmpty()) {
            Log.i(TAG, "SSDP 组播未发现设备，改用单播扫描");
            unicastScan(byUdn);
        }

        result.addAll(byUdn.values());
        Log.i(TAG, "共发现 " + result.size() + " 个 DLNA 接收端");
        return result;
    }

    /** 上次成功投屏的接收端，用于下一次快速定位 */
    private static volatile Renderer rememberedRenderer;

    /** 调用方投屏成功后把设备记下来，下次搜索优先探测它 */
    public static void remember(Renderer r) {
        rememberedRenderer = r;
    }

    /** 标准 SSDP 组播搜索 */
    private static void ssdpSearch(int timeoutMs, Map<String, Renderer> byUdn) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(500);
            socket.setBroadcast(true);

            String msg = "M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 3\r\n"
                    + "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
                    + "\r\n";

            byte[] data = msg.getBytes("UTF-8");
            DatagramPacket packet = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
            socket.send(packet);
            Log.i(TAG, "已发送 SSDP M-SEARCH");

            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[8192];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(resp);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }

                String text = new String(resp.getData(), 0, resp.getLength(), "UTF-8");
                String location = extract(LOCATION, text);
                if (location == null || location.isEmpty()) {
                    continue;
                }
                addRenderer(location, byUdn);
            }
        } catch (Exception e) {
            Log.w(TAG, "SSDP 搜索失败: " + e.getMessage());
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    /**
     * 单播扫描：对本机所在 /24 网段的每个地址探测 DLNA 服务端口。
     *
     * 模拟器（雷电/夜神等）的虚拟 NAT 不转发 239.255.255.250 组播，
     * SSDP 一定收不到回应，所以必须有这条路。真机上组播能通，
     * 这个分支不会执行。
     *
     * 端口策略：先探常见固定端口，再对"看起来有开放端口的主机"做一段
     * 范围扫描。因为像光元素投屏这类 App 每次启动会换随机高位端口
     * （实测见过 1474 / 1326 / 55556 等），只列固定端口会漏。
     */
    private static void unicastScan(Map<String, Renderer> byUdn) {
        String localIp = localIpv4();
        if (localIp == null) {
            Log.w(TAG, "拿不到本机 IP，无法单播扫描");
            return;
        }

        int lastDot = localIp.lastIndexOf('.');
        if (lastDot <= 0) {
            return;
        }
        String prefix = localIp.substring(0, lastDot + 1);
        Log.i(TAG, "扫描网段 " + prefix + "0/24");

        // 第一步：全网段快速探活，找出哪些主机在跑 DLNA 类服务。
        // 端口选各家投屏 App 的典型监听口，命中概率高且数量少。
        int[] probePorts = {1474, 1478, 1086, 8080, 8081, 8888, 9090, 1900, 49152, 49153, 55556};
        // 第二步：对探活成功的主机做端口区间扫描。
        // 实测「光元素投屏」每次启动会在这个范围里随机选端口
        // （见过 1086 / 1326 / 1474 / 55556），所以必须扫区间。
        int[][] ranges = {
                {1024, 6144},      // 低随机段（实测 1086/1326/1474/3333）
                {55500, 55600},    // 实测出现过 55556
                {49000, 49200},    // UPnP 标准动态段
        };

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(200);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        // 先扫候选端口，收集有响应的主机
        List<String> aliveHosts = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int host = 1; host < 255; host++) {
            final String ip = prefix + host;
            if (ip.equals(localIp)) {
                continue;
            }
            for (final int port : probePorts) {
                futures.add(pool.submit(() -> {
                    if (tcpAlive(ip, port, 150)) {
                        synchronized (aliveHosts) {
                            if (!aliveHosts.contains(ip)) {
                                aliveHosts.add(ip);
                            }
                        }
                        // 顺手试一下这个端口是不是就是 DLNA
                        probe(ip, port, 150, byUdn);
                    }
                }));
            }
        }

        pool.shutdown();
        try {
            pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        synchronized (byUdn) {
            if (!byUdn.isEmpty()) {
                Log.i(TAG, "候选端口已找到设备，跳过区间扫描");
                return;
            }
        }

        Log.i(TAG, "候选端口未命中，对 " + aliveHosts.size() + " 台活跃主机做区间扫描");
        if (aliveHosts.isEmpty()) {
            // 一台都没探活，退化为「全网段只扫最常见的几个端口」
            aliveHosts.addAll(java.util.Arrays.asList(prefix + "1"));
        }

        pool = java.util.concurrent.Executors.newFixedThreadPool(200);
        for (String ip : aliveHosts) {
            for (int[] r : ranges) {
                for (int port = r[0]; port <= r[1]; port++) {
                    final int p = port;
                    final String host = ip;
                    futures.add(pool.submit(() -> probe(host, p, 120, byUdn)));
                }
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(90, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Log.i(TAG, "范围扫描结束，命中 " + byUdn.size() + " 个");
    }

    /** TCP 探活 */
    private static boolean tcpAlive(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 探测单个 ip:port 是否是可用的 DLNA 接收端 */
    private static void probe(String ip, int port, int connectTimeout, Map<String, Renderer> byUdn) {
        // 已经找到了就提前退出，避免无谓扫描
        synchronized (byUdn) {
            if (!byUdn.isEmpty()) {
                return;
            }
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), connectTimeout);
        } catch (Exception e) {
            return;
        }

        String location = "http://" + ip + ":" + port + "/";
        String xml = httpGet(location, 900);
        if (xml == null || !xml.contains("MediaRenderer")) {
            return;
        }
        synchronized (byUdn) {
            addRenderer(location, byUdn);
        }
    }

    /** 解析设备描述并加入结果集 */
    private static void addRenderer(String location, Map<String, Renderer> byUdn) {
        Renderer r = new Renderer();
        r.location = location;
        if (!enrich(r)) {
            return;
        }
        String key = (r.udn == null || r.udn.isEmpty()) ? r.location : r.udn;
        if (!byUdn.containsKey(key)) {
            byUdn.put(key, r);
            Log.i(TAG, "发现设备: " + r.name + " @ " + r.location);
        }
    }

    /** 取本机局域网 IPv4 */
    private static String localIpv4() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                java.util.Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ip != null && !ip.startsWith("169.254.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 拉设备描述，补全 name / udn / AVTransport controlURL */
    private static boolean enrich(Renderer r) {
        try {
            String xml = httpGet(r.location, 6000);
            if (xml == null) {
                return false;
            }

            String name = extract(FRIENDLY_NAME, xml);
            if (name != null && !name.isEmpty()) {
                r.name = name;
            }
            String udn = extract(UDN, xml);
            if (udn != null) {
                r.udn = udn;
            }

            // 找到 AVTransport 服务块里的 controlURL
            Matcher svc = Pattern.compile(
                    "<service>(?:(?!</service>).)*?AVTransport(?:(?!</service>).)*?</service>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(xml);
            if (!svc.find()) {
                Log.w(TAG, r.name + " 没有 AVTransport 服务，跳过");
                return false;
            }
            String ctrl = extract(CONTROL_URL, svc.group(0));
            if (ctrl == null || ctrl.isEmpty()) {
                return false;
            }

            if (ctrl.startsWith("http://") || ctrl.startsWith("https://")) {
                r.avTransportControlUrl = ctrl;
            } else {
                URL loc = new URL(r.location);
                String base = loc.getProtocol() + "://" + loc.getHost()
                        + (loc.getPort() > 0 ? ":" + loc.getPort() : "");
                r.avTransportControlUrl = ctrl.startsWith("/") ? base + ctrl : base + "/" + ctrl;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "读取设备描述失败 " + r.location + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 把媒体地址推给接收端并开始播放。
     *
     * @param mediaUrl  形如 http://手机IP:端口/live.ts
     * @param mimeType  视频 MIME，TS 用 video/mp2t
     */
    public static boolean play(Renderer r, String mediaUrl, String mimeType) {
        return play(r, mediaUrl, mimeType, 0L);
    }

    /**
     * 同上，但允许在 Stop 与 SetAVTransportURI 之间等待。
     *
     * 接收端应用在收到新 URI 时会立刻重建播放器,而旧播放器的销毁是异步的:
     * 两者重叠时,新播放器会在视频尺寸未知(0x0)时就配置硬件解码器
     * ("Wrong cropped rect (0,0,0,0)"),配置失败后回退到软解,画面周期性
     * 跳帧,最终触发接收端 30 秒加载超时(-2)。给销毁留出结算时间即可
     * 避免这个竞态。
     *
     * @param settleAfterStopMs Stop 之后、SetAVTransportURI 之前的等待毫秒数
     */
    public static boolean play(Renderer r, String mediaUrl, String mimeType, long settleAfterStopMs) {
        try {
            // 先停止上一个（忽略失败）
            soap(r, "Stop", "<InstanceID>0</InstanceID>");
            if (settleAfterStopMs > 0) {
                try {
                    Thread.sleep(settleAfterStopMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            String meta = buildMetadata(mediaUrl, mimeType);
            String setUri = "<InstanceID>0</InstanceID>"
                    + "<CurrentURI>" + escapeXml(mediaUrl) + "</CurrentURI>"
                    + "<CurrentURIMetaData>" + escapeXml(meta) + "</CurrentURIMetaData>";
            if (!soap(r, "SetAVTransportURI", setUri)) {
                return false;
            }

            if (!soap(r, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")) {
                return false;
            }

            Log.i(TAG, "已推送并播放: " + mediaUrl + " -> " + r.name);
            remember(r);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "推送失败: " + e.getMessage());
            return false;
        }
    }

    public static boolean stop(Renderer r) {
        return soap(r, "Stop", "<InstanceID>0</InstanceID>");
    }

    public static final class PlaybackSnapshot {
        public final String transportState;
        public final long positionMs;
        public final String mediaUri;

        private PlaybackSnapshot(String transportState, long positionMs, String mediaUri) {
            this.transportState = transportState;
            this.positionMs = positionMs;
            this.mediaUri = mediaUri;
        }
    }

    public static PlaybackSnapshot getPlaybackSnapshot(Renderer renderer) {
        String state = queryTransportState(renderer);
        if (state == null) {
            return null;
        }
        if (!"PLAYING".equals(state)) {
            return new PlaybackSnapshot(state, -1, "");
        }
        Element position = queryResponse(renderer, "GetPositionInfo");
        long positionMs = parseRelTimeMs(responseText(position, "RelTime"));
        String mediaUri = responseText(position, "TrackURI");
        if (positionMs < 0 || mediaUri == null) {
            return null;
        }
        if (mediaUri.isEmpty()) {
            mediaUri = responseText(queryResponse(renderer, "GetMediaInfo"), "CurrentURI");
        }
        if (mediaUri == null || mediaUri.isEmpty() || "NOT_IMPLEMENTED".equals(mediaUri)) {
            return null;
        }
        state = queryTransportState(renderer);
        return state == null ? null : new PlaybackSnapshot(state, positionMs, mediaUri);
    }

    private static String queryTransportState(Renderer renderer) {
        Element transport = queryResponse(renderer, "GetTransportInfo");
        String state = responseText(transport, "CurrentTransportState");
        if (state == null || state.isEmpty()
                || !"OK".equals(responseText(transport, "CurrentTransportStatus"))
                || !"1".equals(responseText(transport, "CurrentSpeed"))) {
            return null;
        }
        return state;
    }

    private static Element queryResponse(Renderer renderer, String action) {
        if (renderer == null || renderer.avTransportControlUrl == null
                || renderer.avTransportControlUrl.isEmpty() || Thread.currentThread().isInterrupted()) {
            return null;
        }
        HttpURLConnection connection = null;
        long deadline = System.nanoTime() + QUERY_TIMEOUT_MS * 1_000_000L;
        try {
            connection = (HttpURLConnection) new URL(renderer.avTransportControlUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(QUERY_TIMEOUT_MS);
            connection.setReadTimeout(QUERY_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            connection.setRequestProperty("SOAPACTION", "\"" + AV_TRANSPORT_SERVICE + "#" + action + "\"");
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"" + SOAP_NAMESPACE
                    + "\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body><u:" + action + " xmlns:u=\"" + AV_TRANSPORT_SERVICE + "\">"
                    + "<InstanceID>0</InstanceID></u:" + action + "></s:Body></s:Envelope>";
            byte[] payload = body.getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            connection.setReadTimeout(queryTimeLeft(deadline));
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300 || connection.getContentLength() > QUERY_BODY_LIMIT) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                while (!Thread.currentThread().isInterrupted()) {
                    connection.setReadTimeout(queryTimeLeft(deadline));
                    int count = input.read(buffer);
                    if (count < 0) {
                        queryTimeLeft(deadline);
                        return parseQueryResponse(bytes.toString("UTF-8"), action);
                    }
                    if (bytes.size() + count > QUERY_BODY_LIMIT) {
                        return null;
                    }
                    bytes.write(buffer, 0, count);
                }
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    private static int queryTimeLeft(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            throw new SocketTimeoutException("UPnP query cancelled or timed out");
        }
        return (int) Math.max(1, remaining / 1_000_000L);
    }

    private static Element parseQueryResponse(String xml, String action) throws Exception {
        if (xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")) {
            return null;
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        builder.setErrorHandler(new DefaultHandler());
        Element envelope = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        if (!"Envelope".equals(envelope.getLocalName()) || !SOAP_NAMESPACE.equals(envelope.getNamespaceURI())) {
            return null;
        }
        Element body = responseChild(envelope, "Body", SOAP_NAMESPACE);
        if (responseChild(body, "Fault", SOAP_NAMESPACE) != null) {
            return null;
        }
        return responseChild(body, action + "Response", AV_TRANSPORT_SERVICE);
    }

    private static Element responseChild(Element parent, String name, String namespace) {
        if (parent == null) {
            return null;
        }
        Element result = null;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE || !name.equals(child.getLocalName())) {
                continue;
            }
            String actualNamespace = child.getNamespaceURI();
            boolean validNamespace = namespace == null
                    ? actualNamespace == null || actualNamespace.isEmpty() || AV_TRANSPORT_SERVICE.equals(actualNamespace)
                    : namespace.equals(actualNamespace);
            if (!validNamespace || result != null) {
                return null;
            }
            result = (Element) child;
        }
        return result;
    }

    private static String responseText(Element response, String name) {
        Element value = responseChild(response, name, null);
        if (value == null || value.getElementsByTagName("*").getLength() != 0) {
            return null;
        }
        return value.getTextContent().trim();
    }

    private static long parseRelTimeMs(String time) {
        if (time == null) {
            return -1;
        }
        Matcher match = REL_TIME.matcher(time);
        if (!match.matches()) {
            return -1;
        }
        try {
            long hours = Long.parseLong(match.group(1));
            long remainder = Long.parseLong(match.group(2)) * 60000L + Long.parseLong(match.group(3)) * 1000L;
            String fraction = match.group(4);
            if (fraction != null) {
                remainder += Long.parseLong((fraction + "000").substring(0, 3));
            }
            return hours > (Long.MAX_VALUE - remainder) / 3600000L ? -1 : hours * 3600000L + remainder;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    /** 标准 DIDL-Lite 元数据。缺了它不少接收端会拒绝播放。 */
    private static String buildMetadata(String mediaUrl, String mimeType) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"1\" parentID=\"0\" restricted=\"1\">"
                + "<dc:title>Screen Mirror</dc:title>"
                + "<upnp:class>object.item.videoItem.videoBroadcast</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + mimeType + ":"
                + DlnaHttpServer.LIVE_CONTENT_FEATURES + "\">"
                + escapeXml(mediaUrl)
                + "</res></item></DIDL-Lite>";
    }

    private static boolean soap(Renderer r, String action, String argsXml) {
        if (r.avTransportControlUrl == null || r.avTransportControlUrl.isEmpty()) {
            Log.w(TAG, "设备没有 controlURL: " + r.name);
            return false;
        }

        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" "
                + "xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<s:Body><u:" + action
                + " xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                + argsXml
                + "</u:" + action + "></s:Body></s:Envelope>";

        HttpURLConnection conn = null;
        try {
            URL url = new URL(r.avTransportControlUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION",
                    "\"urn:schemas-upnp-org:service:AVTransport:1#" + action + "\"");

            byte[] payload = body.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            boolean ok = code >= 200 && code < 300;
            if (!ok) {
                Log.w(TAG, action + " 返回 HTTP " + code);
            }
            return ok;
        } catch (Exception e) {
            Log.w(TAG, action + " 异常: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String httpGet(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "GET " + url + " 失败: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String extract(Pattern p, String text) {
        if (text == null) {
            return null;
        }
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
