#include <jni.h>
#include <string>
#include "logging.h"
#include "config.h"
#include "nvhttp.h"
#include "globals.h"
#include "sunshine.h"
#include "stream.h"
#include "rtsp.h"
#include "audio.h"
#include "moonlight-common-c/src/input.h"
#include "video_colorspace.h"

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <boost/endian/buffers.hpp>

#include <chrono>
#include <condition_variable>
#include <atomic>
#include <mutex>
#include <thread>
#include <vector>

#include "gl_renderer.h"

using namespace std::literals;

extern "C" {

static std::unique_ptr<logging::deinit_t> deinit;
static JavaVM *jvm = nullptr;
static JNIEnv *g_env = nullptr;
static jclass sunshineServerClass = nullptr;
static audio::sample_queue_t samples = nullptr;

// 服务端是否在运行。start() 进入时置 true，主循环退出时置 false，
// 供 stop() 与 UI 状态轮询读取。
static std::atomic<bool> g_server_running {false};

// 当前连上来的客户端数量。由 stream.cpp 在会话建立/销毁时增减。
static std::atomic<int> g_client_count {0};

} // extern "C"

// stream.cpp 是 C++ 编译单元，这两个函数必须用 C++ 链接名，不能放进
// extern "C" 块里，否则链接阶段会报 undefined symbol。
void client_count_increment() {
    g_client_count.fetch_add(1);
}

void client_count_decrement() {
    g_client_count.fetch_sub(1);
}

extern "C" {

// 声明全局变量来存储音频录制状态
static std::thread audioRecordingThread;

// 采集帧到达通知：SurfaceTexture 回调 raise，渲染循环 consume。
// 用计数信号量而非布尔标志：回调可能连续触发多次，布尔标志会丢失通知。
static std::mutex g_frame_mutex;
static std::condition_variable g_frame_cv;
static int g_frame_pending = 0;

static void signal_capture_frame() {
    {
        std::lock_guard<std::mutex> lock(g_frame_mutex);
        if (g_frame_pending < 4) {
            g_frame_pending++;
        }
    }
    g_frame_cv.notify_one();
}

/**
 * 等待下一帧，最多 @p timeout。
 * @return true 表示有新帧可 update，false 表示超时（调用方应检查 shutdown）。
 */
static bool wait_capture_frame(std::chrono::milliseconds timeout) {
    std::unique_lock<std::mutex> lock(g_frame_mutex);
    if (g_frame_cv.wait_for(lock, timeout, [] { return g_frame_pending > 0; })) {
        g_frame_pending--;
        return true;
    }
    return false;
}

/// Create a Java Integer object
jobject createJavaInt(JNIEnv *env, int value) {
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID integerConstructor = env->GetMethodID(integerClass, "<init>", "(I)V");
    return env->NewObject(integerClass, integerConstructor, value);
}
/// 一个封装好的函数，用于调用 Java 方法
/// A helper function to call Java methods
void invokeJavaFunction(
        const char *name,
        const char *sig
        ...
) {
    if (jvm == nullptr) {
        BOOST_LOG(error) << "JVM is null!"sv;
        return;
    }

    // Do not blindly Attach/Detach: the calling thread is often already
    // attached (and on Adreno under ARM translation, detaching it breaks all
    // later JNI calls from that thread). Only attach when we actually own it.
    JNIEnv *env = nullptr;
    bool attached_here = false;
    if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            BOOST_LOG(error) << "Cannot attach java thread!"sv;
            return;
        }
        attached_here = true;
    }

    jmethodID method = env->GetStaticMethodID(sunshineServerClass, name, sig);
    if (method == nullptr) {
        BOOST_LOG(error) << "Cannot find method "sv << name << " with signature "sv << sig;
        if (attached_here) {
            jvm->DetachCurrentThread();
        }
        return;
    }
    va_list args;
    va_start(args, sig);
    env->CallStaticVoidMethodV(sunshineServerClass, method, args);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    va_end(args);
    if (attached_here) {
        jvm->DetachCurrentThread();
    }
}
/// Create a Java Double object
jobject createJavaDouble(JNIEnv *env, double value) {
    jclass doubleClass = env->FindClass("java/lang/Double");
    jmethodID doubleConstructor = env->GetMethodID(doubleClass, "<init>", "(D)V");
    return env->NewObject(doubleClass, doubleConstructor, value);
}

jobject createHashMap(JNIEnv *env) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapConstructor = env->GetMethodID(hashMapClass, "<init>", "()V");
    return env->NewObject(hashMapClass, hashMapConstructor);
}

/// 将 C 中 的 int、double、std::string 转换为 Java 中的 Integer、Double、String
/// Convert int, double, and std::string in C to Integer, Double, and String in Java
jobject convertToJavaObject(JNIEnv *env, const std::any &value) {
    if (value.type() == typeid(int)) {
        return createJavaInt(env, std::any_cast<int>(value));
    } else if (value.type() == typeid(double)) {
        return createJavaDouble(env, std::any_cast<double>(value));
    } else {
        return env->NewStringUTF(std::any_cast<std::string>(value).c_str());
    }
}
void putValueInJavaHashMap(
        JNIEnv *env,
        jobject hashMap,
        const std::string &key,
        const std::any &value
) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    const char *sig = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", sig);

    jstring javaKey = env->NewStringUTF(key.c_str());
    jobject javaValue = convertToJavaObject(env, value);

    env->CallObjectMethod(hashMap, putMethod, javaKey, javaValue);

    env->DeleteLocalRef(javaKey);
    env->DeleteLocalRef(javaValue);
}

jobject convertMapToJavaHashMap(JNIEnv *env, const std::map<std::string, std::any> &cppMap) {
    jobject map = createHashMap(env);

    // Iterate over the C++ map and put entries into the HashMap
    for (const auto &entry: cppMap) {
        putValueInJavaHashMap(env, map, entry.first, entry.second);
    }

    return map;
}


JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_NativeBridge_start(JNIEnv *env, jclass clazz) {
    if (g_server_running.exchange(true)) {
        // The native server owns process-global state (mail bus, UDP broadcast
        // listeners, task pool). Starting it twice would duplicate all of that,
        // so a second Start from the UI is a no-op until Stop is pressed.
        __android_log_print(ANDROID_LOG_INFO, "SunshineRot",
                            "native server already running, ignoring duplicate start");
        return;
    }

    env->GetJavaVM(&jvm);
    g_env = env;
    // Use the passed clazz parameter directly instead of looking up the class by name
    sunshineServerClass = (jclass) env->NewGlobalRef(clazz);
    if (sunshineServerClass == nullptr) {
        BOOST_LOG(error) << "Failed to create global reference for SunshineServer class"sv;
        g_server_running.store(false);
        return;
    }
    deinit = logging::init(1, "/dev/null");
    BOOST_LOG(info) << "Start sunshine server"sv;
    // log sunshineServerClass
    BOOST_LOG(info) << "sunshineServerClass: "sv << sunshineServerClass;
    // 每次启动从干净状态开始
    g_client_count.store(0);
    // 丢掉上一轮固定住的广播上下文（UDP 控制/视频/音频监听）。不丢的话
    // 复用那份已经 shutdown 的上下文，客户端会报 control stream 建立失败。
    stream::unpin_broadcast();
    mail::man = std::make_shared<safe::mail_raw_t>();
    task_pool.start(1);
    std::thread httpThread{nvhttp::start};
    rtsp_stream::rtpThread();
    httpThread.join();
    // nvhttp::start() 只在 shutdown 事件被 raise 之后才返回，此时 RTSP 线程也
    // 正在退出。这里清掉标志，让下一次 Start 能重新把监听拉起来。
    task_pool.stop();
    task_pool.join();
    g_client_count.store(0);
    g_server_running.store(false);
    __android_log_print(ANDROID_LOG_INFO, "SunshineRot",
                        "native server main loop exited, running=0");
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_NativeBridge_setSunshineName(JNIEnv *env, jclass clazz,
                                                         jstring sunshine_name) {
    const char *str = env->GetStringUTFChars(sunshine_name, nullptr);
    config::nvhttp.sunshine_name = str;
    env->ReleaseStringUTFChars(sunshine_name, str);
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_NativeBridge_setPkeyPath(JNIEnv *env, jclass clazz, jstring path) {
    const char *str = env->GetStringUTFChars(path, nullptr);
    config::nvhttp.pkey = str;
    env->ReleaseStringUTFChars(path, str);
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_NativeBridge_setCertPath(JNIEnv *env, jclass clazz, jstring path) {
    const char *str = env->GetStringUTFChars(path, nullptr);
    config::nvhttp.cert = str;
    env->ReleaseStringUTFChars(path, str);
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_NativeBridge_setFileStatePath(JNIEnv *env, jclass clazz,
                                                          jstring path) {
    const char *str = env->GetStringUTFChars(path, nullptr);
    config::nvhttp.file_state = str;
    env->ReleaseStringUTFChars(path, str);
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_NativeBridge_submitPin(JNIEnv *env, jclass clazz, jstring pin) {
    const char *pinStr = env->GetStringUTFChars(pin, nullptr);
    nvhttp::pin(pinStr, "some-moonlight");
    env->ReleaseStringUTFChars(pin, pinStr);
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_NativeBridge_onCaptureFrameAvailable(JNIEnv *env, jclass clazz) {
    signal_capture_frame();
}

/**
 * 停止 native 服务端。
 *
 * nvhttp::start() 的收尾是 `shutdown_event->view()` 之后停 https/tcp 并 join，
 * rtpThread() 的循环也以同一个 shutdown 事件为退出条件，所以 raise 这个事件
 * 就能让两个监听线程有序退出并释放端口。
 */
JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_NativeBridge_stop(JNIEnv *env, jclass clazz) {
    if (!g_server_running.load()) {
        __android_log_print(ANDROID_LOG_INFO, "SunshineRot",
                            "stop: server not running, nothing to do");
        return;
    }

    __android_log_print(ANDROID_LOG_INFO, "SunshineRot",
                        "stop: raising shutdown event");
    if (mail::man) {
        auto shutdown_event = mail::man->event<bool>(mail::shutdown);
        if (shutdown_event) {
            shutdown_event->raise(true);
        }
    }

    // 注意：这里刻意不调用 stream::unpin_broadcast()。
    //
    // 广播上下文（UDP 控制/视频/音频监听）在引用计数归零时会走 end_broadcast
    // 销毁套接字，而重启时 ref() 会重新构造它。但实测发现：unpin 之后第二次
    // start 虽然能重新绑定端口，RTSP 的 accept 循环却不再响应新的握手请求，
    // 客户端会一直卡在 "启动中 RTSP handshake"。广播上下文按设计就是进程级
    // 常驻的（见 stream.h 里 pin_broadcast 的注释），保持它常驻最稳。

    // 计数归零：会话线程随 shutdown 一起退出，之后的连接不会再建立。
    g_client_count.store(0);
    // 注意：这里不直接清 g_server_running，交给 start() 的主循环在
    // httpThread.join() 返回后复位，避免端口还没释放就允许再次 Start。
}

/** 服务端是否在运行（供 UI 状态轮询）。 */
JNIEXPORT jboolean JNICALL
Java_com_nightmare_sunshine_NativeBridge_isServerRunning(JNIEnv *env, jclass clazz) {
    return g_server_running.load() ? JNI_TRUE : JNI_FALSE;
}

/** 当前连上来的客户端数量（供 UI 显示）。 */
JNIEXPORT jint JNICALL
Java_com_nightmare_sunshine_NativeBridge_getClientCount(JNIEnv *env, jclass clazz) {
    return g_client_count.load();
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_SunshineServer_cleanup(JNIEnv *env, jclass clazz) {
    if (sunshineServerClass != nullptr) {
        env->DeleteGlobalRef(sunshineServerClass);
        sunshineServerClass = nullptr;
    }
    // 其他清理工作...
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_NativeBridge_postAudioSample(JNIEnv *env, jclass clazz,
                                                         jfloatArray audioData,
                                                         jint sampleCount) {
    // 获取 Java 浮点数组的元素
    jfloat *audioBuffer = env->GetFloatArrayElements(audioData, nullptr);
    if (audioBuffer == nullptr) {
        BOOST_LOG(error) << "无法获取音频数据缓冲区"sv;
        return;
    }

    // 将 Java 浮点数组转换为 std::vector<float>
    std::vector<float> audioSamples(audioBuffer, audioBuffer + sampleCount);

    // 将音频数据传递给 Sunshine 的音频处理系统
    if (samples) {
        samples->raise(std::move(audioSamples));
    } else {
        BOOST_LOG(error) << "音频样本队列未初始化"sv;
    }

    // 释放 Java 数组
    env->ReleaseFloatArrayElements(audioData, audioBuffer, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_NativeBridge_startAudioRecording(JNIEnv *env, jclass clazz,
                                                             jobject audioRecord,
                                                             jint framesPerPacket) {
    // 创建 AudioRecord 的全局引用，以便在线程中使用
    jobject globalAudioRecord = env->NewGlobalRef(audioRecord);
    if (globalAudioRecord == nullptr) {
        BOOST_LOG(error) << "无法创建 AudioRecord 的全局引用"sv;
        return;
    }

    // 获取 AudioRecord 类和方法 ID
    jclass audioRecordClass = env->GetObjectClass(globalAudioRecord);
    if (audioRecordClass == nullptr) {
        BOOST_LOG(error) << "无法获取 AudioRecord 类"sv;
        env->DeleteGlobalRef(globalAudioRecord);
        return;
    }
    jmethodID readMethod = env->GetMethodID(audioRecordClass, "read", "([FIII)I");

    if (!readMethod) {
        BOOST_LOG(error) << "无法获取 AudioRecord 方法"sv;
        env->DeleteGlobalRef(globalAudioRecord);
        return;
    }

    // 设置活动标志并启动录制线程
    audioRecordingThread = std::thread([globalAudioRecord, readMethod, framesPerPacket, env]() {
        JNIEnv *threadEnv;
        jint result = jvm->AttachCurrentThread(&threadEnv, nullptr);
        if (result != JNI_OK) {
            BOOST_LOG(error) << "无法将音频线程附加到 JVM"sv;
            return;
        }

        // 创建缓冲区
        jfloatArray buffer = threadEnv->NewFloatArray(framesPerPacket * 2); // 立体声，每帧两个通道

        try {
            while (true) {
                // 读取音频数据
                jint samplesRead = threadEnv->CallIntMethod(globalAudioRecord, readMethod, buffer,
                                                            0, framesPerPacket * 2, 0);

                if (samplesRead > 0) {
                    // 获取缓冲区数据
                    jfloat *audioData = threadEnv->GetFloatArrayElements(buffer, nullptr);
                    if (audioData) {
                        // 将音频数据转换为 std::vector<float>
                        std::vector<float> audioSamples(audioData, audioData + samplesRead);

                        // 将音频数据传递给 Sunshine 的音频处理系统
                        if (samples) {
                            samples->raise(std::move(audioSamples));
                        }

                        // 释放缓冲区
                        threadEnv->ReleaseFloatArrayElements(buffer, audioData, JNI_ABORT);
                    }
                }
            }
        } catch (...) {
            BOOST_LOG(error) << "音频录制过程中发生异常"sv;
        }

        // 分离线程
        jvm->DetachCurrentThread();
    });
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_NativeBridge_enableH265(JNIEnv *env, jclass clazz) {
    video::active_hevc_mode = 2;
}

}

AMediaFormat *createFormat(const video::config_t &config) {
    bool isHdr = false;
    video::sunshine_colorspace_t colorspace = colorspace_from_client_config(config, isHdr);
    AMediaFormat *format = AMediaFormat_new();
    bool isHevc = config.videoFormat == 1;
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, isHevc ? "video/hevc" : "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, config.width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, config.height);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, config.bitrate * 1000);
#if __ANDROID_API__ >= 28
    // AMEDIAFORMAT_KEY_OPERATING_RATE was introduced in API 28 (Android 9)
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_OPERATING_RATE, config.framerate);
#endif
#if __ANDROID_API__ >= 28
    // AMEDIAFORMAT_KEY_CAPTURE_RATE was introduced in API 28 (Android 9)
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_CAPTURE_RATE, config.framerate);
#endif
    // AMEDIAFORMAT_KEY_FRAME_RATE
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, config.framerate);
    // max-fps-to-encoder
    AMediaFormat_setInt32(format, "max-fps-to-encoder", config.framerate);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 100000);
    // COLOR_FormatSurface
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, 2130708361);
#if __ANDROID_API__ >= 28
    // AMEDIAFORMAT_KEY_LATENCY was introduced in API 28 (Android 9)
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_LATENCY, 0);
#endif
#if __ANDROID_API__ >= 28
    // AMEDIAFORMAT_KEY_COMPLEXITY was introduced in API 28 (Android 9)
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COMPLEXITY, 10);
#endif
    // max-bframes
    AMediaFormat_setInt32(format, "max-bframes", 0);
    if (isHevc) {
#if __ANDROID_API__ >= 28
        if (colorspace.bit_depth == 10) {
            // HEVCProfileMain10
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PROFILE, 2);
        } else {
            // HEVCProfileMain
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PROFILE, 1);
        }
        // HEVCMainTierLevel51
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_LEVEL, 65536);
#endif
    } else {
#if __ANDROID_API__ >= 28
        // HIGH profile
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PROFILE, 0x08);
        // AVCLevel42
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_LEVEL, 0x2000);
#endif
        AMediaFormat_setInt32(format, "vendor.qti-ext-enc-low-latency.enable", 1);
    }
#if __ANDROID_API__ >= 28
    switch (colorspace.colorspace) {
        case video::colorspace_e::rec601:
            // COLOR_STANDARD_BT601_NTSC
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_STANDARD, 4);
            break;
        case video::colorspace_e::rec709:
            // COLOR_STANDARD_BT709
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_STANDARD, 1);
            break;
        case video::colorspace_e::bt2020:
        case video::colorspace_e::bt2020sdr:
            // COLOR_STANDARD_BT2020
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_STANDARD, 6);
            break;
    }
    // 1=FULL, 2=LIMITED
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_RANGE, colorspace.full_range ? 1 : 2);
    if (isHdr) {
        // COLOR_TRANSFER_ST2084
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_TRANSFER, 6);
    } else {
        // COLOR_TRANSFER_SDR_VIDEO
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_TRANSFER, 3);
    }
#endif
    return format;
}


namespace sunshine_callbacks {


    void callJavaOnPinRequested() {
        invokeJavaFunction("onPinRequested", "()V");
    }

    void createVirtualDisplay(
            JNIEnv *env,
            jint width,
            jint height,
            jint frameRate,
            jint packetDuration,
            jobject surface,
            jboolean shouldMute
    ) {
        invokeJavaFunction(
                "createVirtualDisplay",
                "(IIIILandroid/view/Surface;Z)V",
                width,
                height,
                frameRate,
                packetDuration,
                surface,
                shouldMute
        );
    }

    void stopVirtualDisplay() {
        invokeJavaFunction("stopVirtualDisplay", "()V");
    }

    /** 读取屏幕逻辑尺寸与当前显示旋转角（供旋转管线决定源分辨率）。 */
    struct screen_info_t {
        int width = 0;
        int height = 0;
        int rotation = 0;
    };

    screen_info_t query_screen_info(JNIEnv *env) {
        screen_info_t info;
        jmethodID w = env->GetStaticMethodID(sunshineServerClass, "getScreenWidth", "()I");
        jmethodID h = env->GetStaticMethodID(sunshineServerClass, "getScreenHeight", "()I");
        jmethodID r = env->GetStaticMethodID(sunshineServerClass, "getDisplayRotation", "()I");
        if (w && h && r) {
            info.width = env->CallStaticIntMethod(sunshineServerClass, w);
            info.height = env->CallStaticIntMethod(sunshineServerClass, h);
            info.rotation = env->CallStaticIntMethod(sunshineServerClass, r);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        return info;
    }
    /** 读取用户选择的画面旋转模式：0=自动 1=左转90° 2=180° 3=右转90° */
    int query_rotation_mode(JNIEnv *env) {
        jmethodID m = env->GetStaticMethodID(sunshineServerClass, "getRotationMode", "()I");
        if (!m) {
            return 0;
        }
        int mode = env->CallStaticIntMethod(sunshineServerClass, m);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return 0;
        }
        return mode;
    }

    /** 读取画面填充方式：0=适应（留黑边），1=裁剪铺满（无黑边） */
    bool query_fill_mode(JNIEnv *env) {
        jmethodID m = env->GetStaticMethodID(sunshineServerClass, "getFillMode", "()I");
        if (!m) {
            return false;
        }
        int mode = env->CallStaticIntMethod(sunshineServerClass, m);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return false;
        }
        return mode != 0;
    }

    // ------------------------------------------------------------------
    // ASurfaceTexture is API 28+, but minSdk is 26. Resolve it at runtime so
    // the library still loads on older devices and the rotation path simply
    // stays disabled there.
    // ------------------------------------------------------------------
    struct ASurfaceTexture;

    typedef ASurfaceTexture *(*asurfacetexture_from_surfacetexture_fn)(JNIEnv *, jobject);
    typedef int (*asurfacetexture_attach_fn)(ASurfaceTexture *, uint32_t);
    typedef int (*asurfacetexture_detach_fn)(ASurfaceTexture *);
    typedef int (*asurfacetexture_update_fn)(ASurfaceTexture *);
    typedef void (*asurfacetexture_matrix_fn)(ASurfaceTexture *, float[16]);
    typedef void (*asurfacetexture_release_fn)(ASurfaceTexture *);

    struct surface_texture_api_t {
        asurfacetexture_from_surfacetexture_fn from_surface_texture = nullptr;
        asurfacetexture_attach_fn attach = nullptr;
        asurfacetexture_detach_fn detach = nullptr;
        asurfacetexture_update_fn update = nullptr;
        asurfacetexture_matrix_fn matrix = nullptr;
        asurfacetexture_release_fn release = nullptr;
        bool available() const {
            return from_surface_texture && attach && detach && update && matrix && release;
        }
    };

    const surface_texture_api_t &surface_texture_api() {
        static surface_texture_api_t api;
        static bool loaded = false;
        if (!loaded) {
            loaded = true;
            void *handle = dlopen("libandroid.so", RTLD_NOW);
            if (handle) {
                api.from_surface_texture = (asurfacetexture_from_surfacetexture_fn)
                        dlsym(handle, "ASurfaceTexture_fromSurfaceTexture");
                api.attach = (asurfacetexture_attach_fn)
                        dlsym(handle, "ASurfaceTexture_attachToGLContext");
                api.detach = (asurfacetexture_detach_fn)
                        dlsym(handle, "ASurfaceTexture_detachFromGLContext");
                api.update = (asurfacetexture_update_fn)
                        dlsym(handle, "ASurfaceTexture_updateTexImage");
                api.matrix = (asurfacetexture_matrix_fn)
                        dlsym(handle, "ASurfaceTexture_getTransformMatrix");
                api.release = (asurfacetexture_release_fn)
                        dlsym(handle, "ASurfaceTexture_release");
            }
            __android_log_print(ANDROID_LOG_INFO, "SunshineRot",
                                "ASurfaceTexture available=%d", api.available() ? 1 : 0);
        }
        return api;
    }


    void captureVideoLoop(void *channel_data, safe::mail_t mail, const video::config_t &config,
                          const audio::config_t &audioConfig) {
        JNIEnv *env = nullptr;
        bool attached_here = false;
        if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
            if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                BOOST_LOG(error) << "无法附加到 Java 线程"sv;
                return;
            }
            attached_here = true;
        }
        auto shutdown_event = mail->event<bool>(mail::shutdown);
        auto idr_events = mail->event<bool>(mail::idr);

        BOOST_LOG(info) << "Client requested video configuration:";
        // 分辨率
        BOOST_LOG(info) << "  - Resolution: " << config.width << "x" << config.height;
        // 帧率
        BOOST_LOG(info) << "  - Frame rate: " << config.framerate << " fps";
        // 视频格式
        const char *videoFormatStr = "Unknown";
        if (config.videoFormat == 0) videoFormatStr = "H.264";
        else if (config.videoFormat == 1) videoFormatStr = "HEVC";
        else if (config.videoFormat == 2) videoFormatStr = "AV1";
        BOOST_LOG(info) << "  - Video format: " << videoFormatStr;
        // 比特率
        BOOST_LOG(info) << "  - Bitrate: " << config.bitrate << " kbps";
        // 色度采样
        BOOST_LOG(info) << "  - Chroma sampling: " << (config.chromaSamplingType == 1 ? "YUV 4:4:4" : "YUV 4:2:0");
        // 动态范围
        BOOST_LOG(info) << "  - Dynamic range: " << (config.dynamicRange ? "HDR (10-bit)" : "SDR (8-bit)");
        // 编码器色彩空间模式
        BOOST_LOG(info) << "  - Encoder color space mode: 0x" << std::hex << config.encoderCscMode << std::dec;
        // 每帧切片数
        BOOST_LOG(info) << "  - Slices per frame: " << config.slicesPerFrame;
        // Max number of reference frames
        BOOST_LOG(info) << "  - Number of reference frames: " << config.numRefFrames;
        BOOST_LOG(info) << "  - Intra refresh: " << (config.enableIntraRefresh ? "Enabled" : "Disabled");

        // Get and log detailed colorspace information
        bool isHdr = false;
        video::sunshine_colorspace_t colorspace = colorspace_from_client_config(config, isHdr);
        BOOST_LOG(info) << "Colorspace configuration:";
        const char *colorspaceStr = "Unknown";
        if (colorspace.colorspace == video::colorspace_e::rec601) colorspaceStr = "Rec.601";
        else if (colorspace.colorspace == video::colorspace_e::rec709) colorspaceStr = "Rec.709";
        else if (colorspace.colorspace == video::colorspace_e::bt2020sdr) colorspaceStr = "BT.2020 SDR";
        else if (colorspace.colorspace == video::colorspace_e::bt2020) colorspaceStr = "BT.2020 HDR";
        BOOST_LOG(info) << "  - Colorspace: " << colorspaceStr;
        BOOST_LOG(info) << "  - Bit depth: " << colorspace.bit_depth << "-bit";
        BOOST_LOG(info) << "  - Color range: " << (colorspace.full_range ? "Full (0-255)" : "Limited (16-235)");
        BOOST_LOG(info) << "  - HDR mode: " << (isHdr ? "Enabled" : "Disabled");

        AMediaFormat *format = createFormat(config);
#if __ANDROID_API__ >= 28
        int32_t colorStandard = 0, colorRange = 0, colorTransfer = 0;

        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_COLOR_STANDARD, &colorStandard);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_COLOR_RANGE, &colorRange);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_COLOR_TRANSFER, &colorTransfer);

        BOOST_LOG(info) << "Final media format color configuration:"sv;
        BOOST_LOG(info) << "  - COLOR_STANDARD: "sv << colorStandard;
        BOOST_LOG(info) << "  - COLOR_RANGE: "sv << colorRange << (colorRange == 1 ? " (FULL)" : " (LIMITED)");
        BOOST_LOG(info) << "  - COLOR_TRANSFER: "sv << colorTransfer;
#endif

        // Create encoder
        const char *mimeType = config.videoFormat == 1 ? "video/hevc" : "video/avc";
        AMediaCodec *codec = AMediaCodec_createEncoderByType(mimeType);
        if (!codec) {
            BOOST_LOG(error) << "Failed to create encoder";
            AMediaFormat_delete(format);
            return;
        }

        // Configure encoder
        media_status_t status = AMediaCodec_configure(codec, format, nullptr, nullptr,
                                                      AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        if (status != AMEDIA_OK) {
            BOOST_LOG(error) << "Failed to configure encoder, error code: " << status;
            AMediaCodec_delete(codec);
            AMediaFormat_delete(format);
            return;
        }

        // Get input Surface
        ANativeWindow *inputSurface;
        status = AMediaCodec_createInputSurface(codec, &inputSurface);
        if (status != AMEDIA_OK) {
            BOOST_LOG(error) << "Failed to create input Surface, error code: " << status;
            AMediaCodec_delete(codec);
            AMediaFormat_delete(format);
            return;
        }

        // Convert ANativeWindow to Java Surface object
        jobject javaSurface = ANativeWindow_toSurface(env, inputSurface);
        if (javaSurface == nullptr) {
            BOOST_LOG(error) << "Failed to convert ANativeWindow to Surface";
            ANativeWindow_release(inputSurface);
            AMediaCodec_delete(codec);
            AMediaFormat_delete(format);
            if (attached_here) jvm->DetachCurrentThread();
            return;
        }

        bool shouldMute = true;
        if (audioConfig.flags[audio::config_t::HOST_AUDIO]) {
            BOOST_LOG(info) << "Audio config: Sound will be played on host (Sunshine server)"sv;
            shouldMute = false;
        } else {
            BOOST_LOG(info) << "Audio config: Sound will be played on client (Moonlight)"sv;
        }

        // ------------------------------------------------------------------
        // Rotation pipeline
        //
        // MediaProjection mirrors the physical screen without letting us
        // rotate it. Creating the VirtualDisplay at the *client's* resolution
        // therefore letterboxes a portrait screen inside a landscape frame.
        //
        // Instead we capture at the source resolution into a SurfaceTexture,
        // then draw it rotated into the encoder's input surface with EGL.
        // ------------------------------------------------------------------
        screen_info_t screen = query_screen_info(env);
        if (screen.width <= 0 || screen.height <= 0) {
            screen.width = config.width;
            screen.height = config.height;
        }

        const int mode = query_rotation_mode(env);

        // mode: 0=default (no rotation), 1=left 90, 2=180, 3=right 90
        // rotation_degrees is applied to the sampled texture, and the observed
        // mapping on device is: 90 -> image turns clockwise, 270 -> counter-clockwise.
        // "向左旋转 90°" means the picture turns counter-clockwise.
        //
        // Mode 0 keeps the capture untouched: a portrait source is fitted into
        // the landscape encoder frame with black bars on both sides, matching
        // the original behaviour before rotation support existed.
        int rotation_degrees = 0;
        switch (mode) {
            case 1:
                rotation_degrees = 270; // 左转 90°
                break;
            case 2:
                rotation_degrees = 180;
                break;
            case 3:
                rotation_degrees = 90; // 右转 90°
                break;
            default:
                rotation_degrees = 0; // 默认：不旋转
                break;
        }

        const bool fill_mode = query_fill_mode(env);

        __android_log_print(ANDROID_LOG_INFO, "SunshineRot",
                            "screen=%dx%d displayRotation=%d mode=%d -> glRotation=%d "
                            "fill=%d encoder=%dx%d",
                            screen.width, screen.height, screen.rotation, mode,
                            rotation_degrees, fill_mode ? 1 : 0,
                            config.width, config.height);

        __android_log_print(ANDROID_LOG_INFO, "SunshineRot", "creating GL renderer...");
        gl_render::renderer_t renderer = gl_render::create(
                inputSurface,
                screen.width, screen.height,
                config.width, config.height,
                rotation_degrees,
                fill_mode);
        __android_log_print(ANDROID_LOG_INFO, "SunshineRot", "GL renderer created, valid=%d",
                            renderer.valid ? 1 : 0);

        // Note: eglMakeCurrent leaves the thread's JNI state intact as long as
        // we never call DetachCurrentThread on a thread we did not attach.
        // All JNI work below happens on this thread before gl_render::destroy().

        if (!renderer.valid) {
            BOOST_LOG(error) << "Failed to create GL renderer, falling back to direct surface"sv;
            createVirtualDisplay(env, config.width, config.height, 120,
                                 audioConfig.packetDuration, javaSurface, shouldMute);
        } else {
            // Create the capture SurfaceTexture from Java (so it has a valid
            // Looper-free object) and wrap it with ASurfaceTexture, which lets
            // us drive it purely from native without further JNI calls.
            __android_log_print(ANDROID_LOG_INFO, "SunshineRot", "creating capture surface...");
            jmethodID createCapture = env->GetStaticMethodID(
                    sunshineServerClass, "createCaptureSurface", "(II)[Ljava/lang/Object;");
            jobjectArray capture = createCapture
                                   ? (jobjectArray) env->CallStaticObjectMethod(
                            sunshineServerClass, createCapture,
                            (jint) screen.width, (jint) screen.height)
                                   : nullptr;

            jobject surfaceTexture = nullptr;
            jobject captureSurface = nullptr;
            jobject callbackThread = nullptr;
            if (capture != nullptr) {
                surfaceTexture = env->GetObjectArrayElement(capture, 0);
                captureSurface = env->GetObjectArrayElement(capture, 1);
                callbackThread = env->GetObjectArrayElement(capture, 2);
            }

            const surface_texture_api_t &stApi = surface_texture_api();
            ASurfaceTexture *asSurfaceTexture =
                    (surfaceTexture && stApi.available())
                    ? stApi.from_surface_texture(env, surfaceTexture) : nullptr;

            // Promote to global refs: the cleanup below runs after further JNI
            // calls, and local refs can be invalidated by EGL/thread state.
            jobject surfaceTextureGlobal =
                    surfaceTexture ? env->NewGlobalRef(surfaceTexture) : nullptr;
            jobject captureSurfaceGlobal =
                    captureSurface ? env->NewGlobalRef(captureSurface) : nullptr;
            jobject callbackThreadGlobal =
                    callbackThread ? env->NewGlobalRef(callbackThread) : nullptr;

            if (asSurfaceTexture == nullptr || captureSurface == nullptr) {
                __android_log_print(ANDROID_LOG_ERROR, "SunshineRot",
                                    "capture surface unavailable, using direct mirroring");
                gl_render::destroy(renderer);
                createVirtualDisplay(env, config.width, config.height, 120,
                                     audioConfig.packetDuration, javaSurface, shouldMute);
            } else {
                // Bind the capture texture to this EGL context. The context is
                // current on this thread, which is what attachToGLContext needs.
                int attachResult = stApi.attach(asSurfaceTexture, renderer.texture);
                __android_log_print(ANDROID_LOG_INFO, "SunshineRot",
                                    "attachToGLContext result=%d tex=%u",
                                    attachResult, (unsigned) renderer.texture);

                // The VirtualDisplay mirrors the source screen at its native
                // resolution into the SurfaceTexture, no letterboxing.
                invokeJavaFunction("createVirtualDisplay",
                                   "(IIIILandroid/view/Surface;Z)V",
                                   screen.width, screen.height, 120,
                                   audioConfig.packetDuration, captureSurfaceGlobal, shouldMute);

                status = AMediaCodec_start(codec);
                if (status != AMEDIA_OK) {
                    BOOST_LOG(error) << "Failed to start encoder, error code: "sv << status;
                    stApi.release(asSurfaceTexture);
                    env->DeleteLocalRef(javaSurface);
                    env->DeleteLocalRef(surfaceTexture);
                    env->DeleteLocalRef(captureSurface);
                    gl_render::destroy(renderer);
                    if (attached_here) jvm->DetachCurrentThread();
                    ANativeWindow_release(inputSurface);
                    AMediaCodec_delete(codec);
                    AMediaFormat_delete(format);
                    return;
                }

std::vector<uint8_t> codecConfigData;
                int64_t frameIndex = 0;
                long long frame_count = 0;
                bool first_frame_logged = false;
                auto last_frame_time = std::chrono::steady_clock::now();

                // Drop any frame notification left over from a previous session.
                {
                    std::lock_guard<std::mutex> lock(g_frame_mutex);
                    g_frame_pending = 0;
                }

                // Watchdog: updateTexImage blocks until the producer delivers a
                // frame. After a client disconnect the producer goes quiet, so a
                // helper thread tears the VirtualDisplay down to unblock it and
                // let the session be joined.
                std::atomic<bool> capture_done {false};
                std::thread watchdog([&]() {
                    while (!capture_done.load(std::memory_order_relaxed)) {
                        if (shutdown_event->peek()) {
                            __android_log_print(ANDROID_LOG_INFO, "SunshineRot",
                                                "shutdown while capturing, releasing VirtualDisplay");
                            stopVirtualDisplay();
                            break;
                        }
                        std::this_thread::sleep_for(std::chrono::milliseconds(100));
                    }
                });

                while (!shutdown_event->peek()) {
                    bool requested_idr_frame = false;
                    if (idr_events->peek()) {
                        requested_idr_frame = true;
                        idr_events->pop();
                    }
                    if (requested_idr_frame) {
                        AMediaFormat *params = AMediaFormat_new();
                        AMediaFormat_setInt32(params, "request-sync", 0);
                        if (AMediaCodec_setParameters(codec, params) == AMEDIA_OK) {
                            BOOST_LOG(info) << "IDR frame requested";
                        }
                        AMediaFormat_delete(params);
                    }

                    float tex_matrix[16] = {
                            1, 0, 0, 0,
                            0, 1, 0, 0,
                            0, 0, 1, 0,
                            0, 0, 0, 1
                    };
                    if (!first_frame_logged) {
                        __android_log_print(ANDROID_LOG_INFO, "SunshineRot",
                                            "first updateTexImage call");
                        first_frame_logged = true;
                    }
                    int updateResult = stApi.update(asSurfaceTexture);
                    if (updateResult == 0) {
                        stApi.matrix(asSurfaceTexture, tex_matrix);
                        frame_count++;
                        if (frame_count % 60 == 1) {
                            __android_log_print(ANDROID_LOG_INFO, "SunshineRot",
                                                "captured %lld frames", (long long) frame_count);
                        }
                    }

                    gl_render::draw(renderer, tex_matrix);

                    // Drain whatever the encoder produced; the GL swap above
                    // supplies the frames.
                    for (;;) {
                        AMediaCodecBufferInfo bufferInfo;
                        ssize_t outputBufferIndex =
                                AMediaCodec_dequeueOutputBuffer(codec, &bufferInfo, 0);
                        if (outputBufferIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                            break;
                        }
                        if (outputBufferIndex < 0) {
                            break;
                        }

                        size_t out_size = 0;
                        uint8_t *buffer =
                                AMediaCodec_getOutputBuffer(codec, outputBufferIndex, &out_size);
                        if (buffer != nullptr) {
                            if (bufferInfo.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
                                codecConfigData.assign(buffer, buffer + bufferInfo.size);
                                BOOST_LOG(info) << "Saved codec config, size: "sv
                                                << codecConfigData.size();
                            } else if (bufferInfo.size > 0) {
                                bool isKeyFrame =
                                        (bufferInfo.flags & AMEDIACODEC_BUFFER_FLAG_KEY_FRAME) != 0;
                                frameIndex++;
                                if (frameIndex <= 3 || frameIndex % 120 == 0) {
                                    __android_log_print(ANDROID_LOG_INFO, "SunshineRot",
                                                        "encoded frame #%lld size=%d key=%d "
                                                        "flags=0x%x",
                                                        (long long) frameIndex,
                                                        (int) bufferInfo.size,
                                                        isKeyFrame ? 1 : 0,
                                                        bufferInfo.flags);
                                }
                                std::vector<uint8_t> frameData;
                                if (isKeyFrame && !codecConfigData.empty()) {
                                    frameData.reserve(codecConfigData.size() + bufferInfo.size);
                                    frameData.insert(frameData.end(), codecConfigData.begin(),
                                                     codecConfigData.end());
                                }
                                frameData.insert(frameData.end(), buffer,
                                                 buffer + bufferInfo.size);
                                stream::postFrame(std::move(frameData), frameIndex, isKeyFrame,
                                                  channel_data);
                            }
                        }
                        AMediaCodec_releaseOutputBuffer(codec, outputBufferIndex, false);
                    }

                    // Pace the capture at the client frame rate.
                    auto frame_duration = std::chrono::microseconds(
                            1000000 / (config.framerate > 0 ? config.framerate : 60));
                    last_frame_time += frame_duration;
                    auto now = std::chrono::steady_clock::now();
                    if (last_frame_time > now) {
                        std::this_thread::sleep_until(last_frame_time);
                    } else {
                        last_frame_time = now;
                    }
                }

                // Release capture resources. ASurfaceTexture_detachFromGLContext
                // must run while our EGL context is still current.
                capture_done.store(true, std::memory_order_relaxed);
                if (watchdog.joinable()) {
                    watchdog.join();
                }
                stApi.detach(asSurfaceTexture);
                stApi.release(asSurfaceTexture);

                // Do the Java-side cleanup *before* destroying the EGL context:
                // eglTerminate resets the thread's JNI TLS state on Adreno, and
                // any JNI call after that aborts the process.
                invokeJavaFunction("releaseCaptureSurface",
                                   "(Landroid/graphics/SurfaceTexture;Landroid/view/Surface;Landroid/os/HandlerThread;)V",
                                   surfaceTextureGlobal, captureSurfaceGlobal,
                                   callbackThreadGlobal);
                stopVirtualDisplay();

                gl_render::destroy(renderer);

                if (surfaceTextureGlobal) env->DeleteGlobalRef(surfaceTextureGlobal);
                if (captureSurfaceGlobal) env->DeleteGlobalRef(captureSurfaceGlobal);
                if (callbackThreadGlobal) env->DeleteGlobalRef(callbackThreadGlobal);
                if (surfaceTexture) env->DeleteLocalRef(surfaceTexture);
                if (captureSurface) env->DeleteLocalRef(captureSurface);
                if (callbackThread) env->DeleteLocalRef(callbackThread);
                AMediaCodec_stop(codec);
                ANativeWindow_release(inputSurface);
                AMediaCodec_delete(codec);
                AMediaFormat_delete(format);
                env->DeleteLocalRef(javaSurface);
                if (attached_here) jvm->DetachCurrentThread();
                return;
            }
        }

        // Fallback path (GL renderer unavailable): the VirtualDisplay already
        // feeds the encoder's input surface directly.

        // Start encoder
        status = AMediaCodec_start(codec);
        if (status != AMEDIA_OK) {
            BOOST_LOG(error) << "Failed to start encoder, error code: "sv << status;
            env->DeleteLocalRef(javaSurface);
            if (attached_here) jvm->DetachCurrentThread();
            ANativeWindow_release(inputSurface);
            AMediaCodec_delete(codec);
            AMediaFormat_delete(format);
            return;
        }

        // Initialize variables for encoding loop
        std::vector<uint8_t> codecConfigData;
        int64_t frameIndex = 0;

        // Encoding loop
        while (!shutdown_event->peek()) {
            bool requested_idr_frame = false;
            if (idr_events->peek()) {
                requested_idr_frame = true;
                idr_events->pop();
            }

            if (requested_idr_frame) {
                // Request IDR frame
                AMediaFormat *params = AMediaFormat_new();
                AMediaFormat_setInt32(params, "request-sync", 0);
                media_status_t status = AMediaCodec_setParameters(codec, params);
                if (status != AMEDIA_OK) {
                    BOOST_LOG(warning) << "Failed to request IDR frame, error code: " << status;
                } else {
                    BOOST_LOG(info) << "IDR frame requested";
                }
                AMediaFormat_delete(params);
            }

            // Dequeue output buffer with 1 second timeout
            AMediaCodecBufferInfo bufferInfo;
            ssize_t outputBufferIndex = AMediaCodec_dequeueOutputBuffer(codec, &bufferInfo, -1);

            if (outputBufferIndex >= 0) {
                // Valid output buffer received
                size_t bufferSize = bufferInfo.size;
                uint8_t *buffer = nullptr;
                size_t out_size = 0;

                // Get buffer data
                buffer = AMediaCodec_getOutputBuffer(codec, outputBufferIndex, &out_size);
                if (buffer != nullptr) {
                    if (bufferInfo.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
                        // Codec configuration data (SPS/PPS)
                        BOOST_LOG(info) << "Received codec configuration data, size: " << bufferSize;
                        codecConfigData.assign(buffer, buffer + bufferSize);
                        BOOST_LOG(info) << "Saved complete codec configuration data, size: " << codecConfigData.size();
                    } else {
                        // Regular encoded frame
                        bool isKeyFrame = (bufferInfo.flags & AMEDIACODEC_BUFFER_FLAG_KEY_FRAME) != 0;
                        BOOST_LOG(verbose) << "Received " << (isKeyFrame ? "key frame" : "regular frame") << ", size: " << bufferSize;
                        frameIndex++;

                        if (isKeyFrame) {
                            // For key frames, prepend codec configuration data
                            if (!codecConfigData.empty()) {
                                std::vector<uint8_t> frameData;
                                frameData.insert(frameData.end(), codecConfigData.begin(), codecConfigData.end());
                                frameData.insert(frameData.end(), buffer, buffer + bufferSize);
                                BOOST_LOG(verbose) << "Sending key frame (with config data), total size: " << frameData.size();
                                stream::postFrame(std::move(frameData), frameIndex, true, channel_data);
                            } else {
                                BOOST_LOG(error) << "No codec configuration data, cannot send complete key frame";
                            }
                        } else {
                            std::vector<uint8_t> frameData;
                            frameData.insert(frameData.end(), buffer, buffer + bufferSize);
                            stream::postFrame(std::move(frameData), frameIndex, false, channel_data);
                        }
                    }
                }

                // Release output buffer
                AMediaCodec_releaseOutputBuffer(codec, outputBufferIndex, false);
            } else if (outputBufferIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                BOOST_LOG(verbose) << "Encoder timeout, waiting for output buffer";
                continue;
            } else if (outputBufferIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                // Output format changed
                AMediaFormat *format = AMediaCodec_getOutputFormat(codec);
                BOOST_LOG(info) << "Encoder output format changed";
                AMediaFormat_delete(format);
            } else if (outputBufferIndex == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
                // Output buffers changed
                BOOST_LOG(info) << "Encoder output buffers changed";
                // 在较新的 NDK 版本中，这个事件通常可以忽略，因为 AMediaCodec_getOutputBuffer 会自动处理缓冲区变化
            } else {
                // Error
                BOOST_LOG(error) << "Encoder error, error code: " << outputBufferIndex;
                break;
            }
        }

        stopVirtualDisplay();
        // 停止编码器
        AMediaCodec_stop(codec);

        // 清理资源
        ANativeWindow_release(inputSurface);
        AMediaCodec_delete(codec);
        AMediaFormat_delete(format);

        // 清理 Java Surface 引用
        if (attached_here) jvm->DetachCurrentThread();
    }

    void captureAudioLoop(void *channel_data, safe::mail_t mail, const audio::config_t &config) {
        auto session_samples = std::make_shared<audio::sample_queue_t::element_type>(30);
        samples = session_samples; // 供 postAudioSample 投递

        auto shutdown_event = mail->event<bool>(mail::shutdown);

        // The encoder thread blocks in samples->pop() until a packet arrives.
        // After the client disconnects no audio packets come in, so the session
        // can never be joined unless someone wakes the queue.
        std::thread stopper([shutdown_event, session_samples]() {
            while (!shutdown_event->peek()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
            if (session_samples) {
                session_samples->stop();
            }
        });

        encodeThread(session_samples, config, channel_data);

        if (stopper.joinable()) {
            stopper.join();
        }
    }

    float from_netfloat(netfloat f) {
        return boost::endian::endian_load<float, sizeof(float), boost::endian::order::little>(f);
    }

    void callJavaOnTouch(SS_TOUCH_PACKET *touchPacket) {
        if (jvm == nullptr) {
            BOOST_LOG(error) << "JVM 指针为空"sv;
            return;
        }

        if (sunshineServerClass == nullptr) {
            BOOST_LOG(error) << "SunshineServer 类引用为空"sv;
            return;
        }

        JNIEnv *env;
        jint result = jvm->AttachCurrentThread(&env, nullptr);
        if (result != JNI_OK) {
            BOOST_LOG(error) << "无法附加到 Java 线程"sv;
            return;
        }

        jmethodID handleTouchPacketMethod = env->GetStaticMethodID(sunshineServerClass,
                                                                   "handleTouchPacket",
                                                                   "(IIIFFFFF)V");
        if (handleTouchPacketMethod == nullptr) {
            BOOST_LOG(error) << "找不到 handleTouchPacket 方法"sv;
            jvm->DetachCurrentThread();
            return;
        }

        // 从 SS_TOUCH_PACKET 结构体中提取字段并传递给 Java 方法
        env->CallStaticVoidMethod(sunshineServerClass, handleTouchPacketMethod,
                                  static_cast<int>(touchPacket->eventType),
                                  static_cast<int>(touchPacket->rotation),
                                  static_cast<int>(touchPacket->pointerId),
                                  from_netfloat(touchPacket->x),
                                  from_netfloat(touchPacket->y),
                                  from_netfloat(touchPacket->pressureOrDistance),
                                  from_netfloat(touchPacket->contactAreaMajor),
                                  from_netfloat(touchPacket->contactAreaMinor));

        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }

        jvm->DetachCurrentThread();
    }
}