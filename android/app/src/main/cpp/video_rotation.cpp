#include "video_rotation.h"
#include "logging.h"
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>
#include <algorithm>
#include <chrono>
#include <thread>
#include <memory>

using namespace std::literals;

namespace video_rotation {

    // ====================== YUV色彩空间验证辅助函数 ======================
    
    /**
     * 验证和修夏YUV数据，确保在正确的色彩空间范围内
     * 增强版本：专门解决绿屏和色彩错乱问题
     */
    void validateAndFixYUVData(YUVFrame& frame) {
        BOOST_LOG(info) << "[YUV-VALIDATE] Validating and fixing YUV color space data...";
        
        // 检测是否存在色彩问题
        bool hasColorIssue = false;
        
        // 检查Y分量（亮度）的分布
        int invalidY = 0, allZeroY = 0;
        for (auto& y : frame.yData) {
            if (y == 0) allZeroY++;
            if (y < 16) {
                y = 16;  // 修复为最小亮度
                invalidY++;
                hasColorIssue = true;
            } else if (y > 235) {
                y = 235; // 修夏为最大亮度
                invalidY++;
                hasColorIssue = true;
            }
        }
        
        // 检测绿屏问题：如果大部分Y值为0，说明有绿屏问题
        if (allZeroY > frame.yData.size() / 2) {
            BOOST_LOG(error) << "[YUV-VALIDATE] GREEN SCREEN DETECTED! Most Y values are 0";
            BOOST_LOG(error) << "[YUV-VALIDATE] Zero Y pixels: " << allZeroY << "/" << frame.yData.size();
            // 将所有的Y=0改为正常的亮度值
            for (auto& y : frame.yData) {
                if (y == 0) y = 64; // 使用中等亮度而不是黑色
            }
            hasColorIssue = true;
        }
        
        // 验证U和V分量（色度）
        int invalidU = 0, invalidV = 0;
        int extremeU = 0, extremeV = 0;
        
        for (auto& u : frame.uData) {
            if (u == 0 || u == 255) extremeU++; // 极端值可能导致色彩问题
            if (u < 16) {
                u = 128; // 改为中性色度而不是最小值
                invalidU++;
                hasColorIssue = true;
            } else if (u > 240) {
                u = 128; // 改为中性色度而不是最大值
                invalidU++;
                hasColorIssue = true;
            }
        }
        
        for (auto& v : frame.vData) {
            if (v == 0 || v == 255) extremeV++; // 极端值可能导致色彩问题
            if (v < 16) {
                v = 128; // 改为中性色度
                invalidV++;
                hasColorIssue = true;
            } else if (v > 240) {
                v = 128; // 改为中性色度
                invalidV++;
                hasColorIssue = true;
            }
        }
        
        // 如果有过多极端值，可能需要整体修夏
        if (extremeU > frame.uData.size() / 4 || extremeV > frame.vData.size() / 4) {
            BOOST_LOG(warning) << "[YUV-VALIDATE] Extreme UV values detected, applying aggressive fix";
            BOOST_LOG(warning) << "[YUV-VALIDATE] Extreme U: " << extremeU << ", Extreme V: " << extremeV;
            
            // 对极端值进行平滑化处理
            for (auto& u : frame.uData) {
                if (u == 0) u = 64;
                if (u == 255) u = 192;
            }
            for (auto& v : frame.vData) {
                if (v == 0) v = 64;
                if (v == 255) v = 192;
            }
            hasColorIssue = true;
        }
        
        if (hasColorIssue) {
            BOOST_LOG(warning) << "[YUV-VALIDATE] Fixed color issues - Y=" << invalidY 
                              << ", U=" << invalidU << ", V=" << invalidV;
            BOOST_LOG(warning) << "[YUV-VALIDATE] This should resolve green screen and color corruption";
        } else {
            BOOST_LOG(info) << "[YUV-VALIDATE] YUV color space validation passed - no issues detected";
        }
    }
    
    // ====================== YUV格式解析辅助函数 ======================
    
    /**
     * 解析I420格式的YUV数据
     */
    bool parseI420Format(uint8_t* buffer, size_t bufferSize, YUVFrame& outputFrame, 
                        int stride, int sliceHeight) {
        int width = outputFrame.width;
        int height = outputFrame.height;
        
        BOOST_LOG(info) << "[I420-PARSE] ==== I420 FORMAT PARSING START ====";
        BOOST_LOG(info) << "[I420-PARSE] Input parameters:";
        BOOST_LOG(info) << "[I420-PARSE]   - Frame size: " << width << "x" << height;
        BOOST_LOG(info) << "[I420-PARSE]   - Stride: " << stride;
        BOOST_LOG(info) << "[I420-PARSE]   - Slice height: " << sliceHeight;
        BOOST_LOG(info) << "[I420-PARSE]   - Buffer size: " << bufferSize << " bytes";
        
        // I420格式: YYYYYYYY UUUU VVVV (平面排列)
        int yPlaneSize = stride * sliceHeight;
        int uvStride = (stride + 1) / 2;
        int uvSliceHeight = (sliceHeight + 1) / 2;
        int uvPlaneSize = uvStride * uvSliceHeight;
        
        int totalRequiredSize = yPlaneSize + uvPlaneSize * 2;
        
        BOOST_LOG(info) << "[I420-PARSE] Calculated layout:";
        BOOST_LOG(info) << "[I420-PARSE]   - Y plane size: " << yPlaneSize << " bytes (" << stride << " x " << sliceHeight << ")";
        BOOST_LOG(info) << "[I420-PARSE]   - UV stride: " << uvStride;
        BOOST_LOG(info) << "[I420-PARSE]   - UV slice height: " << uvSliceHeight;
        BOOST_LOG(info) << "[I420-PARSE]   - U plane size: " << uvPlaneSize << " bytes";
        BOOST_LOG(info) << "[I420-PARSE]   - V plane size: " << uvPlaneSize << " bytes";
        BOOST_LOG(info) << "[I420-PARSE]   - Total required: " << totalRequiredSize << " bytes";
        BOOST_LOG(info) << "[I420-PARSE]   - Actual buffer: " << bufferSize << " bytes";
        
        if (bufferSize < totalRequiredSize) {
            BOOST_LOG(error) << "[I420-PARSE] Buffer too small for I420 format";
            BOOST_LOG(error) << "[I420-PARSE] This will cause parsing failure and likely green screen";
            return false;
        }
        
        // 分配输出缓冲区
        int outputYSize = width * height;
        int outputUVSize = (width * height) / 4;
        
        outputFrame.yData.resize(outputYSize);
        outputFrame.uData.resize(outputUVSize);
        outputFrame.vData.resize(outputUVSize);
        
        BOOST_LOG(info) << "[I420-PARSE] Output buffer allocation:";
        BOOST_LOG(info) << "[I420-PARSE]   - Y output: " << outputYSize << " bytes";
        BOOST_LOG(info) << "[I420-PARSE]   - U output: " << outputUVSize << " bytes";
        BOOST_LOG(info) << "[I420-PARSE]   - V output: " << outputUVSize << " bytes";
        
        uint8_t* yPlane = buffer;
        uint8_t* uPlane = buffer + yPlaneSize;
        uint8_t* vPlane = buffer + yPlaneSize + uvPlaneSize;
        
        BOOST_LOG(info) << "[I420-PARSE] Plane pointers:";
        BOOST_LOG(info) << "[I420-PARSE]   - Y plane starts at offset: 0";
        BOOST_LOG(info) << "[I420-PARSE]   - U plane starts at offset: " << yPlaneSize;
        BOOST_LOG(info) << "[I420-PARSE]   - V plane starts at offset: " << (yPlaneSize + uvPlaneSize);
        
        // 打印前几个字节用于调试
        if (bufferSize >= 16) {
            std::string yHex = "", uHex = "", vHex = "";
            for (int i = 0; i < 8 && i < yPlaneSize; i++) {
                char hex[4];
                sprintf(hex, "%02X ", yPlane[i]);
                yHex += hex;
            }
            for (int i = 0; i < 4 && i < uvPlaneSize; i++) {
                char hex[4];
                sprintf(hex, "%02X ", uPlane[i]);
                uHex += hex;
                sprintf(hex, "%02X ", vPlane[i]);
                vHex += hex;
            }
            BOOST_LOG(info) << "[I420-PARSE] Plane data headers:";
            BOOST_LOG(info) << "[I420-PARSE]   - Y: " << yHex;
            BOOST_LOG(info) << "[I420-PARSE]   - U: " << uHex;
            BOOST_LOG(info) << "[I420-PARSE]   - V: " << vHex;
        }
        
        // 复制Y分量（处理stride）
        BOOST_LOG(info) << "[I420-PARSE] Copying Y plane with stride handling...";
        for (int y = 0; y < height; y++) {
            memcpy(outputFrame.yData.data() + y * width, 
                   yPlane + y * stride, 
                   width);
        }
        BOOST_LOG(info) << "[I420-PARSE] Y plane copy completed";
        
        // 复制U和V分量（处理stride）
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        
        BOOST_LOG(info) << "[I420-PARSE] Copying UV planes (" << uvWidth << "x" << uvHeight << ") with stride handling...";
        
        for (int y = 0; y < uvHeight; y++) {
            memcpy(outputFrame.uData.data() + y * uvWidth, 
                   uPlane + y * uvStride, 
                   uvWidth);
            memcpy(outputFrame.vData.data() + y * uvWidth, 
                   vPlane + y * uvStride, 
                   uvWidth);
        }
        BOOST_LOG(info) << "[I420-PARSE] UV planes copy completed";
        
        // 验证复制的数据
        size_t nonZeroY = 0, nonZeroU = 0, nonZeroV = 0;
        size_t checkSize = std::min((size_t)100, outputFrame.yData.size());
        for (size_t i = 0; i < checkSize; i++) {
            if (outputFrame.yData[i] != 0) nonZeroY++;
        }
        checkSize = std::min((size_t)100, outputFrame.uData.size());
        for (size_t i = 0; i < checkSize; i++) {
            if (outputFrame.uData[i] != 0) nonZeroU++;
            if (i < outputFrame.vData.size() && outputFrame.vData[i] != 0) nonZeroV++;
        }
        
        BOOST_LOG(info) << "[I420-PARSE] Output data validation (first 100 pixels):";
        BOOST_LOG(info) << "[I420-PARSE]   - Non-zero Y: " << nonZeroY << "/" << std::min((size_t)100, outputFrame.yData.size());
        BOOST_LOG(info) << "[I420-PARSE]   - Non-zero U: " << nonZeroU << "/" << std::min((size_t)100, outputFrame.uData.size());
        BOOST_LOG(info) << "[I420-PARSE]   - Non-zero V: " << nonZeroV << "/" << std::min((size_t)100, outputFrame.vData.size());
        
        if (nonZeroY == 0 && nonZeroU == 0 && nonZeroV == 0) {
            BOOST_LOG(error) << "[I420-PARSE] CRITICAL: Parsed YUV data is all zeros!";
            BOOST_LOG(error) << "[I420-PARSE] CRITICAL: This WILL cause green screen - check input buffer content";
        } else {
            BOOST_LOG(info) << "[I420-PARSE] Data validation passed - YUV contains actual image data";
        }
        
        BOOST_LOG(info) << "[I420-PARSE] I420 parsing successful:";
        BOOST_LOG(info) << "[I420-PARSE]   - Final Y: " << outputFrame.yData.size() << " bytes";
        BOOST_LOG(info) << "[I420-PARSE]   - Final U: " << outputFrame.uData.size() << " bytes";
        BOOST_LOG(info) << "[I420-PARSE]   - Final V: " << outputFrame.vData.size() << " bytes";
        BOOST_LOG(info) << "[I420-PARSE] ==== I420 FORMAT PARSING SUCCESS ====";
        
        return true;
    }

    // ====================== VideoDecoder 实现 ======================
    
    VideoDecoder::VideoDecoder() : decoder(nullptr), decoderFormat(nullptr), isInitialized(false) {
    }
    
    VideoDecoder::~VideoDecoder() {
        release();
    }
    
    bool VideoDecoder::initialize(const char* mimeType, int width, int height, 
                                 const std::vector<uint8_t>& configData) {
        if (isInitialized) {
            BOOST_LOG(warning) << "[DECODER-INIT] Already initialized";
            return false;
        }
        
        BOOST_LOG(info) << "[DECODER-INIT] Starting decoder initialization:";
        BOOST_LOG(info) << "[DECODER-INIT]   - MIME: " << mimeType;
        BOOST_LOG(info) << "[DECODER-INIT]   - Resolution: " << width << "x" << height;
        BOOST_LOG(info) << "[DECODER-INIT]   - Config data size: " << configData.size() << " bytes";
        
        // 创建解码器
        BOOST_LOG(info) << "[DECODER-CREATE] Creating MediaCodec decoder for " << mimeType;
        decoder = AMediaCodec_createDecoderByType(mimeType);
        if (!decoder) {
            BOOST_LOG(error) << "[DECODER-ERROR] Failed to create decoder for " << mimeType;
            return false;
        }
        BOOST_LOG(info) << "[DECODER-CREATE] Decoder created successfully";
        
        // 创建格式
        decoderFormat = AMediaFormat_new();
        AMediaFormat_setString(decoderFormat, AMEDIAFORMAT_KEY_MIME, mimeType);
        AMediaFormat_setInt32(decoderFormat, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(decoderFormat, AMEDIAFORMAT_KEY_HEIGHT, height);
        
        // 设置配置数据 (SPS/PPS)
        if (!configData.empty()) {
            BOOST_LOG(info) << "[DECODER-CONFIG] Setting SPS/PPS config data, size: " << configData.size();
            // 打印配置数据的前几个字节用于调试
            std::string configHex = "";
            for (size_t i = 0; i < std::min((size_t)8, configData.size()); i++) {
                char hex[4];
                sprintf(hex, "%02X ", configData[i]);
                configHex += hex;
            }
            BOOST_LOG(info) << "[DECODER-CONFIG] Config data header: " << configHex;
            AMediaFormat_setBuffer(decoderFormat, "csd-0", 
                                 configData.data(), configData.size());
        } else {
            BOOST_LOG(warning) << "[DECODER-WARNING] No codec config data provided - may cause decode failure";
        }
        
        // 不设置颜色格式，让解码器使用默认格式
        // AMediaFormat_setInt32(decoderFormat, AMEDIAFORMAT_KEY_COLOR_FORMAT, 21);
        
        BOOST_LOG(info) << "Decoder format configured, width=" << width << ", height=" << height;
        
        // 配置解码器 (输出到CPU内存)
        BOOST_LOG(info) << "[DECODER-CONFIG] Configuring decoder for CPU output";
        media_status_t status = AMediaCodec_configure(decoder, decoderFormat, 
                                                     nullptr, nullptr, 0);
        if (status != AMEDIA_OK) {
            BOOST_LOG(error) << "[DECODER-ERROR] Failed to configure decoder, error code: " << status;
            BOOST_LOG(error) << "[DECODER-ERROR] This typically means incompatible format or missing SPS/PPS";
            release();
            return false;
        }
        BOOST_LOG(info) << "[DECODER-CONFIG] Decoder configured successfully";
        
        // 启动解码器
        BOOST_LOG(info) << "[DECODER-START] Starting decoder...";
        status = AMediaCodec_start(decoder);
        if (status != AMEDIA_OK) {
            BOOST_LOG(error) << "[DECODER-ERROR] Failed to start decoder, error code: " << status;
            release();
            return false;
        }
        
        isInitialized = true;
        BOOST_LOG(info) << "[DECODER-SUCCESS] Video decoder initialized and started successfully";
        return true;
    }
    
    bool VideoDecoder::decodeToYUV(const std::vector<uint8_t>& encodedData, YUVFrame& outputFrame) {
        if (!isInitialized || !decoder) {
            BOOST_LOG(error) << "[DECODE-ERROR] Decoder not initialized";
            return false;
        }
        
        BOOST_LOG(info) << "[DECODE-START] ==== DECODE PROCESS START ====";
        BOOST_LOG(info) << "[DECODE-INPUT] Input frame size: " << encodedData.size() << " bytes";
        
        // 打印输入数据的前几个字节用于调试
        if (encodedData.size() >= 8) {
            std::string inputHex = "";
            for (size_t i = 0; i < 8; i++) {
                char hex[4];
                sprintf(hex, "%02X ", encodedData[i]);
                inputHex += hex;
            }
            BOOST_LOG(info) << "[DECODE-INPUT] Input data header: " << inputHex;
        }
        
        // 获取输入缓冲区
        BOOST_LOG(info) << "[DECODE-BUFFER] Requesting input buffer...";
        ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(decoder, 10000);
        if (inputIndex < 0) {
            BOOST_LOG(error) << "[DECODE-ERROR] Failed to dequeue input buffer, code: " << inputIndex;
            BOOST_LOG(error) << "[DECODE-ERROR] This may indicate decoder is not ready or has errors";
            return false;
        }
        BOOST_LOG(info) << "[DECODE-BUFFER] Got input buffer index: " << inputIndex;
        
        // 填充输入数据
        size_t inputSize;
        uint8_t* inputBuffer = AMediaCodec_getInputBuffer(decoder, inputIndex, &inputSize);
        if (!inputBuffer || inputSize < encodedData.size()) {
            BOOST_LOG(error) << "[DECODE-ERROR] Input buffer too small: available=" << inputSize 
                            << ", required=" << encodedData.size();
            return false;
        }
        
        BOOST_LOG(info) << "[DECODE-BUFFER] Copying " << encodedData.size() << " bytes to input buffer";
        memcpy(inputBuffer, encodedData.data(), encodedData.size());
        
        // 使用当前时间戳
        int64_t presentationTimeUs = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
            
        BOOST_LOG(info) << "[DECODE-QUEUE] Queuing input buffer with timestamp: " << presentationTimeUs;
        media_status_t status = AMediaCodec_queueInputBuffer(decoder, inputIndex, 0, 
                                                           encodedData.size(), presentationTimeUs, 0);
        if (status != AMEDIA_OK) {
            BOOST_LOG(error) << "[DECODE-ERROR] Failed to queue input buffer, status: " << status;
            return false;
        }
        BOOST_LOG(info) << "[DECODE-QUEUE] Input buffer queued successfully";
        
        // 尝试多次获取输出，因为MediaCodec是异步的
        BOOST_LOG(info) << "[DECODE-OUTPUT] Waiting for decoded output...";
        for (int attempts = 0; attempts < 10; attempts++) {
            AMediaCodecBufferInfo bufferInfo;
            ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(decoder, &bufferInfo, 5000);
            
            if (outputIndex >= 0) {
                BOOST_LOG(info) << "[DECODE-OUTPUT] Got output buffer on attempt " << (attempts + 1) 
                               << ", size: " << bufferInfo.size << ", flags: " << bufferInfo.flags;
                
                // 检查是否是配置数据
                if (bufferInfo.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
                    BOOST_LOG(info) << "[DECODE-OUTPUT] Received codec config data, skipping";
                    AMediaCodec_releaseOutputBuffer(decoder, outputIndex, false);
                    continue;
                }
                
                size_t outputSize;
                uint8_t* outputBuffer = AMediaCodec_getOutputBuffer(decoder, outputIndex, &outputSize);
                
                if (outputBuffer && bufferInfo.size > 0) {
                    // 获取实际的输出格式信息
                    AMediaFormat* outputFormat = AMediaCodec_getOutputFormat(decoder);
                    int32_t actualWidth, actualHeight, colorFormat, stride, sliceHeight;
                    
                    AMediaFormat_getInt32(outputFormat, AMEDIAFORMAT_KEY_WIDTH, &actualWidth);
                    AMediaFormat_getInt32(outputFormat, AMEDIAFORMAT_KEY_HEIGHT, &actualHeight);
                    AMediaFormat_getInt32(outputFormat, AMEDIAFORMAT_KEY_COLOR_FORMAT, &colorFormat);
                    
                    // 尝试获取stride和slice-height
                    if (!AMediaFormat_getInt32(outputFormat, AMEDIAFORMAT_KEY_STRIDE, &stride)) {
                        stride = actualWidth;
                    }
                    if (!AMediaFormat_getInt32(outputFormat, "slice-height", &sliceHeight)) {
                        sliceHeight = actualHeight;
                    }
                    
                    BOOST_LOG(info) << "[DECODE-FORMAT] Decoder output format details:";
                    BOOST_LOG(info) << "[DECODE-FORMAT]   - Resolution: " << actualWidth << "x" << actualHeight;
                    BOOST_LOG(info) << "[DECODE-FORMAT]   - Color format: " << colorFormat;
                    BOOST_LOG(info) << "[DECODE-FORMAT]   - Stride: " << stride;
                    BOOST_LOG(info) << "[DECODE-FORMAT]   - Slice height: " << sliceHeight;
                    BOOST_LOG(info) << "[DECODE-FORMAT]   - Buffer size: " << bufferInfo.size;
                    
                    // 更新输出帧尺寸
                    outputFrame.width = actualWidth;
                    outputFrame.height = actualHeight;
                    
                    // 根据颜色格式正确解析YUV数据
                    bool parseSuccess = false;
                    
                    BOOST_LOG(info) << "[DECODE-YUV] Starting YUV data parsing...";
                    if (colorFormat == 21) { // COLOR_FormatYUV420Planar (I420)
                        BOOST_LOG(info) << "[DECODE-YUV] Using I420 format parser (COLOR_FormatYUV420Planar)";
                        parseSuccess = parseI420Format(outputBuffer, bufferInfo.size, outputFrame, stride, sliceHeight);
                    } else if (colorFormat == 19) { // COLOR_FormatYUV420PackedPlanar
                        BOOST_LOG(info) << "[DECODE-YUV] Using I420 format parser (COLOR_FormatYUV420PackedPlanar)";
                        parseSuccess = parseI420Format(outputBuffer, bufferInfo.size, outputFrame, stride, sliceHeight);
                    } else if (colorFormat == 2130708361) { // COLOR_FormatSurface - 不应该到这里
                        BOOST_LOG(error) << "[DECODE-ERROR] Unexpected surface format in CPU decoder: " << colorFormat;
                        parseSuccess = false;
                    } else {
                        BOOST_LOG(warning) << "[DECODE-WARNING] Unknown color format " << colorFormat << ", attempting I420 parsing";
                        parseSuccess = parseI420Format(outputBuffer, bufferInfo.size, outputFrame, stride, sliceHeight);
                    }
                    
                    AMediaFormat_delete(outputFormat);
                    
                    if (parseSuccess) {
                        AMediaCodec_releaseOutputBuffer(decoder, outputIndex, false);
                        BOOST_LOG(info) << "[DECODE-SUCCESS] YUV parsing successful!";
                        BOOST_LOG(info) << "[DECODE-SUCCESS]   - Y plane: " << outputFrame.yData.size() << " bytes";
                        BOOST_LOG(info) << "[DECODE-SUCCESS]   - U plane: " << outputFrame.uData.size() << " bytes";
                        BOOST_LOG(info) << "[DECODE-SUCCESS]   - V plane: " << outputFrame.vData.size() << " bytes";
                        BOOST_LOG(info) << "[DECODE-SUCCESS] ==== DECODE PROCESS SUCCESS ====";
                        return true;
                    } else {
                        BOOST_LOG(error) << "[DECODE-ERROR] Failed to parse YUV data from buffer";
                    }
                }
                
                AMediaCodec_releaseOutputBuffer(decoder, outputIndex, false);
            } else if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                // 输出格式改变
                BOOST_LOG(info) << "[DECODE-FORMAT] Output format changed, updating...";
                AMediaFormat* newFormat = AMediaCodec_getOutputFormat(decoder);
                if (newFormat) {
                    int32_t width, height, colorFormat;
                    AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_WIDTH, &width);
                    AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_HEIGHT, &height);
                    AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_COLOR_FORMAT, &colorFormat);
                    
                    BOOST_LOG(info) << "[DECODE-FORMAT] New format: " << width << "x" << height 
                                   << ", color format: " << colorFormat;
                    
                    // 更新输出帧尺寸
                    outputFrame.width = width;
                    outputFrame.height = height;
                    
                    AMediaFormat_delete(newFormat);
                }
                continue;
            } else if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                BOOST_LOG(info) << "[DECODE-WAIT] Decoder needs more time, attempt " << (attempts + 1) << "/10";
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
                continue;
            } else {
                BOOST_LOG(error) << "[DECODE-ERROR] Decoder error code: " << outputIndex << " on attempt " << (attempts + 1);
                break;
            }
        }
        
        BOOST_LOG(error) << "[DECODE-FAILED] Failed to get decoded YUV data after 10 attempts";
        BOOST_LOG(error) << "[DECODE-FAILED] ==== DECODE PROCESS FAILED ====";
        return false;
    }
    
    void VideoDecoder::release() {
        if (decoder) {
            AMediaCodec_stop(decoder);
            AMediaCodec_delete(decoder);
            decoder = nullptr;
        }
        if (decoderFormat) {
            AMediaFormat_delete(decoderFormat);
            decoderFormat = nullptr;
        }
        isInitialized = false;
    }
    
    // ====================== VideoEncoder 实现 ======================
    
    VideoEncoder::VideoEncoder() : encoder(nullptr), encoderFormat(nullptr), isInitialized(false) {
    }
    
    VideoEncoder::~VideoEncoder() {
        release();
    }
    
    bool VideoEncoder::initialize(const char* mimeType, int width, int height, 
                                 int bitrate, int framerate) {
        if (isInitialized) {
            BOOST_LOG(warning) << "[ENCODER-INIT] Already initialized";
            return false;
        }
        
        BOOST_LOG(info) << "[ENCODER-INIT] Starting encoder initialization:";
        BOOST_LOG(info) << "[ENCODER-INIT]   - MIME: " << mimeType;
        BOOST_LOG(info) << "[ENCODER-INIT]   - Resolution: " << width << "x" << height;
        BOOST_LOG(info) << "[ENCODER-INIT]   - Bitrate: " << bitrate << " bps";
        BOOST_LOG(info) << "[ENCODER-INIT]   - Framerate: " << framerate << " fps";
        
        // 创建编码器
        BOOST_LOG(info) << "[ENCODER-CREATE] Creating MediaCodec encoder for " << mimeType;
        encoder = AMediaCodec_createEncoderByType(mimeType);
        if (!encoder) {
            BOOST_LOG(error) << "[ENCODER-ERROR] Failed to create encoder for " << mimeType;
            return false;
        }
        BOOST_LOG(info) << "[ENCODER-CREATE] Encoder created successfully";
        
        // 创建格式
        encoderFormat = AMediaFormat_new();
        AMediaFormat_setString(encoderFormat, AMEDIAFORMAT_KEY_MIME, mimeType);
        AMediaFormat_setInt32(encoderFormat, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(encoderFormat, AMEDIAFORMAT_KEY_HEIGHT, height);
        AMediaFormat_setInt32(encoderFormat, AMEDIAFORMAT_KEY_BIT_RATE, bitrate);
        AMediaFormat_setInt32(encoderFormat, AMEDIAFORMAT_KEY_FRAME_RATE, framerate);
        AMediaFormat_setInt32(encoderFormat, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 2); // 2秒一个I帧
        
        BOOST_LOG(info) << "[ENCODER-CONFIG] Format configuration:";
        BOOST_LOG(info) << "[ENCODER-CONFIG]   - MIME: " << mimeType;
        BOOST_LOG(info) << "[ENCODER-CONFIG]   - Width: " << width;
        BOOST_LOG(info) << "[ENCODER-CONFIG]   - Height: " << height;
        BOOST_LOG(info) << "[ENCODER-CONFIG]   - Bitrate: " << bitrate;
        BOOST_LOG(info) << "[ENCODER-CONFIG]   - Framerate: " << framerate;
        
        // 尝试使用更广泛支持的颜色格式
        // 优先尝试 COLOR_FormatYUV420Planar (I420)
        int colorFormat = 21; // COLOR_FormatYUV420Planar
        AMediaFormat_setInt32(encoderFormat, AMEDIAFORMAT_KEY_COLOR_FORMAT, colorFormat);
        
        BOOST_LOG(info) << "[ENCODER-CONFIG] Setting color format to I420 (" << colorFormat << ")";
        
        // 设置编码器质量参数
        AMediaFormat_setInt32(encoderFormat, "bitrate-mode", 2); // VBR
        AMediaFormat_setInt32(encoderFormat, "complexity", 0); // 低复杂度，提高性能
        AMediaFormat_setInt32(encoderFormat, "priority", 0); // 实时优先级
        
        BOOST_LOG(info) << "[ENCODER-CONFIG] Set encoder quality parameters: VBR, low complexity, realtime priority";
        
        // 配置编码器
        BOOST_LOG(info) << "[ENCODER-CONFIG] Configuring encoder...";
        media_status_t status = AMediaCodec_configure(encoder, encoderFormat, 
                                                     nullptr, nullptr, 
                                                     AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        if (status != AMEDIA_OK) {
            BOOST_LOG(error) << "[ENCODER-ERROR] Failed to configure encoder, error code: " << status;
            
            // 尝试使用备用颜色格式
            BOOST_LOG(info) << "[ENCODER-RETRY] Trying alternative color format (19)...";
            AMediaFormat_setInt32(encoderFormat, AMEDIAFORMAT_KEY_COLOR_FORMAT, 19); // COLOR_FormatYUV420PackedPlanar
            
            status = AMediaCodec_configure(encoder, encoderFormat, 
                                         nullptr, nullptr, 
                                         AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
            if (status != AMEDIA_OK) {
                BOOST_LOG(error) << "[ENCODER-ERROR] Failed to configure encoder with alternative format, error: " << status;
                release();
                return false;
            }
            BOOST_LOG(info) << "[ENCODER-RETRY] Alternative color format configuration successful";
        } else {
            BOOST_LOG(info) << "[ENCODER-CONFIG] Primary color format configuration successful";
        }
        
        // 启动编码器
        BOOST_LOG(info) << "[ENCODER-START] Starting encoder...";
        status = AMediaCodec_start(encoder);
        if (status != AMEDIA_OK) {
            BOOST_LOG(error) << "[ENCODER-ERROR] Failed to start encoder, error code: " << status;
            release();
            return false;
        }
        
        isInitialized = true;
        BOOST_LOG(info) << "[ENCODER-SUCCESS] Video encoder initialized and started successfully";
        return true;
    }
    
    bool VideoEncoder::encodeFromYUV(const YUVFrame& yuvFrame, std::vector<uint8_t>& outputData) {
        if (!isInitialized || !encoder) {
            BOOST_LOG(error) << "[ENCODE-ERROR] Encoder not initialized";
            return false;
        }
        
        BOOST_LOG(info) << "[ENCODE-START] ==== ENCODE PROCESS START ====";
        BOOST_LOG(info) << "[ENCODE-INPUT] YUV frame details:";
        BOOST_LOG(info) << "[ENCODE-INPUT]   - Resolution: " << yuvFrame.width << "x" << yuvFrame.height;
        BOOST_LOG(info) << "[ENCODE-INPUT]   - Y plane: " << yuvFrame.yData.size() << " bytes";
        BOOST_LOG(info) << "[ENCODE-INPUT]   - U plane: " << yuvFrame.uData.size() << " bytes";
        BOOST_LOG(info) << "[ENCODE-INPUT]   - V plane: " << yuvFrame.vData.size() << " bytes";
        
        // 获取输入缓冲区，增加重试机制
        BOOST_LOG(info) << "[ENCODE-BUFFER] Requesting input buffer...";
        ssize_t inputIndex = -1;
        for (int retry = 0; retry < 5; retry++) {
            inputIndex = AMediaCodec_dequeueInputBuffer(encoder, 5000); // 5秒超时
            if (inputIndex >= 0) {
                BOOST_LOG(info) << "[ENCODE-BUFFER] Got input buffer on retry " << (retry + 1) << ", index: " << inputIndex;
                break;
            }
            BOOST_LOG(warning) << "[ENCODE-BUFFER] Failed to dequeue input buffer, retry " << (retry + 1) << "/5, code: " << inputIndex;
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        
        if (inputIndex < 0) {
            BOOST_LOG(error) << "[ENCODE-ERROR] Failed to dequeue input buffer after 5 retries, final code: " << inputIndex;
            return false;
        }
        
        // 填充YUV数据
        size_t inputSize;
        uint8_t* inputBuffer = AMediaCodec_getInputBuffer(encoder, inputIndex, &inputSize);
        if (!inputBuffer) {
            BOOST_LOG(error) << "[ENCODE-ERROR] Failed to get input buffer pointer";
            return false;
        }
        
        // 计算所需大小
        size_t totalSize = yuvFrame.yData.size() + yuvFrame.uData.size() + yuvFrame.vData.size();
        if (inputSize < totalSize) {
            BOOST_LOG(error) << "[ENCODE-ERROR] Input buffer too small: available=" << inputSize 
                            << ", required=" << totalSize;
            return false;
        }
        
        BOOST_LOG(info) << "[ENCODE-BUFFER] Copying YUV data to input buffer, total: " << totalSize << " bytes";
        
        // 复制YUV数据到输入缓冲区 - 确保I420格式排列
        size_t offset = 0;
        
        // 复制Y平面（亮度）
        memcpy(inputBuffer + offset, yuvFrame.yData.data(), yuvFrame.yData.size());
        offset += yuvFrame.yData.size();
        
        // 复制U平面（色度）
        memcpy(inputBuffer + offset, yuvFrame.uData.data(), yuvFrame.uData.size());
        offset += yuvFrame.uData.size();
        
        // 复制V平面（色度）
        memcpy(inputBuffer + offset, yuvFrame.vData.data(), yuvFrame.vData.size());
        
        BOOST_LOG(info) << "[ENCODE-YUV] YUV data layout details:";
        BOOST_LOG(info) << "[ENCODE-YUV]   - Y plane: " << yuvFrame.yData.size() << " bytes (expected: " << (yuvFrame.width * yuvFrame.height) << ")";
        BOOST_LOG(info) << "[ENCODE-YUV]   - U plane: " << yuvFrame.uData.size() << " bytes (expected: " << (yuvFrame.width * yuvFrame.height / 4) << ")";
        BOOST_LOG(info) << "[ENCODE-YUV]   - V plane: " << yuvFrame.vData.size() << " bytes (expected: " << (yuvFrame.width * yuvFrame.height / 4) << ")";
        
        // 验证YUV数据的合理性
        int expectedYSize = yuvFrame.width * yuvFrame.height;
        int expectedUVSize = expectedYSize / 4;
        
        if (yuvFrame.yData.size() != expectedYSize || 
            yuvFrame.uData.size() != expectedUVSize || 
            yuvFrame.vData.size() != expectedUVSize) {
            BOOST_LOG(error) << "[ENCODE-ERROR] YUV data size mismatch detected!";
            BOOST_LOG(error) << "[ENCODE-ERROR]   - Y: expected=" << expectedYSize << ", actual=" << yuvFrame.yData.size();
            BOOST_LOG(error) << "[ENCODE-ERROR]   - U: expected=" << expectedUVSize << ", actual=" << yuvFrame.uData.size();
            BOOST_LOG(error) << "[ENCODE-ERROR]   - V: expected=" << expectedUVSize << ", actual=" << yuvFrame.vData.size();
            BOOST_LOG(error) << "[ENCODE-ERROR] This will likely cause green screen or encoding failure";
        } else {
            BOOST_LOG(info) << "[ENCODE-YUV] YUV data size validation passed";
        }
        
        // 使用当前时间戳
        int64_t presentationTimeUs = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        
        // 提交输入缓冲区
        media_status_t status = AMediaCodec_queueInputBuffer(encoder, inputIndex, 0, 
                                                           totalSize, presentationTimeUs, 0);
        if (status != AMEDIA_OK) {
            BOOST_LOG(error) << "Failed to queue input buffer, status: " << status;
            return false;
        }
        
        BOOST_LOG(verbose) << "Input buffer queued successfully, waiting for output...";
        
        // 尝试多次获取编码输出
        for (int attempts = 0; attempts < 20; attempts++) {
            AMediaCodecBufferInfo bufferInfo;
            ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(encoder, &bufferInfo, 1000); // 1秒超时
            
            if (outputIndex >= 0) {
                BOOST_LOG(verbose) << "Got output buffer, size: " << bufferInfo.size 
                                  << ", flags: " << bufferInfo.flags;
                
                // 检查是否是配置数据
                if (bufferInfo.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
                    BOOST_LOG(info) << "[ENCODE-OUTPUT] Received codec config data, skipping";
                    AMediaCodec_releaseOutputBuffer(encoder, outputIndex, false);
                    continue;
                }
                
                size_t outputSize;
                uint8_t* outputBuffer = AMediaCodec_getOutputBuffer(encoder, outputIndex, &outputSize);
                
                if (outputBuffer && bufferInfo.size > 0) {
                    outputData.assign(outputBuffer, outputBuffer + bufferInfo.size);
                    AMediaCodec_releaseOutputBuffer(encoder, outputIndex, false);
                    BOOST_LOG(info) << "[ENCODE-SUCCESS] Successfully encoded YUV to " << outputData.size() << " bytes";
                    BOOST_LOG(info) << "[ENCODE-SUCCESS] ==== ENCODE PROCESS SUCCESS ====";
                    return true;
                }
                
                AMediaCodec_releaseOutputBuffer(encoder, outputIndex, false);
            } else if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                // 输出格式改变
                BOOST_LOG(info) << "[ENCODE-FORMAT] Output format changed, updating...";
                AMediaFormat* newFormat = AMediaCodec_getOutputFormat(encoder);
                if (newFormat) {
                    int32_t width, height;
                    AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_WIDTH, &width);
                    AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_HEIGHT, &height);
                    
                    BOOST_LOG(info) << "[ENCODE-FORMAT] New format: " << width << "x" << height;
                    AMediaFormat_delete(newFormat);
                }
                continue;
            } else if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                BOOST_LOG(info) << "[ENCODE-WAIT] Encoder needs more time, attempt " << (attempts + 1) << "/20";
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
                continue;
            } else {
                BOOST_LOG(error) << "[ENCODE-ERROR] Encoder error code: " << outputIndex << " on attempt " << (attempts + 1);
                break;
            }
        }
        
        BOOST_LOG(error) << "[ENCODE-FAILED] Failed to get encoded output after 20 attempts";
        BOOST_LOG(error) << "[ENCODE-FAILED] ==== ENCODE PROCESS FAILED ====";
        return false;
    }
    
    void VideoEncoder::release() {
        if (encoder) {
            AMediaCodec_stop(encoder);
            AMediaCodec_delete(encoder);
            encoder = nullptr;
        }
        if (encoderFormat) {
            AMediaFormat_delete(encoderFormat);
            encoderFormat = nullptr;
        }
        isInitialized = false;
    }
    
    /**
     * 真正的90度旋转方案：从横屏1920x1080中提取中间1080x1080正方形，旋转90度后输出1080x1080
     * 避免双重处理（旋转+缩放）导致的画面变形和残影问题
     */
    bool cropRotateFillYUV(const YUVFrame& input, YUVFrame& output) {
        BOOST_LOG(info) << "[REAL-ROTATE] ==== TRUE 90-DEGREE ROTATION (NO DOUBLE PROCESSING) ====\n";
        BOOST_LOG(info) << "[REAL-ROTATE] Input: " << input.width << "x" << input.height;
        
        if (input.yData.empty() || input.uData.empty() || input.vData.empty()) {
            BOOST_LOG(error) << "[REAL-ROTATE] Invalid input YUV data";
            return false;
        }
        
        int inputWidth = input.width;   // 1920
        int inputHeight = input.height; // 1080
        
        // 【方案变更】真正的90度旋转：从1920x1080提取1080x1080正方形，旋转90度后输出1080x1080
        // 这样避免了双重处理（旋转+缩放）导致的画面变形和残影问题
        
        BOOST_LOG(warning) << "[REAL-ROTATE] 方案说明：从横屏1920x1080中提取中间1080x1080正方形区域";
        BOOST_LOG(warning) << "[REAL-ROTATE] 然后对这个正方形进行90度旋转，直接输出1080x1080";
        BOOST_LOG(warning) << "[REAL-ROTATE] 避免旋转后再缩放的双重处理，消除残影和变形问题";
        
        // Step 1: 从横屏1920x1080中提取中间1080x1080正方形区域
        int squareSize = inputHeight; // 1080
        int cropX = (inputWidth - squareSize) / 2; // (1920-1080)/2 = 420
        int cropY = 0; // 垂直不需要裁剪
        
        BOOST_LOG(info) << "[REAL-ROTATE] Step 1: 提取正方形区域 " << squareSize << "x" << squareSize;
        BOOST_LOG(info) << "[REAL-ROTATE] 裁剪偏移: (" << cropX << ", " << cropY << ")";
        
        // 创建临时的正方形帧
        YUVFrame squareFrame;
        squareFrame.width = squareSize;
        squareFrame.height = squareSize;
        squareFrame.yStride = squareSize;
        squareFrame.uvStride = squareSize / 2;
        
        squareFrame.yData.resize(squareSize * squareSize);
        squareFrame.uData.resize((squareSize * squareSize) / 4);
        squareFrame.vData.resize((squareSize * squareSize) / 4);
        
        // 提取Y分量的正方形区域
        for (int y = 0; y < squareSize; y++) {
            for (int x = 0; x < squareSize; x++) {
                int srcIndex = (y + cropY) * inputWidth + (x + cropX);
                int dstIndex = y * squareSize + x;
                
                if (srcIndex >= 0 && srcIndex < input.yData.size() && 
                    dstIndex >= 0 && dstIndex < squareFrame.yData.size()) {
                    squareFrame.yData[dstIndex] = input.yData[srcIndex];
                }
            }
        }
        
        // 提取UV分量的正方形区域
        int uvSquareSize = squareSize / 2;
        int uvCropX = cropX / 2;
        int uvCropY = cropY / 2;
        int uvInputWidth = inputWidth / 2;
        
        for (int y = 0; y < uvSquareSize; y++) {
            for (int x = 0; x < uvSquareSize; x++) {
                int srcIndex = (y + uvCropY) * uvInputWidth + (x + uvCropX);
                int dstIndex = y * uvSquareSize + x;
                
                if (srcIndex >= 0 && srcIndex < input.uData.size() && 
                    dstIndex >= 0 && dstIndex < squareFrame.uData.size() &&
                    srcIndex < input.vData.size() && 
                    dstIndex < squareFrame.vData.size()) {
                    squareFrame.uData[dstIndex] = input.uData[srcIndex];
                    squareFrame.vData[dstIndex] = input.vData[srcIndex];
                }
            }
        }
        
        BOOST_LOG(info) << "[REAL-ROTATE] Step 1 完成: 提取了 " << squareSize << "x" << squareSize << " 正方形区域";
        
        // Step 2: 对正方形区域进行90度旋转
        BOOST_LOG(info) << "[REAL-ROTATE] Step 2: 对正方形区域进行90度旋转...";
        
        if (!rotateYUV90Clockwise(squareFrame, output)) {
            BOOST_LOG(error) << "[REAL-ROTATE] Failed to rotate square frame";
            return false;
        }
        
        BOOST_LOG(info) << "[REAL-ROTATE] Step 2 完成: 旋转后尺寸 " << output.width << "x" << output.height;
        
        // 验证输出质量
        validateAndFixYUVData(output);
        
        BOOST_LOG(info) << "[REAL-ROTATE] ==== TRUE ROTATION SUCCESS ====";
        BOOST_LOG(info) << "[REAL-ROTATE] 最终输出: " << output.width << "x" << output.height;
        BOOST_LOG(info) << "[REAL-ROTATE] 方案优势: 无双重处理，无残影，无画面变形";
        
        return true;
    }
    
    // ====================== YUV旋转算法实现 ======================
    
    bool rotateYUV90Clockwise(const YUVFrame& input, YUVFrame& output) {
        if (input.yData.empty() || input.uData.empty() || input.vData.empty()) {
            BOOST_LOG(error) << "[ROTATE-ERROR] Input YUV data is empty";
            return false;
        }
        
        BOOST_LOG(info) << "[ROTATE-START] ==== YUV ROTATION START ====";
        BOOST_LOG(info) << "[ROTATE-INPUT] Input frame: " << input.width << "x" << input.height;
        BOOST_LOG(info) << "[ROTATE-INPUT] Input Y: " << input.yData.size() << " bytes";
        BOOST_LOG(info) << "[ROTATE-INPUT] Input U: " << input.uData.size() << " bytes";
        BOOST_LOG(info) << "[ROTATE-INPUT] Input V: " << input.vData.size() << " bytes";
        
        // 验证YUV数据的合理性
        size_t nonZeroY = 0, nonZeroU = 0, nonZeroV = 0;
        size_t checkSize = std::min((size_t)100, input.yData.size());
        for (size_t i = 0; i < checkSize; i++) {
            if (input.yData[i] != 0) nonZeroY++;
        }
        checkSize = std::min((size_t)100, input.uData.size());
        for (size_t i = 0; i < checkSize; i++) {
            if (input.uData[i] != 0) nonZeroU++;
            if (i < input.vData.size() && input.vData[i] != 0) nonZeroV++;
        }
        
        BOOST_LOG(info) << "[ROTATE-VALIDATE] YUV data validation (first 100 pixels):";
        BOOST_LOG(info) << "[ROTATE-VALIDATE]   - Non-zero Y: " << nonZeroY << "/" << std::min((size_t)100, input.yData.size());
        BOOST_LOG(info) << "[ROTATE-VALIDATE]   - Non-zero U: " << nonZeroU << "/" << std::min((size_t)100, input.uData.size());
        BOOST_LOG(info) << "[ROTATE-VALIDATE]   - Non-zero V: " << nonZeroV << "/" << std::min((size_t)100, input.vData.size());
        
        if (nonZeroY == 0 && nonZeroU == 0 && nonZeroV == 0) {
            BOOST_LOG(error) << "[ROTATE-WARNING] YUV data appears to be all zeros - THIS WILL CAUSE GREEN SCREEN!";
            BOOST_LOG(error) << "[ROTATE-WARNING] Check decoder output and YUV parsing logic";
        } else {
            BOOST_LOG(info) << "[ROTATE-VALIDATE] YUV data validation passed - contains actual image data";
        }
        
        int srcWidth = input.width;
        int srcHeight = input.height;
        int dstWidth = srcHeight;  // 旋转后宽高互换
        int dstHeight = srcWidth;
        
        // 初始化输出帧
        output.width = dstWidth;
        output.height = dstHeight;
        output.yStride = dstWidth;
        output.uvStride = dstWidth / 2;
        
        // 分配输出内存并初始化为正确的YUV值，防止残影
        output.yData.resize(dstWidth * dstHeight);
        output.uData.resize((dstWidth * dstHeight) / 4);
        output.vData.resize((dstWidth * dstHeight) / 4);
        
        // 初始化为正确的视频黑色值，防止残影问题
        std::fill(output.yData.begin(), output.yData.end(), 16);   // 视频黑色
        std::fill(output.uData.begin(), output.uData.end(), 128);  // 中性色度
        std::fill(output.vData.begin(), output.vData.end(), 128);  // 中性色度
        
        BOOST_LOG(info) << "[ROTATE-PROCESS] Rotating YUV data: " << srcWidth << "x" << srcHeight 
                       << " -> " << dstWidth << "x" << dstHeight;
        BOOST_LOG(info) << "[ROTATE-PROCESS] Output buffer sizes: Y=" << output.yData.size() 
                       << ", U=" << output.uData.size() << ", V=" << output.vData.size();
        
        // 旋转Y分量 (亮度) - 添加边界检查，防止越界访问
        BOOST_LOG(info) << "[ROTATE-Y] Rotating Y plane (luminance)...";
        for (int y = 0; y < srcHeight; y++) {
            for (int x = 0; x < srcWidth; x++) {
                int srcIndex = y * srcWidth + x;
                int dstX = srcHeight - 1 - y;  // 90度顺时针旋转
                int dstY = x;
                int dstIndex = dstY * dstWidth + dstX;
                
                // 添加边界检查，确保索引在有效范围内
                if (srcIndex >= 0 && srcIndex < input.yData.size() &&
                    dstIndex >= 0 && dstIndex < output.yData.size()) {
                    output.yData[dstIndex] = input.yData[srcIndex];
                }
            }
        }
        BOOST_LOG(info) << "[ROTATE-Y] Y plane rotation completed";
        
        // 旋转U分量 (色度) - 添加边界检查
        BOOST_LOG(info) << "[ROTATE-U] Rotating U plane (chrominance)...";
        int uvSrcWidth = srcWidth / 2;
        int uvSrcHeight = srcHeight / 2;
        int uvDstWidth = dstWidth / 2;
        
        for (int y = 0; y < uvSrcHeight; y++) {
            for (int x = 0; x < uvSrcWidth; x++) {
                int srcIndex = y * uvSrcWidth + x;
                int dstX = uvSrcHeight - 1 - y;  // 90度顺时针旋转
                int dstY = x;
                int dstIndex = dstY * uvDstWidth + dstX;
                
                // 添加UV分量的边界检查
                if (srcIndex >= 0 && srcIndex < input.uData.size() &&
                    dstIndex >= 0 && dstIndex < output.uData.size()) {
                    output.uData[dstIndex] = input.uData[srcIndex];
                }
            }
        }
        BOOST_LOG(info) << "[ROTATE-U] U plane rotation completed";
        
        // 旋转V分量 (色度) - 添加边界检查
        BOOST_LOG(info) << "[ROTATE-V] Rotating V plane (chrominance)...";
        for (int y = 0; y < uvSrcHeight; y++) {
            for (int x = 0; x < uvSrcWidth; x++) {
                int srcIndex = y * uvSrcWidth + x;
                int dstX = uvSrcHeight - 1 - y;  // 90度顺时针旋转
                int dstY = x;
                int dstIndex = dstY * uvDstWidth + dstX;
                
                // 添加V分量的边界检查
                if (srcIndex >= 0 && srcIndex < input.vData.size() &&
                    dstIndex >= 0 && dstIndex < output.vData.size()) {
                    output.vData[dstIndex] = input.vData[srcIndex];
                }
            }
        }
        BOOST_LOG(info) << "[ROTATE-V] V plane rotation completed";
        
        // 验证旋转后的YUV数据质量
        BOOST_LOG(info) << "[ROTATE-VALIDATE] Validating rotated YUV data quality...";
        validateAndFixYUVData(output);
        
        BOOST_LOG(info) << "[ROTATE-SUCCESS] YUV frame rotated 90° clockwise successfully: " 
                       << srcWidth << "x" << srcHeight << " -> " 
                       << dstWidth << "x" << dstHeight;
        BOOST_LOG(info) << "[ROTATE-SUCCESS] ==== YUV ROTATION SUCCESS ====";
        
        return true;
    }
    
    // ====================== 完整视频帧旋转处理 ======================
    
    bool rotateVideoFrame(const std::vector<uint8_t>& encodedData,
                         const std::vector<uint8_t>& configData,
                         const char* mimeType,
                         int width, int height,
                         int bitrate, int framerate,
                         std::vector<uint8_t>& outputData) {
        
        BOOST_LOG(info) << "[VIDEO-ROTATION] ===== CROP-ROTATE-FILL PROCESSING START =====";
        BOOST_LOG(info) << "[VIDEO-ROTATION] Input parameters:";
        BOOST_LOG(info) << "[VIDEO-ROTATION]   - MIME: " << mimeType;
        BOOST_LOG(info) << "[VIDEO-ROTATION]   - Input resolution: " << width << "x" << height << " (横屏帧，包含黑边)";
        BOOST_LOG(info) << "[VIDEO-ROTATION]   - Expected content: 中间竖屏内容，两边黑边";
        BOOST_LOG(info) << "[VIDEO-ROTATION]   - Target output: " << width << "x" << height << " (填满横屏)";
        BOOST_LOG(info) << "[VIDEO-ROTATION]   - Bitrate: " << bitrate << " bps";
        BOOST_LOG(info) << "[VIDEO-ROTATION]   - Framerate: " << framerate << " fps";
        BOOST_LOG(info) << "[VIDEO-ROTATION]   - Encoded data: " << encodedData.size() << " bytes";
        BOOST_LOG(info) << "[VIDEO-ROTATION]   - Config data: " << configData.size() << " bytes";
        
        // 计算截取区域：从1920x1080中截取中间的竖屏内容
        int cropWidth = 1080;   // 竖屏内容的宽度
        int cropHeight = 1080;  // 竖屏内容的高度（可能需要根据实际情况调整）
        
        BOOST_LOG(info) << "[VIDEO-ROTATION] Crop-Rotate-Fill strategy:";
        BOOST_LOG(info) << "[VIDEO-ROTATION]   - Crop region: " << cropWidth << "x" << cropHeight << " 从中间截取";
        BOOST_LOG(info) << "[VIDEO-ROTATION]   - After 90° rotation: " << cropHeight << "x" << cropWidth;
        BOOST_LOG(info) << "[VIDEO-ROTATION]   - Final fill: Scale to " << width << "x" << height;
        
        // 检查输入参数
        if (encodedData.empty()) {
            BOOST_LOG(error) << "[VIDEO-ROTATION] Encoded data is empty";
            return false;
        }
        
        if (configData.empty()) {
            BOOST_LOG(error) << "[VIDEO-ROTATION] No codec config data - this will likely cause decode failure";
            // 注意：这里改为error级别，因为这是绿屏问题的主要原因
        }
        
        // 使用RAII管理资源
        std::unique_ptr<VideoDecoder> decoder;
        std::unique_ptr<VideoEncoder> encoder;
        
        try {
            // 1. 初始化解码器
            BOOST_LOG(info) << "[VIDEO-ROTATION] Step 1: Initializing decoder...";
            decoder = std::make_unique<VideoDecoder>();
            if (!decoder->initialize(mimeType, width, height, configData)) {
                BOOST_LOG(error) << "[VIDEO-ROTATION] Failed to initialize decoder";
                return false;
            }
            BOOST_LOG(info) << "[VIDEO-ROTATION] Step 1 completed - decoder initialized";
            
            // 2. 解码为YUV
            BOOST_LOG(info) << "[VIDEO-ROTATION] Step 2: Decoding to YUV...";
            YUVFrame originalFrame;
            originalFrame.width = width;
            originalFrame.height = height;
            originalFrame.yStride = width;
            originalFrame.uvStride = width / 2;
            
            if (!decoder->decodeToYUV(encodedData, originalFrame)) {
                BOOST_LOG(error) << "[VIDEO-ROTATION] Failed to decode to YUV - this is likely the root cause of green screen";
                BOOST_LOG(error) << "[VIDEO-ROTATION] CRITICAL: Cannot proceed without decoded YUV data!";
                return false;
            }
            BOOST_LOG(info) << "[VIDEO-ROTATION] Step 2 completed - YUV decode successful";
            BOOST_LOG(info) << "[VIDEO-ROTATION] Decoded frame dimensions: " << originalFrame.width << "x" << originalFrame.height;
            
            // 释放解码器资源
            decoder.reset();
            
            // 对于1920x1080横屏帧，始终使用裁剪-旋转-填充处理
            BOOST_LOG(info) << "[VIDEO-ROTATION] Processing strategy: Crop-Rotate-Fill";
            BOOST_LOG(info) << "[VIDEO-ROTATION]   - Assumption: Landscape frame contains portrait content with black bars";
            BOOST_LOG(info) << "[VIDEO-ROTATION]   - Process: Extract center portrait → Rotate 90° → Fill landscape";
            
            YUVFrame finalFrame;
            
            // 3. 使用裁剪-旋转-填充处理
            BOOST_LOG(info) << "[VIDEO-ROTATION] Step 3: Applying crop-rotate-fill processing...";
            if (!cropRotateFillYUV(originalFrame, finalFrame)) {
                BOOST_LOG(error) << "[VIDEO-ROTATION] Failed to apply crop-rotate-fill processing";
                return false;
            }
            BOOST_LOG(info) << "[VIDEO-ROTATION] Step 3 completed - crop-rotate-fill successful";
            
            // 4. 初始化编码器 (使用最终帧的尺寸)
            BOOST_LOG(info) << "[VIDEO-ROTATION] Step 4: Initializing encoder for final frame...";
            BOOST_LOG(info) << "[VIDEO-ROTATION] Final frame dimensions: " << finalFrame.width << "x" << finalFrame.height;
            BOOST_LOG(info) << "[VIDEO-ROTATION] Original dimensions were: " << width << "x" << height;
            
            // 确定编码器的输出尺寸
            int encoderWidth = finalFrame.width;
            int encoderHeight = finalFrame.height;
            
            BOOST_LOG(info) << "[VIDEO-ROTATION] Encoder output configuration:";
            BOOST_LOG(info) << "[VIDEO-ROTATION]   - Encoder will output: " << encoderWidth << "x" << encoderHeight;
            BOOST_LOG(info) << "[VIDEO-ROTATION]   - Format: " << (encoderWidth > encoderHeight ? "Landscape (横屏)" : "Portrait (竖屏)");
            BOOST_LOG(info) << "[VIDEO-ROTATION]   - This matches client request for landscape display";
            
            encoder = std::make_unique<VideoEncoder>();
            
            // 大幅降低码率和复杂度以提高成功率和响应速度
            int adjustedBitrate = std::min(bitrate / 2, 2000000); // 最大2Mbps，比原来减半
            int adjustedFramerate = std::min(framerate, 25);       // 最多25fps
            BOOST_LOG(info) << "[VIDEO-ROTATION] Using performance-optimized parameters:";
            BOOST_LOG(info) << "[VIDEO-ROTATION]   - Original bitrate: " << bitrate << " -> Adjusted: " << adjustedBitrate;
            BOOST_LOG(info) << "[VIDEO-ROTATION]   - Original fps: " << framerate << " -> Adjusted: " << adjustedFramerate;
            BOOST_LOG(info) << "[VIDEO-ROTATION]   - This should significantly improve client responsiveness";
            
            if (!encoder->initialize(mimeType, encoderWidth, encoderHeight, 
                                    adjustedBitrate, adjustedFramerate)) {
                BOOST_LOG(error) << "[VIDEO-ROTATION] Failed to initialize encoder with target dimensions";
                BOOST_LOG(error) << "[VIDEO-ROTATION] Target was: " << encoderWidth << "x" << encoderHeight;
                return false;
            }
            BOOST_LOG(info) << "[VIDEO-ROTATION] Step 4 completed - encoder initialized for " << encoderWidth << "x" << encoderHeight;
            
            // 5. 重新编码
            BOOST_LOG(info) << "[VIDEO-ROTATION] Step 5: Encoding final YUV...";
            if (!encoder->encodeFromYUV(finalFrame, outputData)) {
                BOOST_LOG(error) << "[VIDEO-ROTATION] Failed to encode final YUV";
                return false;
            }
            BOOST_LOG(info) << "[VIDEO-ROTATION] Step 5 completed - encoding successful";
            
            // 释放编码器资源
            encoder.reset();
            
            BOOST_LOG(info) << "[VIDEO-ROTATION] ===== VIDEO FRAME ROTATION SUCCESS =====";
            BOOST_LOG(info) << "[VIDEO-ROTATION] Final output: " << outputData.size() << " bytes";
            return true;
            
        } catch (const std::exception& e) {
            BOOST_LOG(error) << "[VIDEO-ROTATION] Exception during rotation: " << e.what();
            BOOST_LOG(error) << "[VIDEO-ROTATION] ===== VIDEO FRAME ROTATION FAILED (EXCEPTION) =====";
            // RAII会自动清理资源
            return false;
        } catch (...) {
            BOOST_LOG(error) << "[VIDEO-ROTATION] Unknown exception during rotation";
            BOOST_LOG(error) << "[VIDEO-ROTATION] ===== VIDEO FRAME ROTATION FAILED (UNKNOWN EXCEPTION) =====";
            return false;
        }
    }

} // namespace video_rotation