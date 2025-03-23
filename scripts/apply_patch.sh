#!/bin/bash

# 定义源目录和目标目录
SOURCE_DIR="android/sunshine/src"
TARGET_DIR="android/app/src/main/cpp"
PATCH_DIR="patchs"

# 确保目标目录存在
mkdir -p "$TARGET_DIR"

# 拷贝文件
echo "正在拷贝文件..."
cp -r "$SOURCE_DIR/"* "$TARGET_DIR/"

# 检查拷贝是否成功
if [ $? -ne 0 ]; then
  echo "文件拷贝失败，请检查路径和权限。"
  exit 1
fi
echo "文件拷贝完成。"

# 遍历所有补丁文件并应用
echo "正在应用补丁..."
for patch_file in "$PATCH_DIR"/*.patch; do
  if [ -f "$patch_file" ]; then
    echo "正在检查补丁：$patch_file"
    # 检查补丁是否已经应用
    if patch --dry-run < "$patch_file" > /dev/null 2>&1; then
      echo "正在应用补丁：$patch_file"
      patch < "$patch_file"
    else
      echo "跳过：补丁已应用或冲突 -> $patch_file"
    fi else echo "没有找到补丁文件，跳过。"
  fi
done

echo "所有补丁处理完成。"