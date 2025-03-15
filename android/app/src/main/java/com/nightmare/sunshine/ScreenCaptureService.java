package com.nightmare.sunshine;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import androidx.core.app.NotificationCompat;

import com.nightmare.sunshine_android.R;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

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


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String sunshineName = Build.MANUFACTURER + "-" + Build.MODEL;
        NativeBridge.setSunshineName(sunshineName);
        NativeBridge.setFileStatePath(getFilesDir().getAbsolutePath() + "/sunshine_state.json");
        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("data");
        MDNSHelper.broadcastService(this);
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
        Log.d("tag", "onStartCommand: " + mediaProjection);
        NativeBridge.setFileStatePath(this.getFilesDir().getAbsolutePath() + "/sunshine_state.json");
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
        NativeBridge.mediaProjection = mediaProjection;

        new Thread(new Runnable() {
            @Override
            public void run() {
                NativeBridge.start();
            }
        }).start();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Capture")
                .setContentText("Screen capture is running")
                .setSmallIcon(android.R.drawable.ic_notification_overlay)
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
                NativeBridge.setCertPath(context.getFilesDir().getAbsolutePath() + "/cacert.pem");
            }

            // 写入密钥文件
            try (InputStream keyInput = context.getAssets().open("cakey.pem");
                 FileOutputStream keyOutput = context.openFileOutput("cakey.pem", Context.MODE_PRIVATE)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = keyInput.read(buffer)) > 0) {
                    keyOutput.write(buffer, 0, length);
                }
                NativeBridge.setPkeyPath(context.getFilesDir().getAbsolutePath() + "/cakey.pem");
            }

            android.util.Log.i("TAG", "证书和密钥文件写入成功: " + context.getFilesDir().getAbsolutePath());
        } catch (IOException e) {
            android.util.Log.e("TAG", "写入证书文件失败", e);
        }
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


    private void startMediaProjectionForeground() {
        String channelId = "ScreenCaptureChannel";
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Screen Capture Service",
                    NotificationManager.IMPORTANCE_LOW);  // Use LOW to avoid disturbing the user
            channel.setDescription("Screen capture service running");
            notificationManager.createNotificationChannel(channel);
        }
        // Build a proper notification with all required elements
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Screen Capture Active")
                .setContentText("Your screen is being captured")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true);
        Notification notification = notificationBuilder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }
    }
}