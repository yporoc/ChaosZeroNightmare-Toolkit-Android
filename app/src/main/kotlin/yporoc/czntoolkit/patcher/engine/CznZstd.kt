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

    /** 单档压缩，返回标准 zstd 帧；选哪一档由 SsraEngine 的余量规则决定。 */
    fun compressAtLevel(data: ByteArray, level: Int): ByteArray =
        checkNotNull(nativeCompress(data, level)) { "zstd 压缩失败（level=$level）" }

    fun decompress(data: ByteArray, maxOut: Int): ByteArray =
        checkNotNull(nativeDecompress(data, maxOut)) { "zstd decompress 失败" }

    private external fun nativeCompress(src: ByteArray, level: Int): ByteArray?

    private external fun nativeDecompress(src: ByteArray, maxOut: Int): ByteArray?
}
