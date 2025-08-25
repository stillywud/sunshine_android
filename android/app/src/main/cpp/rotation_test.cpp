#include "video_rotation.h"
#include "logging.h"

/**
 * 简化的测试方案，跳过解码，直接使用模拟的YUV数据测试旋转逻辑
 */
namespace video_rotation_test {

    bool testRotationWithMockData(int width, int height, std::vector<uint8_t>& outputData) {
        BOOST_LOG(info) << "Testing rotation with mock YUV data: " << width << "x" << height;
        
        // 创建模拟的YUV数据
        video_rotation::YUVFrame mockFrame;
        mockFrame.width = width;
        mockFrame.height = height;
        mockFrame.yStride = width;
        mockFrame.uvStride = width / 2;
        
        // 分配YUV数据
        int frameSize = width * height;
        int uvSize = frameSize / 4;
        
        mockFrame.yData.resize(frameSize);
        mockFrame.uData.resize(uvSize);
        mockFrame.vData.resize(uvSize);
        
        // 填充测试模式 - 渐变色
        for (int i = 0; i < frameSize; i++) {
            mockFrame.yData[i] = (uint8_t)(i % 256);
        }
        for (int i = 0; i < uvSize; i++) {
            mockFrame.uData[i] = (uint8_t)(128 + (i % 128));
            mockFrame.vData[i] = (uint8_t)(64 + (i % 128));
        }
        
        BOOST_LOG(info) << "Mock YUV data created, Y=" << mockFrame.yData.size() 
                       << ", U=" << mockFrame.uData.size() << ", V=" << mockFrame.vData.size();
        
        // 测试旋转
        video_rotation::YUVFrame rotatedFrame;
        if (!video_rotation::rotateYUV90Clockwise(mockFrame, rotatedFrame)) {
            BOOST_LOG(error) << "Rotation test failed";
            return false;
        }
        
        BOOST_LOG(info) << "Rotation test successful, rotated frame: " 
                       << rotatedFrame.width << "x" << rotatedFrame.height;
        
        // 简单地将旋转后的Y数据作为输出（测试用）
        outputData = rotatedFrame.yData;
        
        return true;
    }
    
    /**
     * 更简单的视频帧处理方案，暂时跳过解码
     */
    bool simpleRotateVideoFrame(const std::vector<uint8_t>& encodedData,
                               const std::vector<uint8_t>& configData,
                               const char* mimeType,
                               int width, int height,
                               int bitrate, int framerate,
                               std::vector<uint8_t>& outputData) {
        
        BOOST_LOG(info) << "Using simplified rotation (no decode/encode)";
        
        // 暂时跳过实际的解码/编码，使用模拟数据测试旋转逻辑
        if (testRotationWithMockData(width, height, outputData)) {
            BOOST_LOG(info) << "Simplified rotation test passed";
            return true;
        } else {
            BOOST_LOG(error) << "Simplified rotation test failed";
            return false;
        }
    }

} // namespace video_rotation_test