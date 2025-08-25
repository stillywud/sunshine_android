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
    
    // 存储编码配置信息
    static std::vector<uint8_t> cachedConfigData;
    static std::string cachedMimeType = "video/avc"; // 默认H.264
    static int cachedWidth = 1080;
    static int cachedHeight = 1920;
    static int cachedBitrate = 5000000; // 5Mbps
    static int cachedFramerate = 60;
    
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
        
        // 检查是否需要旋转：如果已经是横屏格式，直接返回原始数据
        bool needRotation = (cachedWidth < cachedHeight); // 竖屏需要旋转
        
        BOOST_LOG(info) << "[ROTATION-ANALYSIS] Frame format analysis:";
        BOOST_LOG(info) << "[ROTATION-ANALYSIS]   - Resolution: " << cachedWidth << "x" << cachedHeight;
        BOOST_LOG(info) << "[ROTATION-ANALYSIS]   - Format: " << (needRotation ? "Portrait (竖屏, 需要旋转)" : "Landscape (横屏, 无需旋转)");
        BOOST_LOG(info) << "[ROTATION-ANALYSIS]   - Action: " << (needRotation ? "Will rotate" : "Will skip rotation");
        
        if (!needRotation) {
            BOOST_LOG(info) << "[ROTATION-SKIP] Frame is already landscape format, skipping rotation processing";
            BOOST_LOG(info) << "[ROTATION-SKIP] Using original frame data to avoid unnecessary decode/encode";
            outputData = encodedData;
            processedFrames++; // 计为处理成功
            
            BOOST_LOG(info) << "[ROTATION-SUCCESS] Frame " << totalFrames << " processed successfully (no rotation needed)!";
            BOOST_LOG(info) << "[ROTATION-SUCCESS]   - Processing time: 0ms (skipped)";
            BOOST_LOG(info) << "[ROTATION-SUCCESS]   - Input size: " << encodedData.size() << " bytes";
            BOOST_LOG(info) << "[ROTATION-SUCCESS]   - Output size: " << outputData.size() << " bytes";
            BOOST_LOG(info) << "[ROTATION-SUCCESS] ==== Frame " << totalFrames << " Processing END (SKIPPED) ====";
            
            return true;
        }
        
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
            
            BOOST_LOG(info) << "[ROTATION-PROCESS] Starting video_rotation::rotateVideoFrame...";
            
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
            
            if (rotationSuccess && !rotatedData.empty()) {
                outputData = rotatedData;
                processedFrames++;
                
                BOOST_LOG(info) << "[ROTATION-SUCCESS] Frame " << totalFrames << " rotated successfully!";
                BOOST_LOG(info) << "[ROTATION-SUCCESS]   - Processing time: " << duration.count() << "ms";
                BOOST_LOG(info) << "[ROTATION-SUCCESS]   - Input size: " << encodedData.size() << " bytes";
                BOOST_LOG(info) << "[ROTATION-SUCCESS]   - Output size: " << outputData.size() << " bytes";
                BOOST_LOG(info) << "[ROTATION-SUCCESS] ==== Frame " << totalFrames << " Processing END (SUCCESS) ====";
                
                // 每10帧记录一次统计信息
                if (totalFrames % 10 == 0) {
                    BOOST_LOG(info) << "[ROTATION-STATS] Progress: " << processedFrames << "/" << totalFrames 
                                   << " frames successfully rotated, avg time: " << duration.count() << "ms";
                }
                
                return true;
            } else {
                BOOST_LOG(error) << "[ROTATION-FAILED] Frame " << totalFrames << " rotation FAILED!";
                BOOST_LOG(error) << "[ROTATION-FAILED]   - rotationSuccess: " << (rotationSuccess ? "true" : "false");
                BOOST_LOG(error) << "[ROTATION-FAILED]   - rotatedData.empty(): " << (rotatedData.empty() ? "true" : "false");
                BOOST_LOG(error) << "[ROTATION-FAILED]   - Processing time: " << duration.count() << "ms";
                BOOST_LOG(error) << "[ROTATION-FAILED]   - Using original frame as fallback";
                outputData = encodedData;
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