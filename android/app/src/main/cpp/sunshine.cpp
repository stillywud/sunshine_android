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
#include <boost/endian/buffers.hpp>

using namespace std::literals;

extern "C" {

static std::unique_ptr<logging::deinit_t> deinit;
static JavaVM *jvm = nullptr;
static JNIEnv *g_env = nullptr;
static jclass sunshineServerClass = nullptr;
static audio::sample_queue_t samples = nullptr;

// 声明全局变量来存储音频录制状态
static std::thread audioRecordingThread;

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
    JNIEnv *env;
    jint result = jvm->AttachCurrentThread(&env, nullptr);
    if (result != JNI_OK) {
        BOOST_LOG(error) << "Cannot attach java thread!"sv;
        return;
    }

    jmethodID method = env->GetStaticMethodID(sunshineServerClass, name, sig);
    if (method == nullptr) {
        BOOST_LOG(error) << "Cannot find method "sv << name << " with signature "sv << sig;
        jvm->DetachCurrentThread();
        return;
    }
    va_list args;
    va_start(args, sig);
    env->CallStaticVoidMethodV(sunshineServerClass, method, args);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    jvm->DetachCurrentThread();
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


void passMapToJava(JNIEnv *env, const std::map<std::string, std::any> &cppMap) {

    jobject map = createHashMap(env);

    // Iterate over the C++ map and put entries into the HashMap
    for (const auto &entry: cppMap) {
        putValueInJavaHashMap(env, map, entry.first, entry.second);
    }

    // Call the Java method with the HashMap
    jclass javaClass = env->FindClass("com/nightmare/sunshine/SunshineServer");
    jmethodID javaMethod = env->GetStaticMethodID(javaClass, "receiveMap",
                                                  "(Ljava/util/HashMap;)V");
    env->CallStaticVoidMethod(javaClass, javaMethod, map);

    // Clean up
    env->DeleteLocalRef(map);
    env->DeleteLocalRef(javaClass);
}
JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_SunshineServer_start(JNIEnv *env, jclass clazz) {
    env->GetJavaVM(&jvm);
    g_env = env;
    jclass localClass = env->FindClass("com/nightmare/sunshine/SunshineServer");
    if (localClass != nullptr) {
        sunshineServerClass = (jclass) env->NewGlobalRef(localClass);
        env->DeleteLocalRef(localClass);
    } else {
        BOOST_LOG(error) << "Cannot find SunshineServer class on start"sv;
    }
    deinit = logging::init(1, "/dev/null");
    BOOST_LOG(info) << "Start sunshine server"sv;
    mail::man = std::make_shared<safe::mail_raw_t>();
    task_pool.start(1);
    std::thread httpThread{nvhttp::start};
    rtsp_stream::rtpThread();
    httpThread.join();
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_SunshineServer_setSunshineName(JNIEnv *env, jclass clazz,
                                                           jstring sunshine_name) {
    const char *str = env->GetStringUTFChars(sunshine_name, nullptr);
    config::nvhttp.sunshine_name = str;
    env->ReleaseStringUTFChars(sunshine_name, str);
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_SunshineServer_setPkeyPath(JNIEnv *env, jclass clazz, jstring path) {
    const char *str = env->GetStringUTFChars(path, nullptr);
    config::nvhttp.pkey = str;
    env->ReleaseStringUTFChars(path, str);
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_SunshineServer_setCertPath(JNIEnv *env, jclass clazz, jstring path) {
    const char *str = env->GetStringUTFChars(path, nullptr);
    config::nvhttp.cert = str;
    env->ReleaseStringUTFChars(path, str);
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_SunshineServer_setFileStatePath(JNIEnv *env, jclass clazz,
                                                            jstring path) {
    const char *str = env->GetStringUTFChars(path, nullptr);
    config::nvhttp.file_state = str;
    env->ReleaseStringUTFChars(path, str);
}

JNIEXPORT void JNICALL
Java_com_nightmare_sunshine_SunshineServer_submitPin(JNIEnv *env, jclass clazz, jstring pin) {
    const char *pinStr = env->GetStringUTFChars(pin, nullptr);
    nvhttp::pin(pinStr, "some-moonlight");
    env->ReleaseStringUTFChars(pin, pinStr);
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
Java_com_nightmare_sunshine_SunshineServer_postAudioSample(JNIEnv *env, jclass clazz,
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
Java_com_nightmare_sunshine_SunshineServer_startAudioRecording(JNIEnv *env, jclass clazz,
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
Java_com_nightmare_sunshine_SunshineServer_enableH265(JNIEnv *env, jclass clazz) {
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
    // AMEDIAFORMAT_KEY_OPERATING_RATE
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_OPERATING_RATE, config.framerate);
    // AMEDIAFORMAT_KEY_CAPTURE_RATE
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_CAPTURE_RATE, config.framerate);
    // AMEDIAFORMAT_KEY_FRAME_RATE
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, config.framerate);
    // max-fps-to-encoder
    AMediaFormat_setInt32(format, "max-fps-to-encoder", config.framerate);
    // AMEDIAFORMAT_KEY_I_FRAME_INTERVAL
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 100000);
    // AMEDIAFORMAT_KEY_COLOR_FORMAT
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, 2130708361); // COLOR_FormatSurface
    // AMEDIAFORMAT_KEY_LATENCY
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_LATENCY, 0);
    // AMEDIAFORMAT_KEY_COMPLEXITY
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COMPLEXITY, 10);
    // max-bframes
    AMediaFormat_setInt32(format, "max-bframes", 0);
    if (isHevc) {
        if (colorspace.bit_depth == 10) {
            // HEVCProfileMain10
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PROFILE, 2);
        } else {
            // HEVCProfileMain
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PROFILE, 1);
        }
        // HEVCMainTierLevel51
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_LEVEL, 65536);
    } else {
        // HIGH profile
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PROFILE, 0x08);
        // AVCLevel42
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_LEVEL, 0x2000);
        AMediaFormat_setInt32(format, "vendor.qti-ext-enc-low-latency.enable", 1);
    }
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


//    ANativeWindow *creatInputSurfaceWithANativeWindow(const video::config_t &config, video::sunshine_colorspace_t colorspace) {
//        // Convert config to java HashMap
//        std::map<std::string, std::any> configMap;
//        configMap["width"] = config.width;
//        configMap["height"] = config.height;
//        configMap["framerate"] = config.framerate;
//        configMap["bitrate"] = config.bitrate;
//        configMap["slicesPerFrame"] = config.slicesPerFrame;
//        configMap["numRefFrames"] = config.numRefFrames;
//        configMap["encoderCscMode"] = config.encoderCscMode;
//        configMap["videoFormat"] = config.videoFormat;
//        configMap["dynamicRange"] = config.dynamicRange;
//        configMap["chromaSamplingType"] = config.chromaSamplingType;
//        configMap["enableIntraRefresh"] = config.enableIntraRefresh;
//        // full_range
//        configMap["full_range"] = colorspace.full_range ? JNI_TRUE : JNI_FALSE;
//        // colorspace
//        int colorstandard = 0;
//        switch (colorspace.colorspace) {
//            case video::colorspace_e::rec601:
//                colorstandard = 4; // COLOR_STANDARD_BT601_NTSC
//                break;
//            case video::colorspace_e::rec709:
//                colorstandard = 1; // COLOR_STANDARD_BT709
//                break;
//            case video::colorspace_e::bt2020:
//            case video::colorspace_e::bt2020sdr:
//                colorstandard = 6; // COLOR_STANDARD_BT2020
//                break;
//        }
//        configMap["colorstandard"] = colorstandard;
//        JNIEnv *env;
//        jint result = jvm->AttachCurrentThread(&env, nullptr);
//        jobject javaConfig = convertMapToJavaHashMap(env, configMap);
//        // Call Java method to create Surface
//        jmethodID javaMethod = env->GetStaticMethodID(sunshineServerClass, "createSurface",
//                                                      "(Ljava/util/HashMap;)Landroid/view/Surface;");
//        if (javaMethod == nullptr) {
//            BOOST_LOG(error) << "Cannot find createSurface method"sv;
//            env->DeleteLocalRef(javaConfig);
//            jvm->DetachCurrentThread();
//            return nullptr;
//        }
//        jobject javaSurface = env->CallStaticObjectMethod(sunshineServerClass, javaMethod, javaConfig);
//        // Convert Java Surface to ANativeWindow
//        ANativeWindow *window = ANativeWindow_fromSurface(env, javaSurface);
//
//        return window;
//    }


    void captureVideoLoop(void *channel_data, safe::mail_t mail, const video::config_t &config,
                          const audio::config_t &audioConfig) {
        JNIEnv *env;
        jint result = jvm->AttachCurrentThread(&env, nullptr);
        if (result != JNI_OK) {
            BOOST_LOG(error) << "无法附加到 Java 线程"sv;
            return;
        }
        auto shutdown_event = mail->event<bool>(mail::shutdown);
        auto idr_events = mail->event<bool>(mail::idr);
        // 添加更详细的客户端配置日志
        BOOST_LOG(info) << "客户端请求视频配置:"sv;
        BOOST_LOG(info) << "  - 分辨率: "sv << config.width << "x"sv << config.height;
        BOOST_LOG(info) << "  - 帧率: "sv << config.framerate;
        BOOST_LOG(info) << "  - 视频格式: "sv << (config.videoFormat == 1 ? "HEVC" : "H.264");
        BOOST_LOG(info) << "  - 色度采样: "sv << (config.chromaSamplingType == 1 ? "YUV 4:4:4" : "YUV 4:2:0");
        BOOST_LOG(info) << "  - 动态范围: "sv << (config.dynamicRange ? "HDR" : "SDR");
        BOOST_LOG(info) << "  - 编码器色彩空间模式: 0x"sv << std::hex << config.encoderCscMode << std::dec;

        // 获取并记录详细的色彩空间信息
        bool isHdr = false;
        video::sunshine_colorspace_t colorspace = colorspace_from_client_config(config, isHdr);
        BOOST_LOG(info) << "色彩空间配置:"sv;
        BOOST_LOG(info) << "  - 色彩空间: "sv << static_cast<int>(colorspace.colorspace);
        BOOST_LOG(info) << "  - 位深度: "sv << colorspace.bit_depth;
        BOOST_LOG(info) << "  - 色彩范围: "sv << (colorspace.full_range ? "Full" : "Limited");

        AMediaFormat *format = createFormat(config);
        // 打印最终的媒体格式颜色配置
        int32_t colorStandard = 0, colorRange = 0, colorTransfer = 0;
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_COLOR_STANDARD, &colorStandard);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_COLOR_RANGE, &colorRange);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_COLOR_TRANSFER, &colorTransfer);

        BOOST_LOG(info) << "最终媒体格式颜色配置:"sv;
        BOOST_LOG(info) << "  - COLOR_STANDARD: "sv << colorStandard;
        BOOST_LOG(info) << "  - COLOR_RANGE: "sv << colorRange << (colorRange == 1 ? " (FULL)" : " (LIMITED)");
        BOOST_LOG(info) << "  - COLOR_TRANSFER: "sv << colorTransfer;


        AMediaCodec *codec = AMediaCodec_createEncoderByType(config.videoFormat == 1 ? "video/hevc" : "video/avc");
        if (!codec) {
            BOOST_LOG(error) << "无法创建编码器"sv;
            AMediaFormat_delete(format);
            return;
        }

        // 配置编码器
        media_status_t config_status = AMediaCodec_configure(codec, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        if (config_status != AMEDIA_OK) {
            std::string errorMsg = "无法配置编码器，错误码: " + std::to_string(config_status);
            BOOST_LOG(error) << errorMsg;
            AMediaCodec_delete(codec);
            AMediaFormat_delete(format);
            return;
        }
        // 获取输入 Surface
        ANativeWindow *inputSurface;
        media_status_t create_surface_status = AMediaCodec_createInputSurface(codec, &inputSurface);
        if (create_surface_status != AMEDIA_OK) {
            BOOST_LOG(error) << "无法创建输入Surface，错误码: "sv << create_surface_status;
            AMediaCodec_delete(codec);
            AMediaFormat_delete(format);
            return;
        }

        // 将 ANativeWindow 转换为 Java Surface 对象
        jobject javaSurface = ANativeWindow_toSurface(env, inputSurface);
        if (javaSurface == nullptr) {
            BOOST_LOG(error) << "无法将 ANativeWindow 转换为 Surface"sv;
            jvm->DetachCurrentThread();
            ANativeWindow_release(inputSurface);
            AMediaCodec_delete(codec);
            AMediaFormat_delete(format);
            return;
        }

        bool shouldMute = true;
        if (audioConfig.flags[audio::config_t::HOST_AUDIO]) {
            BOOST_LOG(info) << "音频配置: 声音将在主机端(Sunshine服务器)播放"sv;
            shouldMute = false;
        } else {
            BOOST_LOG(info) << "音频配置: 声音将在客户端(Moonlight)播放"sv;
        }

        // 调用 createVirtualDisplay 方法，传递 shouldMute 参数
        createVirtualDisplay(env, config.width, config.height, 120, audioConfig.packetDuration,
                             javaSurface, shouldMute);

        // 启动编码器
        media_status_t status = AMediaCodec_start(codec);
        if (status != AMEDIA_OK) {
            BOOST_LOG(error) << "无法启动编码器，错误码: "sv << status;
            env->DeleteLocalRef(javaSurface);
            jvm->DetachCurrentThread();
            ANativeWindow_release(inputSurface);
            AMediaCodec_delete(codec);
            AMediaFormat_delete(format);
            return;
        }

        // 编码循环
        std::vector<uint8_t> codecConfigData;  // 用于存储完整的编解码器配置数据
        int64_t frameIndex = 0;

        while (!shutdown_event->peek()) {
            bool requested_idr_frame = false;
            if (idr_events->peek()) {
                requested_idr_frame = true;
                idr_events->pop();
            }

            if (requested_idr_frame) {
                // 使用 Bundle 参数请求同步帧（IDR帧）
                AMediaFormat *params = AMediaFormat_new();
                AMediaFormat_setInt32(params, "request-sync", 0);
                media_status_t status = AMediaCodec_setParameters(codec, params);
                if (status != AMEDIA_OK) {
                    BOOST_LOG(warning) << "请求 IDR 帧失败，错误码: "sv << status;
                } else {
                    BOOST_LOG(info) << "已请求 IDR 帧"sv;
                }
                AMediaFormat_delete(params);
            }

            // 获取输出缓冲区，使用1秒的超时时间
            AMediaCodecBufferInfo bufferInfo;
            ssize_t outputBufferIndex = AMediaCodec_dequeueOutputBuffer(codec, &bufferInfo,
                                                                        1000000); // 1秒 = 1000000微秒

            if (outputBufferIndex >= 0) {
                // 获取到有效的输出缓冲区
                size_t bufferSize = bufferInfo.size;
                uint8_t *buffer = nullptr;
                size_t out_size = 0;

                // 获取缓冲区数据
                buffer = AMediaCodec_getOutputBuffer(codec, outputBufferIndex, &out_size);
                if (buffer != nullptr) {
                    // 处理编码后的数据
                    if (bufferInfo.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
                        // 这是编解码器配置数据（SPS/PPS）
                        BOOST_LOG(info) << "收到编解码器配置数据，大小: "sv << bufferSize;

                        // 直接保存整个配置数据
                        codecConfigData.assign(buffer, buffer + bufferSize);
                        BOOST_LOG(info) << "保存完整的编解码器配置数据，大小: "sv
                                        << codecConfigData.size();
                    } else {
                        // 这是正常的编码帧
                        bool isKeyFrame =
                                (bufferInfo.flags & AMEDIACODEC_BUFFER_FLAG_KEY_FRAME) != 0;
                        BOOST_LOG(verbose) << "收到" << (isKeyFrame ? "关键帧" : "普通帧")
                                           << "，大小: "sv << bufferSize;
                        frameIndex++;

                        if (isKeyFrame) {
                            // 对于关键帧，需要在数据前附加编解码器配置数据
                            if (!codecConfigData.empty()) {
                                // 创建包含配置数据和关键帧的完整数据
                                std::vector<uint8_t> frameData;

                                // 添加配置数据
                                frameData.insert(frameData.end(), codecConfigData.begin(),
                                                 codecConfigData.end());

                                // 添加关键帧数据
                                frameData.insert(frameData.end(), buffer, buffer + bufferSize);

                                BOOST_LOG(verbose) << "发送关键帧(带配置数据)，总大小: "sv
                                                   << frameData.size();
                                // 发送完整的关键帧数据
                                stream::postFrame(std::move(frameData), frameIndex, true,
                                                  channel_data);
                            } else {
                                BOOST_LOG(error) << "没有编解码器配置数据，无法发送完整关键帧"sv;
                            }
                        } else {
                            std::vector<uint8_t> frameData;
                            frameData.insert(frameData.end(), buffer, buffer + bufferSize);
                            stream::postFrame(std::move(frameData), frameIndex, false,
                                              channel_data);
                        }
                    }
                }

                // 释放输出缓冲区
                AMediaCodec_releaseOutputBuffer(codec, outputBufferIndex, false);
            } else if (outputBufferIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                BOOST_LOG(verbose) << "编码器超时，等待输出缓冲区"sv;
                continue;
            } else if (outputBufferIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                // 输出格式已更改
                AMediaFormat *format = AMediaCodec_getOutputFormat(codec);
                BOOST_LOG(info) << "编码器输出格式已更改"sv;
                // 可以从format中获取更多信息
                AMediaFormat_delete(format);
            } else if (outputBufferIndex == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
                // 输出缓冲区已更改
                BOOST_LOG(info) << "编码器输出缓冲区已更改"sv;
                // 在较新的 NDK 版本中，这个事件通常可以忽略，因为 AMediaCodec_getOutputBuffer 会自动处理缓冲区变化
            } else {
                // 出错
                BOOST_LOG(error) << "编码器出错，错误码: "sv << outputBufferIndex;
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
        jvm->DetachCurrentThread();
    }

    void captureAudioLoop(void *channel_data, safe::mail_t mail, const audio::config_t &config) {
        samples = std::make_shared<audio::sample_queue_t::element_type>(30);
        encodeThread(samples, config, channel_data);
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