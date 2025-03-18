#!/bin/bash

# 定义原始文件和修改后的文件路径
BACKUP_DIR="android/sunshine/src"
MODIFIED_DIR="/Users/lori/Desktop/nightmare-space/sunshine_android/android/app/src/main/cpp"
PATCH_DIR="patchs"

# 创建存储 patch 文件的目录（如果不存在）
mkdir -p "$PATCH_DIR"

# 遍历所有修改的 C/C++ 文件 和 头文件
for file in $(find $MODIFIED_DIR -name "*.c" -o -name "*.cpp" -o -name "*.h"); do
  # 获取相对路径
  relative_path=${file#$MODIFIED_DIR/}
  
  # 检查原始文件是否存在
  if [ -f "$BACKUP_DIR/$relative_path" ]; then
    # 检查是否有差异
    diff_output=$(diff -u "$BACKUP_DIR/$relative_path" "$file")
    if [ -n "$diff_output" ]; then
      # 生成 patch 文件
      echo "$diff_output" > "$PATCH_DIR/${relative_path}.patch"
    else
      echo "跳过：无差异 -> $relative_path"
    fi
  else
    echo "跳过：原始文件不存在 -> $relative_path"
  fi
done