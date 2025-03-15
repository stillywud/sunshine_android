package com.nightmare.sunshine;

import static com.nightmare.sunshine.MainActivity.surfaceView;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;


import com.nightmare.sunshine_android.R;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

public class ScreenCaptureService extends Service {
    private static final String CHANNEL_ID = "ScreenCaptureChannel";
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;

    private static final String TAG = "SunshineService";

    @Override
    public void onCreate() {
        super.onCreate();
        startMediaProjectionForeground();
        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
    }

    private static boolean isWifiApEnabled(WifiManager mWifiManager) {
        boolean apState = false;
        try {
            // @RequiresPermission(android.Manifest.permission.ACCESS_WIFI_STATE)
            apState = (boolean) mWifiManager.getClass().getMethod("isWifiApEnabled").invoke(mWifiManager);
            Log.i(TAG, "isWifiApEnabled :" + apState + "");
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            Log.e(TAG, "failed to get  isWifiApEnabled", e );
        }
        return apState;
    }
    public static void printMap(Map<String, String> map) {
        for (Map.Entry<String, String> entry : map.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
        }
    }
    private static String getApIP() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()){
                NetworkInterface ni = networkInterfaces.nextElement();
                Log.d(TAG, "network interface: " + ni.getName());
                if(ni.isUp() && !ni.isPointToPoint() && !ni.isLoopback() && ("ap0".equals(ni.getName()) || "softap0".equals(ni.getName()) || "wlan2".equals(ni.getName()))){
                    List<InterfaceAddress> interfaceAddresses = ni.getInterfaceAddresses();
                    for (InterfaceAddress interfaceAddress : interfaceAddresses) {
                        if(interfaceAddress.getAddress() != null){
                            Log.d(TAG,"address:"+interfaceAddress.getAddress().toString());
                            if(interfaceAddress.getAddress().toString().contains("/192.168")){
                                String softApIP = interfaceAddress.getAddress().toString().substring(1);
                                Log.d(TAG,"getApIP:"+softApIP);
                                return softApIP;
                            }
                        }
                    }
                }
            }
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public static String getWifiIpAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            return null;
        }
        if (isWifiApEnabled(wifiManager)) {
            String apIp = getApIP();
            if (apIp != null) {
                return apIp;
            }
        }

        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        // Convert little-endian to big-endian if needed
        byte[] bytes = new byte[4];
        bytes[0] = (byte) (ipAddress & 0xFF);
        bytes[1] = (byte) ((ipAddress >> 8) & 0xFF);
        bytes[2] = (byte) ((ipAddress >> 16) & 0xFF);
        bytes[3] = (byte) ((ipAddress >> 24) & 0xFF);

        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String sunshineName = "屏易连-"  + Build.MANUFACTURER + "-" + Build.MODEL;
        SunshineServer.setSunshineName(sunshineName);        String addr = getWifiIpAddress(this);
        if (addr == null) {
//            State.log("无法获取WiFi IP地址");
            Log.d("tag", "无法获取WiFi IP地址");
        } else {
//            State.log("发布 moonlight 服务名："  + sunshineName);
//            State.log("发布 moonlight ip："  + addr);
            Log.d("tag", "发布 moonlight 服务名：" + sunshineName);
            Log.d("tag", "发布 moonlight ip：" + addr);
        }
        SunshineServer.setFileStatePath(getFilesDir().getAbsolutePath() + "/sunshine_state.json");
        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("data");
        new Thread(new Runnable() {
            @Override
            public void run() {
                JmDNS jmdns = null;
                try {
                    jmdns = JmDNS.create(Inet4Address.getByName(addr));
                    ServiceInfo serviceInfo = ServiceInfo.create(
                            "_nvstream._tcp.local.",
                            "ConnectScreen",
                            47989,
                            "ConnectScreen"
                    );

                    jmdns.registerService(serviceInfo);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                Log.i("MirrorHomeFragment", "JmDNS服务注册成功");
            }
        }).start();
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
        Log.d("tag", "onStartCommand: " + mediaProjection);
        SunshineServer.setFileStatePath(this.getFilesDir().getAbsolutePath() + "/sunshine_state.json");
        writeCertAndKey(this);
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                Log.d("tag", "MediaProjection stopped");
                if (virtualDisplay != null) {
                    virtualDisplay.release();
                }
                if (mediaProjection != null) {
                    mediaProjection.stop();
                }
            }
        }, null);
        SunshineServer.mediaProjection = mediaProjection;

        new Thread(new Runnable() {
            @Override
            public void run() {
                SunshineServer.start();
            }
        }).start();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Capture")
                .setContentText("Screen capture is running")
                .setSmallIcon(R.drawable.launch_background)
                .build();
        startForeground(1, notification);

        return START_STICKY;
    }


    public static void writeCertAndKey(Context context) {
        try {
            // 写入证书文件
            try (InputStream certInput = context.getAssets().open("cacert.pem");
                 FileOutputStream certOutput = context.openFileOutput("cacert.pem", Context.MODE_PRIVATE)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = certInput.read(buffer)) > 0) {
                    certOutput.write(buffer, 0, length);
                }
                SunshineServer.setCertPath(context.getFilesDir().getAbsolutePath() + "/cacert.pem");
            }

            // 写入密钥文件
            try (InputStream keyInput = context.getAssets().open("cakey.pem");
                 FileOutputStream keyOutput = context.openFileOutput("cakey.pem", Context.MODE_PRIVATE)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = keyInput.read(buffer)) > 0) {
                    keyOutput.write(buffer, 0, length);
                }
                SunshineServer.setPkeyPath(context.getFilesDir().getAbsolutePath() + "/cakey.pem");
            }

            android.util.Log.i("TAG", "证书和密钥文件写入成功: " + context.getFilesDir().getAbsolutePath());
        } catch (IOException e) {
            android.util.Log.e("TAG", "写入证书文件失败", e);
        }
    }
    public void createProjectionVirtualDisplay() {

//        if (mMediaProjection != null && ProjectionView.isSurfaceCreated()) {
//            WindowManager WINDOW_MANAGER = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
//            DisplayMetrics dm = new DisplayMetrics();
//            WINDOW_MANAGER.getDefaultDisplay().getRealMetrics(dm);
//            mMediaProjection.registerCallback(MEDIA_PROJECTION_CALLBACK, null);
//            mVirtualDisplayProjection = mMediaProjection.createVirtualDisplay("Projection", dm.widthPixels, dm.heightPixels, dm.densityDpi, Display.FLAG_ROUND, /*Surface*/, null, null);
//        }
    }
    private void setupVirtualDisplay() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int density = metrics.densityDpi;
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;

        Surface surface = surfaceView.getHolder().getSurface();
        if (surface == null || !surface.isValid()) {
            Log.e("tag", "Surface is not valid");
            stopSelf();
            return;
        }

        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null);
        SunshineServer.start();
        Log.d("tag", "setupVirtualDisplay: " + virtualDisplay);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Capture Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }


    private void startMediaProjectionForeground() {
        NotificationManager NOTIFICATION_MANAGER = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder notificationBuilder = new Notification.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("服务已启动");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = "CHANNEL_ID_MEDIA_PROJECTION";
            NotificationChannel channel = new NotificationChannel(channelId, "屏幕录制", NotificationManager.IMPORTANCE_HIGH);
            NOTIFICATION_MANAGER.createNotificationChannel(channel);

            notificationBuilder.setChannelId(channelId);
        }
        Notification notification = notificationBuilder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            startForeground(1, notification);
        } else {
            startForeground(1, notification);
        }
    }
}