#ifndef PRACTICAL_ROTATION_H
#define PRACTICAL_ROTATION_H

#include <vector>
#include <cstdint>

/**
 * 实用的视频旋转方案
 * 
 * 由于完整的解码-旋转-编码流程对性能要求很高，
 * 这里提供一个更实用的方案：
 * 1. 暂时跳过实时旋转，保持原始帧数据
 * 2. 在虚拟显示层面进行优化
 * 3. 或使用硬件加速方案
 */

namespace practical_rotation {

    /**
     * 实用的视频帧处理方案
     * 
     * @param encodedData 输入的编码数据
     * @param outputData 输出数据（旋转后的数据）
     * @return 是否成功处理
     */
    bool processVideoFrame(const std::vector<uint8_t>& encodedData,
                          std::vector<uint8_t>& outputData);

    /**
     * 设置编码参数（由sunshine.cpp调用）
     * @param configData 编码器配置数据（SPS/PPS）
     * @param mimeType 编码格式
     * @param width 视频宽度
     * @param height 视频高度
     * @param bitrate 码率
     * @param framerate 帧率
     */
    void setEncodingParams(const std::vector<uint8_t>& configData,
                          const char* mimeType, 
                          int width, int height,
                          int bitrate, int framerate);

    /**
     * 设置旋转模式
     * @param enabled 是否启用旋转处理
     */
    void setRotationEnabled(bool enabled);

    /**
     * 获取当前旋转状态
     */
    bool isRotationEnabled();

    /**
     * 记录旋转尝试的统计信息
     */
    void logRotationStats();

    /**
     * 清理缓存和重置状态，用于解决画面切换时不更新的问题
     */
    void clearCache();

} // namespace practical_rotation

#endif // PRACTICAL_ROTATION_H