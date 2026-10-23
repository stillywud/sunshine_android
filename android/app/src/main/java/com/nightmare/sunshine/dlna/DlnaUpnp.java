package com.nightmare.sunshine.dlna;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    private static final Pattern LOCATION =
            Pattern.compile("<LOCATION>(.*?)</LOCATION>", Pattern.CASE_INSENSITIVE);
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
        rememberedRenderer = null;

        ssdpSearch(timeoutMs, byUdn);

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
        int[] probePorts = {1474, 1086, 8080, 8081, 8888, 9090, 1900, 49152, 49153, 55556};
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
        try {
            // 先停止上一个（忽略失败）
            soap(r, "Stop", "<InstanceID>0</InstanceID>");

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

    /** 标准 DIDL-Lite 元数据。缺了它不少接收端会拒绝播放。 */
    private static String buildMetadata(String mediaUrl, String mimeType) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"1\" parentID=\"0\" restricted=\"1\">"
                + "<dc:title>Screen Mirror</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + mimeType + ":*\">"
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
