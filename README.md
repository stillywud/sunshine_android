# sunshine_android

**移植的源代码**

- https://gitee.com/connect-screen/connect-screen#https://gitee.com/link?target=https%3A%2F%2Fgithub.com%2FMagicianGuo%2FAndroid-SettingTools

我偶然从屏易联中发现它集成了 Sunshine 服务端，但是找个功能混在了它本身的一些功能内，单独将这部分提取出来也有非常大的价值

可以让 Android 设备运行 Sunshine 的服务端，然后可以通过任意的 Moonlight 连接

它的延迟等应该是要比 scrcpy 更低的，更适合安卓串流安卓来玩游戏

## Flutter

使用这个框架并不是为了跨平台，仅仅是因为开发效率更高，并且我对 Flutter 更熟练



## 一些总结

根据我之前对 Android 软解码的一些经验

我们使用 ffmpeg 在 jni 层需要操作的其实是 Surface 对应的 ANativeWindow

既然 ANativeWindow 可以拿来送显，也能通过 ANativeWindow 来获取渲染数据

因为 MediaCodec 有 CONFIGURE_FLAG_ENCODE 和 CONFIGURE_FLAG_DECODE 两种模式

有一个非常伟大的库为 scrcpy，我想同时推进这样的方式到 scrcpy，这样可以获得更极致的性能

因为 scrcpy 的录屏，以及推流都是在 java 中完成的，如果能在 C/C++ 层，那么服务端的部分就会更快




- 支持最低 API 26


## 接下来的工作

我个人的主要技术栈是 Flutter，我开源的绝大部分项目也都是这个框架，尝试编写 C/C++ 对我来说是不小的挑战

我大概会在接下来的两周完成这些工作

- 优化移植的 C++ 代码，添加更多的注释，以及更好的代码风格
- 支持事件控制，我准备对接 Shizuku，然后用我在 NeoDesktop(自己的一个项目) 的代码，下发事件到手机屏幕上
- 适配最新的 Sunshine 代码，我不太清楚这个工作量会有多大，但是我会尽力



'AMediaCodec_createInputSurface' is unavailable: introduced in Android 26
'ANativeWindow_toSurface' is unavailable: introduced in Android 26
'AMediaCodec_setParameters' is unavailable: introduced in Android 26

这几个关键的 api 都只在 Android 26 之后才有，这对应 Android 8

如果要 Sunshine 支持更低的版本，似乎只能从 Java 层来获取录屏的数据，然后通过 JNI 传递给 C++ 层



   I  Info: Client requested video configuration:
2025-03-16 06:04:01.835  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Resolution: 3840x2160
2025-03-16 06:04:01.836  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Frame rate: 144 fps
2025-03-16 06:04:01.836  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Video format: H.264
2025-03-16 06:04:01.836  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Bitrate: 118988 kbps
2025-03-16 06:04:01.837  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Chroma sampling: YUV 4:2:0
2025-03-16 06:04:01.837  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Dynamic range: SDR (8-bit)
2025-03-16 06:04:01.837  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Encoder color space mode: 0x0
2025-03-16 06:04:01.838  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Slices per frame: 1
2025-03-16 06:04:01.838  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Number of reference frames: 1
2025-03-16 06:04:01.838  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Intra refresh: Disabled
2025-03-16 06:04:01.839  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info: Colorspace configuration:
2025-03-16 06:04:01.839  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Colorspace: Rec.601
2025-03-16 06:04:01.839  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Bit depth: 8-bit
2025-03-16 06:04:01.840  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - Color range: Limited (16-235)
2025-03-16 06:04:01.840  7802-7901  Sunshine                com.nightmare.sunshine_android       I  Info:   - HDR mode: Disabled

  Info: Client requested video configuration:
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Resolution: 3840x2160
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Frame rate: 144 fps
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Video format: H.264
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Bitrate: 78988 kbps
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Chroma sampling: YUV 4:2:0
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Dynamic range: SDR (8-bit)
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Encoder color space mode: 0x0
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Slices per frame: 1
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Number of reference frames: 1
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Intra refresh: Disabled
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info: Colorspace configuration:
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Colorspace: Rec.601
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Bit depth: 8-bit
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - Color range: Limited (16-235)
2025-03-16 06:05:29.802  8206-8325  Sunshine                com.nightmare.sunshine_android       I  Info:   - HDR mode: Disabled