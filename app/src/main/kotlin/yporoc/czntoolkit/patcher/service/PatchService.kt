package yporoc.czntoolkit.patcher.service

import android.content.Context
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import yporoc.czntoolkit.patcher.engine.CznZstd
import yporoc.czntoolkit.patcher.engine.SsraEngine
import yporoc.czntoolkit.patcher.engine.ZstdCodec
import java.io.File

private const val TAG = "CZN"

/** 构建指纹：日志中出现它即证明服务进程运行的是本次推送的新代码。 */
const val BUILD_ID = "0.0.1beta"

/**
 * Shizuku UserService 实现：以 shell UID 运行，直接读写游戏的 Android/data 目录。
 * 构建结果（manifest/分卷字节）保留在本进程内存，跨进程只传统计与日志。
 */
class PatchService : IPatchService.Stub {

    @Suppress("unused")
    private val context: Context?

    constructor() {
        this.context = null
    }

    /** Shizuku 若支持 Context 构造器会传入应用上下文（当前逻辑不依赖它）。 */
    constructor(context: Context?) {
        this.context = context
    }

    @Volatile
    private var pending: SsraEngine.BuildResult? = null

    override fun detect(gameresPath: String?): Bundle = try {
        Log.i(TAG, "detect start [$BUILD_ID]: $gameresPath")
        val info = SsraEngine.detect(File(checkNotNull(gameresPath)))
        Log.i(
            TAG,
            "detect ok: manifest=${info.manifestExists}/${info.manifestOk} part=${info.partExists} " +
                "etag=${info.etagExists} textdb=${info.textdbFound} etagMatch=${info.etagMatch} " +
                "footerOk=${info.partFooterOk} writeOk=${info.writeOk} backup=${info.backupExists} " +
                "err=${info.manifestError}"
        )
        Bundle().apply {
            putBoolean(DetectKeys.OK, true)
            putString(DetectKeys.PATH, info.gameres.absolutePath)
            putBoolean(DetectKeys.MANIFEST_EXISTS, info.manifestExists)
            putBoolean(DetectKeys.PART_EXISTS, info.partExists)
            putBoolean(DetectKeys.ETAG_EXISTS, info.etagExists)
            putLong(DetectKeys.MANIFEST_SIZE, info.manifestSize)
            putLong(DetectKeys.PART_SIZE, info.partSize)
            putLong(DetectKeys.ETAG_SIZE, info.etagSize)
            putBoolean(DetectKeys.MANIFEST_OK, info.manifestOk)
            putString(DetectKeys.MANIFEST_ERROR, info.manifestError)
            putLong(DetectKeys.BUILD, info.build)
            putInt(DetectKeys.PART_COUNT, info.partCount)
            putInt(DetectKeys.FILE_COUNT, info.fileCount)
            putBoolean(DetectKeys.TEXTDB_FOUND, info.textdbFound)
            putString(DetectKeys.TEXTDB_PART, info.textdbPart)
            putBoolean(DetectKeys.ETAG_MATCH, info.etagMatch)
            putBoolean(DetectKeys.PART_FOOTER_OK, info.partFooterOk)
            putBoolean(DetectKeys.WRITE_OK, info.writeOk)
            putBoolean(DetectKeys.BACKUP_EXISTS, info.backupExists)
        }
    } catch (t: Throwable) {
        errorBundle("detect", t)
    }

    override fun build(gameresPath: String?, onLog: IProgressCallback?): Bundle = try {
        Log.i(TAG, "build start [$BUILD_ID]: $gameresPath")
        CznZstd.load()
        ZstdCodec.compress = { data, maxSize -> CznZstd.compressWithLimit(data, maxSize) }
        ZstdCodec.decompress = { blob, maxOut -> CznZstd.decompress(blob, maxOut) }
        Log.i(TAG, "cznfast (bionic zstd) loaded")
        val res = SsraEngine.build(File(checkNotNull(gameresPath))) { line ->
            Log.i(TAG, line)
            try {
                onLog?.onLog(line)
            } catch (_: RemoteException) {
            }
        }
        Log.i(TAG, "build ok: ${res.stats.summary()}")
        pending = res
        Bundle().apply {
            putBoolean(DetectKeys.OK, true)
            putString(DetectKeys.SUMMARY, res.stats.summary())
            putInt(DetectKeys.CONVERTED, res.stats.converted)
            putStringArray(DetectKeys.LOG, res.logs.toTypedArray())
        }
    } catch (t: Throwable) {
        errorBundle("build", t)
    }

    override fun apply(gameresPath: String?): Bundle = try {
        Log.i(TAG, "apply start: $gameresPath")
        val res = checkNotNull(pending) { "没有待应用的构建结果，请先构建" }
        SsraEngine.apply(File(checkNotNull(gameresPath)), res) { }
        Log.i(TAG, "apply ok")
        Bundle().apply { putBoolean(DetectKeys.OK, true) }
    } catch (t: Throwable) {
        errorBundle("apply", t)
    }

    override fun restore(gameresPath: String?): Bundle = try {
        Log.i(TAG, "restore start: $gameresPath")
        SsraEngine.restore(File(checkNotNull(gameresPath))) { }
        Log.i(TAG, "restore ok")
        Bundle().apply { putBoolean(DetectKeys.OK, true) }
    } catch (t: Throwable) {
        errorBundle("restore", t)
    }

    override fun listDirs(parentPath: String?): Array<String> = try {
        File(checkNotNull(parentPath))
            .listFiles { f -> f.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?.toTypedArray()
            ?: emptyArray()
    } catch (_: Throwable) {
        emptyArray()
    }

    override fun discardPending() {
        pending = null
    }

    override fun exit() {
        pending = null
        System.exit(0)
    }

    private fun errorBundle(op: String, t: Throwable): Bundle {
        Log.e(TAG, "$op FAILED", t)
        var root = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val msg = buildString {
            append(t.javaClass.simpleName)
            if (!t.message.isNullOrBlank()) append(": ").append(t.message)
            if (root !== t) {
                append("｜根因 ").append(root.javaClass.simpleName)
                if (!root.message.isNullOrBlank()) append(": ").append(root.message)
            }
        }
        return Bundle().apply {
            putBoolean(DetectKeys.OK, false)
            putString(DetectKeys.ERROR, msg)
        }
    }
}
