package com.nightmare.sunshine.dlna;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.nightmare.sunshine_android.R;

/**
 * 承载 DLNA 投屏的前台服务。
 *
 * 存在的唯一理由：Android 14（API 34）起，MediaProjectionManager.getMediaProjection()
 * 必须在「已经进入前台的 mediaProjection 类型前台服务」里调用，否则直接抛：
 *
 *   SecurityException: Media projections require a foreground service of type
 *   ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
 *
 * 在 Android 13 及以下没有这个限制，所以在 Activity 里直接拿是能跑的——
 * 这也是为什么这个问题只在 Android 14 的模拟器上才暴露出来。
 *
 * 这个服务不参与 Moonlight 串流那条链路（那条用 ScreenCaptureService），
 * 两条链路各自独立，互不影响。
 */
public class DlnaForegroundService extends Service {
    private static final String TAG = "DlnaService";
    private static final String CHANNEL_ID = "DlnaCastChannel";
    private static final int NOTIFICATION_ID = 2;

    /** 服务启动后由 Activity 取用 */
    public static volatile DlnaForegroundService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startAsForeground();
        Log.i(TAG, "DLNA 前台服务已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 这里不做投屏初始化：MediaProjection 的创建放在 DlnaStreamer 里，
        // 只要此时本服务已进入前台，那个调用就是合法的。
        return START_NOT_STICKY;
    }

    /** 创建一个低打扰的常驻通知，满足前台服务的要求。 */
    private void startAsForeground() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "DLNA 投屏", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("正在向电视推送屏幕画面");
            nm.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DLNA 投屏")
                .setContentText("正在向电视推送屏幕画面")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        Log.i(TAG, "DLNA 前台服务已销毁");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 启动前台服务的便捷方法。 */
    public static void start(Context context) {
        Intent intent = new Intent(context, DlnaForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** 停止前台服务。 */
    public static void stop(Context context) {
        context.stopService(new Intent(context, DlnaForegroundService.class));
    }
}
