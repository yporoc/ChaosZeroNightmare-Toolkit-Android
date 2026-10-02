package yporoc.czntoolkit.patcher.engine

/**
 * cznfast（NDK 编译的 bionic 版 zstd）JNI 绑定。
 * 只在 Android 侧由 PatchService 加载后注入 SsraEngine；PC 单测改用 zstd-jni。
 */
object CznZstd {
    @Volatile
    var loaded = false
        private set

    fun load() {
        if (loaded) return
        System.loadLibrary("cznfast")
        loaded = true
    }

    /** 依次尝试压缩级别，返回首个 ≤ maxSize 的标准 zstd 帧；全部超限则抛异常。 */
    fun compressWithLimit(data: ByteArray, maxSize: Int): ByteArray {
        for (lvl in intArrayOf(3, 6, 9, 12, 16, 19)) {
            nativeCompress(data, lvl)?.let { if (it.size <= maxSize) return it }
        }
        throw IllegalStateException("所有压缩级别均超过原帧尺寸（maxSize=$maxSize）")
    }

    fun decompress(data: ByteArray, maxOut: Int): ByteArray =
        checkNotNull(nativeDecompress(data, maxOut)) { "zstd decompress 失败" }

    private external fun nativeCompress(src: ByteArray, level: Int): ByteArray?

    private external fun nativeDecompress(src: ByteArray, maxOut: Int): ByteArray?
}
