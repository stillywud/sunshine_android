#ifndef VIDEO_ROTATION_H
#define VIDEO_ROTATION_H

#include <vector>
#include <cstdint>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

/**
 * 视频旋转处理模块
 * 用于实现视频帧的90度旋转功能
 */

namespace video_rotation {

    /**
     * YUV数据结构
     */
    struct YUVFrame {
        std::vector<uint8_t> yData;    // Y分量数据
        std::vector<uint8_t> uData;    // U分量数据  
        std::vector<uint8_t> vData;    // V分量数据
        int width;                     // 图像宽度
        int height;                    // 图像高度
        int yStride;                   // Y分量行步长
        int uvStride;                  // UV分量行步长
    };

    // ====================== YUV格式解析辅助函数 ======================
    
    /**
     * 解析I420格式的YUV数据
     */
    bool parseI420Format(uint8_t* buffer, size_t bufferSize, YUVFrame& outputFrame, 
                        int stride, int sliceHeight);

    /**
     * 解码器管理类
     */
    class VideoDecoder {
    private:
        AMediaCodec* decoder;
        AMediaFormat* decoderFormat;
        bool isInitialized;
        
    public:
        VideoDecoder();
        ~VideoDecoder();
        
        /**
         * 初始化解码器
         * @param mimeType 编码格式 ("video/avc" 或 "video/hevc")
         * @param width 视频宽度
         * @param height 视频高度
         * @param configData SPS/PPS配置数据
         * @return 成功返回true
         */
        bool initialize(const char* mimeType, int width, int height, 
                       const std::vector<uint8_t>& configData);
        
        /**
         * 解码H.264/HEVC数据为YUV
         * @param encodedData 编码后的数据
         * @param outputFrame 输出的YUV帧数据
         * @return 成功返回true
         */
        bool decodeToYUV(const std::vector<uint8_t>& encodedData, YUVFrame& outputFrame);
        
        /**
         * 释放资源
         */
        void release();
    };

    /**
     * 编码器管理类
     */
    class VideoEncoder {
    private:
        AMediaCodec* encoder;
        AMediaFormat* encoderFormat;
        bool isInitialized;
        
    public:
        VideoEncoder();
        ~VideoEncoder();
        
        /**
         * 初始化编码器
         * @param mimeType 编码格式
         * @param width 视频宽度
         * @param height 视频高度
         * @param bitrate 比特率
         * @param framerate 帧率
         * @return 成功返回true
         */
        bool initialize(const char* mimeType, int width, int height, 
                       int bitrate, int framerate);
        
        /**
         * 将YUV数据编码为H.264/HEVC
         * @param yuvFrame YUV帧数据
         * @param outputData 编码后的数据
         * @return 成功返回true
         */
        bool encodeFromYUV(const YUVFrame& yuvFrame, std::vector<uint8_t>& outputData);
        
        /**
         * 释放资源
         */
        void release();
    };

    /**
     * YUV数据90度顺时针旋转
     * @param input 输入的YUV帧
     * @param output 旋转后的YUV帧
     * @return 成功返回true
     */
    bool rotateYUV90Clockwise(const YUVFrame& input, YUVFrame& output);

    /**
     * 裁剪-旋转-填充YUV数据处理
     * 从1920x1080横屏帧中截取中间的竖屏内容，旋转90°后重新填满1920x1080
     * @param input 输入的横屏帧（包含黑边）
     * @param output 输出的横屏帧（填满内容）
     * @return 成功返回true
     */
    bool cropRotateFillYUV(const YUVFrame& input, YUVFrame& output);

    /**
     * 完整的视频帧旋转处理
     * @param encodedData 输入的编码数据
     * @param configData SPS/PPS配置数据
     * @param mimeType 编码格式
     * @param width 原始宽度
     * @param height 原始高度
     * @param bitrate 比特率
     * @param framerate 帧率
     * @param outputData 旋转后重新编码的数据
     * @return 成功返回true
     */
    bool rotateVideoFrame(const std::vector<uint8_t>& encodedData,
                         const std::vector<uint8_t>& configData,
                         const char* mimeType,
                         int width, int height,
                         int bitrate, int framerate,
                         std::vector<uint8_t>& outputData);

} // namespace video_rotation

#endif // VIDEO_ROTATION_H