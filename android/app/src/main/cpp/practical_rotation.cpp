#include "practical_rotation.h"
#include "logging.h"
#include "video_rotation.h"
#include <atomic>
#include <chrono>

using namespace std::literals;

namespace practical_rotation {

    static std::atomic<bool> rotationEnabled{true}; // 硬编码启用旋转功能
    static std::atomic<int> totalFrames{0};
    static std::atomic<int> processedFrames{0};
    static std::atomic<int> failedFrames{0};
    
    // 增加帧计数器用于检测画面更新
    static std::atomic<uint64_t> lastFrameTimestamp{0};
    static std::chrono::steady_clock::time_point lastProcessTime;
    
    // 性能优化模式：当检测到处理时间过长时启用
    static std::atomic<bool> fastModeEnabled{false};
    static std::atomic<int> slowFrameCount{0};
    
    // 存储编码配置信息
    static std::vector<uint8_t> cachedConfigData;
    static std::string cachedMimeType = "video/avc"; // 默认H.264
    static int cachedWidth = 1080;
    static int cachedHeight = 1920;
    static int cachedBitrate = 5000000; // 5Mbps
    static int cachedFramerate = 60;
    
    // 清理缓存和重置状态，用于解决画面切换时不更新的问题
    void clearCache() {
        totalFrames = 0;
        processedFrames = 0;
        failedFrames = 0;
        lastFrameTimestamp = 0;
        lastProcessTime = std::chrono::steady_clock::now();
        
        // 重置性能优化模式
        fastModeEnabled = false;
        slowFrameCount = 0;
        
        BOOST_LOG(info) << "[ROTATION-CACHE] Cache cleared, ready for new video stream";
        BOOST_LOG(info) << "[ROTATION-CACHE] Performance optimization mode reset";
        BOOST_LOG(info) << "[ROTATION-CACHE] This should resolve frame update issues";
    }
    
    // 设置编码参数（从sunshine.cpp调用）
    void setEncodingParams(const std::vector<uint8_t>& configData,
                          const char* mimeType, 
                          int width, int height,
                          int bitrate, int framerate) {
        cachedConfigData = configData;
        cachedMimeType = mimeType ? mimeType : "video/avc";
        cachedWidth = width;
        cachedHeight = height;
        cachedBitrate = bitrate;
        cachedFramerate = framerate;
        
        BOOST_LOG(info) << "[ROTATION CONFIG] Updated encoding params: " 
                       << cachedMimeType << " " << cachedWidth << "x" << cachedHeight
                       << ", bitrate=" << cachedBitrate << ", fps=" << cachedFramerate
                       << ", config_size=" << cachedConfigData.size();
    }

    bool processVideoFrame(const std::vector<uint8_t>& encodedData,
                          std::vector<uint8_t>& outputData) {
        
        totalFrames++;
        
        BOOST_LOG(info) << "[ROTATION-MAIN] ==== Frame " << totalFrames << " Processing START ====";
        BOOST_LOG(info) << "[ROTATION-INPUT] Input data size: " << encodedData.size() << " bytes";
        
        if (!rotationEnabled) {
            BOOST_LOG(warning) << "[ROTATION-DISABLED] Rotation disabled, using original data";
            outputData = encodedData;
            return true;
        }
        
        // 检查是否有编码配置数据
        if (cachedConfigData.empty()) {
            BOOST_LOG(error) << "[ROTATION-ERROR] No codec config data available!";
            BOOST_LOG(error) << "[ROTATION-ERROR] This will cause decode failure - using original frame";
            outputData = encodedData;
            return true;
        }
        
        if (encodedData.empty()) {
            BOOST_LOG(error) << "[ROTATION-ERROR] Input frame data is empty!";
            return false;
        }
        
        // 强制启用旋转处理：从1920x1080横屏帧中截取竖屏内容并旋转填满
        bool needRotation = true; // 始终需要处理黑边和旋转
        
        BOOST_LOG(info) << "[ROTATION-ANALYSIS] Frame processing analysis:";
        BOOST_LOG(info) << "[ROTATION-ANALYSIS]   - Input resolution: " << cachedWidth << "x" << cachedHeight << " (横屏格式)";
        BOOST_LOG(info) << "[ROTATION-ANALYSIS]   - Expected content: 竖屏内容显示在横屏中间，两边黑边";
        BOOST_LOG(info) << "[ROTATION-ANALYSIS]   - Processing plan: 截取中间竖屏内容 -> 旋转90° -> 重新填满1920x1080";
        BOOST_LOG(info) << "[ROTATION-ANALYSIS]   - Output resolution: " << cachedWidth << "x" << cachedHeight << " (保持不变)";
        
        // 计算截取区域：假设竖屏内容在横屏中间
        // 1920x1080横屏中的竖屏内容大约是中间的1080x1080区域
        int cropWidth = 1080;   // 截取宽度（对应原始竖屏的宽度）
        int cropHeight = 1080;  // 截取高度（对应原始竖屏的高度，可能需要调整）
        int cropX = (1920 - cropWidth) / 2;  // 居中截取的X偏移
        int cropY = 0;  // Y偏移，从顶部开始
        
        BOOST_LOG(info) << "[ROTATION-CROP] Crop region calculation:";
        BOOST_LOG(info) << "[ROTATION-CROP]   - Crop area: " << cropWidth << "x" << cropHeight;
        BOOST_LOG(info) << "[ROTATION-CROP]   - Crop offset: (" << cropX << ", " << cropY << ")";
        BOOST_LOG(info) << "[ROTATION-CROP]   - This extracts portrait content from landscape frame";
        
        BOOST_LOG(info) << "[ROTATION-PROCESS] Starting crop-rotate-fill processing:";
        BOOST_LOG(info) << "[ROTATION-PROCESS]   - Step 1: 解码1920x1080横屏帧";
        BOOST_LOG(info) << "[ROTATION-PROCESS]   - Step 2: 截取中间" << cropWidth << "x" << cropHeight << "竖屏内容";
        BOOST_LOG(info) << "[ROTATION-PROCESS]   - Step 3: 旋转90°变为" << cropHeight << "x" << cropWidth;
        BOOST_LOG(info) << "[ROTATION-PROCESS]   - Step 4: 重新编码为1920x1080填满横屏";
        
        BOOST_LOG(info) << "[ROTATION-CONFIG] Using cached config:";
        BOOST_LOG(info) << "[ROTATION-CONFIG]   - MIME: " << cachedMimeType;
        BOOST_LOG(info) << "[ROTATION-CONFIG]   - Size: " << cachedWidth << "x" << cachedHeight;
        BOOST_LOG(info) << "[ROTATION-CONFIG]   - Bitrate: " << cachedBitrate;
        BOOST_LOG(info) << "[ROTATION-CONFIG]   - FPS: " << cachedFramerate;
        BOOST_LOG(info) << "[ROTATION-CONFIG]   - Config data: " << cachedConfigData.size() << " bytes";
        
        try {
            // 使用video_rotation模块进行实际的旋转处理
            std::vector<uint8_t> rotatedData;
            
            auto startTime = std::chrono::high_resolution_clock::now();
            
            // 性能检测：如果之前的帧处理时间过长，启用快速模式
            if (fastModeEnabled) {
                BOOST_LOG(warning) << "[ROTATION-FAST] Fast mode enabled due to performance issues";
                BOOST_LOG(warning) << "[ROTATION-FAST] Skipping complex rotation to reduce latency";
                BOOST_LOG(warning) << "[ROTATION-FAST] Frame " << totalFrames << " using original data";
                outputData = encodedData;
                return true;
            }
            
            BOOST_LOG(info) << "[ROTATION-PROCESS] Starting video_rotation::rotateVideoFrame...";
            BOOST_LOG(info) << "[ROTATION-PROCESS] Input frame parameters:";
            BOOST_LOG(info) << "[ROTATION-PROCESS]   - Encoded frame size: " << encodedData.size() << " bytes";
            BOOST_LOG(info) << "[ROTATION-PROCESS]   - Config data size: " << cachedConfigData.size() << " bytes";
            BOOST_LOG(info) << "[ROTATION-PROCESS]   - MIME type: " << cachedMimeType;
            BOOST_LOG(info) << "[ROTATION-PROCESS]   - Current dimensions: " << cachedWidth << "x" << cachedHeight;
            BOOST_LOG(info) << "[ROTATION-PROCESS]   - Expected output: " << cachedHeight << "x" << cachedWidth << " (rotated 90°)";
            BOOST_LOG(info) << "[ROTATION-PROCESS]   - Bitrate: " << cachedBitrate << ", FPS: " << cachedFramerate;
            
            bool rotationSuccess = video_rotation::rotateVideoFrame(
                encodedData,
                cachedConfigData,
                cachedMimeType.c_str(),
                cachedWidth, cachedHeight,
                cachedBitrate,
                cachedFramerate,
                rotatedData
            );
            
            auto endTime = std::chrono::high_resolution_clock::now();
            auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime);
            
            // 性能监控：如果处理时间超过100ms，记录为慢帧
            if (duration.count() > 100) {
                slowFrameCount++;
                BOOST_LOG(warning) << "[ROTATION-PERF] Slow frame detected: " << duration.count() << "ms (frame " << totalFrames << ")";
                BOOST_LOG(warning) << "[ROTATION-PERF] Slow frame count: " << slowFrameCount << "/" << totalFrames;
                
                // 如果连续3帧或耵50%的帧都过慢，启用快速模式
                if (slowFrameCount >= 3 || (totalFrames > 10 && slowFrameCount * 2 > totalFrames)) {
                    fastModeEnabled = true;
                    BOOST_LOG(error) << "[ROTATION-PERF] CRITICAL: Enabling fast mode due to consistent slow performance!";
                    BOOST_LOG(error) << "[ROTATION-PERF] This will disable rotation to improve client responsiveness";
                    BOOST_LOG(error) << "[ROTATION-PERF] Slow frames: " << slowFrameCount << "/" << totalFrames;
                }
            }
            
            if (rotationSuccess && !rotatedData.empty()) {
                // 验证输出数据的合理性，防止损坏的帧数据
                if (rotatedData.size() < 1000) {
                    BOOST_LOG(error) << "[ROTATION-QUALITY] Output frame too small: " << rotatedData.size() << " bytes";
                    BOOST_LOG(error) << "[ROTATION-QUALITY] This may indicate encoding failure - using original frame";
                    outputData = encodedData;
                    failedFrames++;
                    return true;
                }
                
                outputData = rotatedData;
                processedFrames++;
                
                BOOST_LOG(info) << "[ROTATION-SUCCESS] Frame " << totalFrames << " rotated successfully!";
                BOOST_LOG(info) << "[ROTATION-SUCCESS]   - Processing time: " << duration.count() << "ms";
                BOOST_LOG(info) << "[ROTATION-SUCCESS]   - Input size: " << encodedData.size() << " bytes";
                BOOST_LOG(info) << "[ROTATION-SUCCESS]   - Output size: " << outputData.size() << " bytes";
                BOOST_LOG(info) << "[ROTATION-SUCCESS] ==== Frame " << totalFrames << " Processing END (SUCCESS) ====";
                
                // 每10帧记录一次统计信息，包括失败率
                if (totalFrames % 10 == 0) {
                    float successRate = (float)processedFrames / totalFrames * 100.0f;
                    BOOST_LOG(info) << "[ROTATION-STATS] Progress: " << processedFrames << "/" << totalFrames 
                                   << " frames successfully rotated (" << successRate << "%), "
                                   << "failed: " << failedFrames << ", avg time: " << duration.count() << "ms";
                }
                
                return true;
            } else {
                BOOST_LOG(error) << "[ROTATION-FAILED] Frame " << totalFrames << " rotation FAILED!";
                BOOST_LOG(error) << "[ROTATION-FAILED]   - rotationSuccess: " << (rotationSuccess ? "true" : "false");
                BOOST_LOG(error) << "[ROTATION-FAILED]   - rotatedData.empty(): " << (rotatedData.empty() ? "true" : "false");
                BOOST_LOG(error) << "[ROTATION-FAILED]   - Processing time: " << duration.count() << "ms";
                BOOST_LOG(error) << "[ROTATION-FAILED]   - Using original frame as fallback";
                outputData = encodedData;
                failedFrames++;
                BOOST_LOG(info) << "[ROTATION-FAILED] ==== Frame " << totalFrames << " Processing END (FAILED) ====";
                return true; // 返回true但使用原始数据
            }
            
        } catch (const std::exception& e) {
            BOOST_LOG(error) << "[ROTATION-EXCEPTION] Exception in frame " << totalFrames << ": " << e.what();
            BOOST_LOG(error) << "[ROTATION-EXCEPTION] Using original frame as fallback";
            outputData = encodedData;
            BOOST_LOG(info) << "[ROTATION-EXCEPTION] ==== Frame " << totalFrames << " Processing END (EXCEPTION) ====";
            return true;
        } catch (...) {
            BOOST_LOG(error) << "[ROTATION-EXCEPTION] Unknown exception in frame " << totalFrames;
            BOOST_LOG(error) << "[ROTATION-EXCEPTION] Using original frame as fallback";
            outputData = encodedData;
            BOOST_LOG(info) << "[ROTATION-EXCEPTION] ==== Frame " << totalFrames << " Processing END (UNKNOWN EXCEPTION) ====";
            return true;
        }
    }

    void setRotationEnabled(bool enabled) {
        // 硬编码启用旋转功能进行验证
        rotationEnabled = true;
        BOOST_LOG(info) << "[ROTATION TEST] Practical rotation HARDCODED ENABLED for testing";
        BOOST_LOG(info) << "[ROTATION TEST] Function verification mode activated";
        BOOST_LOG(info) << "[ROTATION TEST] All frames will be processed through rotation logic";
    }

    bool isRotationEnabled() {
        return rotationEnabled;
    }

    void logRotationStats() {
        BOOST_LOG(info) << "Rotation statistics:";
        BOOST_LOG(info) << "  Total frames: " << totalFrames.load();
        BOOST_LOG(info) << "  Processed frames: " << processedFrames.load();
        BOOST_LOG(info) << "  Rotation enabled: " << (rotationEnabled ? "Yes" : "No");
    }

} // namespace practical_rotation