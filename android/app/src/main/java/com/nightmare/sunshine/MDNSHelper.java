package com.nightmare.sunshine;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

public class MDNSHelper {
    private static final String TAG = "MDNSHelper";

    public static void broadcastService(Context context) {
        new Thread(() -> {
            try (JmDNS jmdns = JmDNS.create(Inet4Address.getByName(getWifiIpAddress(context)))) {
                ServiceInfo serviceInfo = ServiceInfo.create(
                        "_nvstream._tcp.local.",
                        "Sunshine",
                        47989,
                        "Sunshine"
                );

                jmdns.registerService(serviceInfo);
                Log.i(TAG, "JmDNS service registered successfully");

                // Keep the thread alive to maintain the broadcast
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    Log.i(TAG, "Service broadcast interrupted");
                }
            } catch (IOException e) {
                Log.e(TAG, "Error broadcasting service", e);
            }
        }).start();
    }

    public static String getWifiIpAddress(Context context) {
        if (context == null) return null;

        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return null;

        // First check if AP mode is enabled
        if (isWifiApEnabled(wifiManager)) {
            String apIp = getApIP();
            if (apIp != null) return apIp;
        }

        // If not AP or AP IP couldn't be found, check regular WiFi connection
        if (wifiManager.isWifiEnabled()) {
            int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
            if (ipAddress != 0) {
                return formatIpAddress(ipAddress);
            }
        }

        return null;
    }

    @SuppressLint("DefaultLocale")
    private static String formatIpAddress(int ipAddress) {
        return String.format(
                "%d.%d.%d.%d",
                ipAddress & 0xff,
                (ipAddress >> 8) & 0xff,
                (ipAddress >> 16) & 0xff,
                (ipAddress >> 24) & 0xff
        );
    }

    private static boolean isWifiApEnabled(WifiManager wifiManager) {
        try {
            Method method = wifiManager.getClass().getMethod("isWifiApEnabled");
            return (boolean) method.invoke(wifiManager);
        } catch (Exception e) {
            Log.e(TAG, "Failed to check AP state", e);
            return false;
        }
    }


    private static String getApIP() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                // Skip interfaces that aren't relevant
                if (!ni.isUp() || ni.isPointToPoint() || ni.isLoopback()) continue;

                String name = ni.getName();
                if ("ap0".equals(name) || "softap0".equals(name) || "wlan2".equals(name)) {
                    for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                        if (addr != null && addr.getAddress() != null) {
                            String ipString = addr.getAddress().getHostAddress();
                            if (ipString != null && ipString.startsWith("192.168.")) {
                                return ipString;
                            }
                        }
                    }
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "Error getting AP IP", e);
        }
        return null;
    }
}
