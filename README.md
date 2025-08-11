# sunshine_android

**移植的源代码**

- https://gitee.com/connect-screen/connect-screen#https://gitee.com/link?target=https%3A%2F%2Fgithub.com%2FMagicianGuo%2FAndroid-SettingTools

我偶然从屏易联中发现它集成了 Sunshine 服务端，但是找个功能混在了它本身的一些功能内，单独将这部分提取出来也有非常大的价值

可以让 Android 设备运行 Sunshine 的服务端，然后可以通过任意的 Moonlight 连接

它的延迟等应该是要比 scrcpy 更低的，更适合安卓串流安卓来玩游戏

## Flutter

使用这个框架并不是为了跨平台，仅仅是因为开发效率更高，并且我对 Flutter 更熟练



为了加快编译速度，需要提供以下url
http://127.0.0.1/boost-1.86.0-cmake.tar.xz

http://127.0.0.1/prebuilts/openssl/3.3.2/arm64-v8a-android.tar.gz


这些文件放置在android/app/libs下