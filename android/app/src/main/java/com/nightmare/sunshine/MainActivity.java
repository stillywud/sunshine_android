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


public class MainActivity extends FlutterActivity {
    private static final int REQUEST_CODE = 1000;
    private static final int REQUEST_AUDIO_PERMISSION = 1001;
    private MediaProjectionManager mediaProjectionManager;
    public static Context context;
    // 录屏权限请求中标记：防止连点重复弹权限框
    private static boolean isRequestingProjection = false;

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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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