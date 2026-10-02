#!/usr/bin/env bash
# 用 Android NDK 编译 cznfast（bionic 版 zstd + JNI 壳），产物落到 app/src/main/jniLibs/。
# 用法: ./build_native.sh <ndk路径> [API级别]
# 示例: ./build_native.sh "C:/Android/ndk/27.2.12479018" 24
set -e
NDK="${1:?需要 NDK 路径}"
API="${2:-24}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../app/src/main/jniLibs"

for ABI_CC in "arm64-v8a aarch64-linux-android$API-clang.cmd" "x86_64 x86_64-linux-android$API-clang.cmd"; do
    set -- $ABI_CC
    ABI=$1; CC=$2
    mkdir -p "$OUT/$ABI"
    find "$NDK/toolchains/llvm/prebuilt" -name "$CC" | head -1 | while read -r CC_PATH; do
        (cd "$HERE" && "$CC_PATH" -shared -fPIC -O2 -Izstd-lib \
            cznfast.c zstd-lib/common/*.c zstd-lib/compress/*.c zstd-lib/decompress/*.c \
            -o "$OUT/$ABI/libcznfast.so")
        echo "built $ABI/libcznfast.so"
    done
done
# x86_64 模拟器构建禁用 zstd 汇编（其 asm 仅覆盖部分平台）
if [ -f "$OUT/x86_64/libcznfast.so" ]; then
    find "$NDK/toolchains/llvm/prebuilt" -name "x86_64-linux-android$API-clang.cmd" | head -1 | while read -r CC_PATH; do
        (cd "$HERE" && "$CC_PATH" -shared -fPIC -O2 -DZSTD_DISABLE_ASM -Izstd-lib \
            cznfast.c zstd-lib/common/*.c zstd-lib/compress/*.c zstd-lib/decompress/*.c \
            -o "$OUT/x86_64/libcznfast.so")
        echo "rebuilt x86_64 without asm"
    done
fi
