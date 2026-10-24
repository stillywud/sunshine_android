package com.nightmare.sunshine;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

import com.nightmare.sunshine.dlna.DlnaForegroundService;
import com.nightmare.sunshine.dlna.DlnaStreamer;
import com.nightmare.sunshine.dlna.DlnaUpnp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class MainActivity extends FlutterActivity {
    private static final int REQUEST_CODE = 1000;
    private static final int REQUEST_AUDIO_PERMISSION = 1001;
    /** DLNA 投屏单独用一套请求码，避免和 Moonlight 串流的授权流程互相干扰 */
    private static final int REQUEST_DLNA_PROJECTION = 1002;
    /** DLNA 投屏的录音权限（AudioPlaybackCapture 需要） */
    private static final int REQUEST_DLNA_AUDIO_PERMISSION = 1003;
    private MediaProjectionManager mediaProjectionManager;
    public static Context context;
    // 录屏权限请求中标记：防止连点重复弹权限框
    private static boolean isRequestingProjection = false;

    /** DLNA 投屏实例（全局唯一） */
    private DlnaStreamer dlnaStreamer;
    /** 待投屏的目标接收端 */
    private DlnaUpnp.Renderer pendingDlnaRenderer;
    private boolean pendingDlnaLowLatency = true;
    /** 最近一次搜索到的设备列表，dlnaStart 按下标取 */
    private List<DlnaUpnp.Renderer> lastDiscovered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = this;
        NativeBridge.init(this);
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MethodChannel channel = new MethodChannel(getFlutterEngine().getDartExecutor().getBinaryMessenger(), "com.nightmare.sunshine");
        channel.setMethodCallHandler(new MethodChannel.MethodCallHandler() {
            @Override
            public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
                switch (call.method) {
                    case "dlnaDiscover": {
                        // 搜索局域网内的 DLNA 接收端。放后台线程，避免阻塞 UI。
                        new Thread(() -> {
                            List<DlnaUpnp.Renderer> found = DlnaUpnp.discover(4000);
                            List<Map<String, Object>> payload = new ArrayList<>();
                            for (int i = 0; i < found.size(); i++) {
                                Map<String, Object> m = new HashMap<>();
                                m.put("index", i);
                                m.put("name", found.get(i).name);
                                m.put("location", found.get(i).location);
                                payload.add(m);
                            }
                            lastDiscovered = found;
                            runOnUiThread(() -> result.success(payload));
                        }, "DlnaDiscover").start();
                        break;
                    }
                    case "dlnaStart": {
                        // 参数：设备索引。先请求录屏权限，授权回来再真正启动。
                        Integer idx = call.arguments instanceof Map
                                ? call.argument("index")
                                : (Integer) call.arguments;
                        Boolean lowLatency = call.arguments instanceof Map
                                ? call.argument("lowLatency")
                                : null;
                        if (lastDiscovered == null || idx == null
                                || idx < 0 || idx >= lastDiscovered.size()) {
                            result.success("设备不存在，请重新搜索");
                            break;
                        }
                        if (isRequestingProjection) {
                            result.success("正在请求录屏权限…");
                            break;
                        }
                        pendingDlnaRenderer = lastDiscovered.get(idx);
                        pendingDlnaLowLatency = lowLatency == null || lowLatency;
                        isRequestingProjection = true;

                        // AudioPlaybackCapture 需要 RECORD_AUDIO 运行时授权，
                        // 否则应用层就采不到声音（系统会静默拒绝，不报错）。
                        // 没有这个权限视频照常，只是没声音，所以拒绝了也继续。
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                                && ContextCompat.checkSelfPermission(MainActivity.this,
                                        android.Manifest.permission.RECORD_AUDIO)
                                        != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(MainActivity.this,
                                    new String[]{android.Manifest.permission.RECORD_AUDIO},
                                    REQUEST_DLNA_AUDIO_PERMISSION);
                            result.success(null);
                            break;
                        }
                        requestDlnaScreenCapture();
                        result.success(null);
                        break;
                    }
                    case "dlnaStop": {
                        if (dlnaStreamer != null) {
                            dlnaStreamer.stop();
                            dlnaStreamer = null;
                        }
                        // 投屏结束，前台服务也要跟着退，否则通知栏会一直挂着
                        DlnaForegroundService.stop(MainActivity.this);
                        result.success(true);
                        break;
                    }
                    case "dlnaStatus": {
                        boolean running = dlnaStreamer != null && dlnaStreamer.isRunning();
                        if (!running) {
                            result.success("0||||");
                            break;
                        }
                        // 格式：运行中|接收端名|流地址|音频状态|运行统计
                        // 音频状态："a" 表示有音频，"e:<原因>" 表示没音频及原因
                        // 运行统计见 DlnaStreamer.getStats()
                        String audio = dlnaStreamer.hasAudio()
                                ? "a"
                                : "e:" + dlnaStreamer.getAudioError();
                        result.success("1|" + dlnaStreamer.getTargetName()
                                + "|" + dlnaStreamer.getMediaUrl() + "|" + audio
                                + "|" + dlnaStreamer.getStats());
                        break;
                    }
                    case "stop":
                        // 真正停服：停前台服务（释放 MediaProjection 与 mDNS），
                        // 再让 native 主循环收到 shutdown 事件退出并释放端口。
                        Log.i("SunshineMain", "stop: stopping server");
                        try {
                            NativeBridge.stop();
                        } catch (Exception e) {
                            Log.w("SunshineMain", "NativeBridge.stop failed", e);
                        }
                        try {
                            stopService(new Intent(MainActivity.this, ScreenCaptureService.class));
                        } catch (Exception e) {
                            Log.w("SunshineMain", "stopService failed", e);
                        }
                        result.success(true);
                        break;
                    case "serverStatus":
                        // 返回 "运行中|客户端数"，UI 每秒轮询刷新指示灯
                        result.success(
                                (NativeBridge.isServerRunning() ? "1" : "0")
                                        + "|" + NativeBridge.getClientCount());
                        break;
                    case "start":
                        // 若服务已在运行：先停服再走启动流程，保证重连时是干净状态
                        if (ScreenCaptureService.isRunning) {
                            Log.i("SunshineMain", "start: service running, stopping first for clean restart");
                            try { stopService(new Intent(MainActivity.this, ScreenCaptureService.class)); } catch (Exception e) { }
                            try { Thread.sleep(1000); } catch (InterruptedException ie) { }
                        }
                        if (isRequestingProjection) {
                            Toast.makeText(MainActivity.this, "\u6b63\u5728\u8bf7\u6c42\u5f55\u5c4f\u6743\u9650\u2026", Toast.LENGTH_SHORT).show();
                            result.success("requesting");
                            break;
                        }
                        isRequestingProjection = true;
                        // RECORD_AUDIO is a dangerous permission: it must be
                        // granted at runtime or AudioPlaybackCapture is rejected
                        // by the audio policy and the stream ends up silent.
                        if (ContextCompat.checkSelfPermission(MainActivity.this,
                                android.Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(MainActivity.this,
                                    new String[]{android.Manifest.permission.RECORD_AUDIO},
                                    REQUEST_AUDIO_PERMISSION);
                            result.success("requesting");
                            break;
                        }
                        requestScreenCapture();
                        result.success(null);
                        break;
                    case "pin":
                        String pin = (String) call.arguments;
                        NativeBridge.submitPin(pin);
                        result.success(pin);
                        break;
                    case "rotationMode":
                        int mode = (int) call.arguments;
                        NativeBridge.setRotationMode(mode);
                        result.success(mode);
                        break;
                    case "getRotationMode":
                        result.success(NativeBridge.getRotationMode());
                        break;
                    case "address":
                        String address = MDNSHelper.getWifiIpAddress(getContext());
                        result.success(address);
                        break;
                }
            }
        });
    }

    /** 拉起系统录屏授权框。 */
    private void requestScreenCapture() {
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE);
    }

    /** 拉起 DLNA 投屏用的录屏授权框（独立请求码）。 */
    private void requestDlnaScreenCapture() {
        startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(), REQUEST_DLNA_PROJECTION);
    }

    /**
     * 等 DLNA 前台服务真正进入前台。
     *
     * startForegroundService() 是异步的，服务 onStartCommand 之前
     * 进程还不算「有 mediaProjection 前台服务」，此时创建 MediaProjection
     * 依然会被 Android 14 拒绝。这里轮询等待，超时也继续往下走
     * （让真正的错误在日志里暴露，而不是静默卡住）。
     */
    private void waitDlnaServiceForeground() {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            DlnaForegroundService svc = DlnaForegroundService.instance;
            if (svc != null) {
                Log.i("SunshineMain", "DLNA 前台服务已就绪");
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Log.w("SunshineMain", "DLNA 前台服务未在 3 秒内就绪，继续尝试");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // DLNA 的录音权限：不管给不给都继续走录屏授权，
        // 拒绝了只是没声音，视频不受影响。
        if (requestCode == REQUEST_DLNA_AUDIO_PERMISSION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.i("SunshineMain", "DLNA RECORD_AUDIO " + (granted ? "granted" : "denied"));
            if (pendingDlnaRenderer != null) {
                requestDlnaScreenCapture();
            } else {
                isRequestingProjection = false;
            }
            return;
        }

        if (requestCode != REQUEST_AUDIO_PERMISSION) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            Log.i("SunshineMain", "RECORD_AUDIO granted");
        } else {
            // Video still works without audio, so keep going and tell the user.
            Log.w("SunshineMain", "RECORD_AUDIO denied, streaming will be silent");
            Toast.makeText(MainActivity.this,
                    "\u672a\u6388\u4e88\u5f55\u97f3\u6743\u9650\uff0c\u5c06\u4ee5\u65e0\u58f0\u97f3\u65b9\u5f0f\u4e32\u6d41",
                    Toast.LENGTH_LONG).show();
        }
        // Continue the start flow: the screen capture dialog is next.
        requestScreenCapture();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_DLNA_PROJECTION) {
            isRequestingProjection = false;
            if (resultCode != Activity.RESULT_OK || data == null) {
                Toast.makeText(this, "投屏授权失败", Toast.LENGTH_SHORT).show();
                pendingDlnaRenderer = null;
                return;
            }
            if (pendingDlnaRenderer == null) {
                return;
            }
            final DlnaUpnp.Renderer renderer = pendingDlnaRenderer;
            final boolean lowLatency = pendingDlnaLowLatency;
            pendingDlnaRenderer = null;

            // 先把前台服务拉起来，再创建 MediaProjection。
            //
            // Android 14 起 getMediaProjection() 要求调用方已经处于
            // 「mediaProjection 类型的前台服务」上下文，否则直接 SecurityException。
            // 服务创建是异步的，所以在这里等它真正进入前台（最多 3 秒），
            // 拿不到就算了，让 DlnaStreamer 自己报错，至少错误信息是准确的。
            DlnaForegroundService.start(this);
            final int rc = resultCode;
            final Intent resultData = data;

            new Thread(() -> {
                waitDlnaServiceForeground();

                // 启动投屏可能耗时（创建编码器/绑端口/发 SOAP），本来就在后台线程
                if (dlnaStreamer != null) {
                    dlnaStreamer.stop();
                }
                DlnaStreamer streamer = new DlnaStreamer(MainActivity.this);
                String error = streamer.start(rc, resultData, renderer, lowLatency);
                if (error == null) {
                    dlnaStreamer = streamer;
                } else {
                    DlnaForegroundService.stop(MainActivity.this);
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show());
                }
            }, "DlnaStart").start();
            return;
        }

        if (requestCode == REQUEST_CODE) {
            isRequestingProjection = false;
        }
        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            Log.d("MainActivity", "onActivityResult: " + data);
            if (data == null) {
                // 录屏授权返回的 Intent 为空（"不再显示"自动授权场景），无法创建 MediaProjection
                Toast.makeText(MainActivity.this, "\u5f55\u5c4f\u6743\u9650\u83b7\u53d6\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5", Toast.LENGTH_LONG).show();
                return;
            }
            Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }
    }
}
