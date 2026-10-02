package yporoc.czntoolkit.patcher

import android.content.Context
import android.content.ServiceConnection
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import yporoc.czntoolkit.patcher.engine.SsraEngine
import yporoc.czntoolkit.patcher.service.DetectKeys
import yporoc.czntoolkit.patcher.service.IProgressCallback
import yporoc.czntoolkit.patcher.service.IPatchService
import yporoc.czntoolkit.patcher.shizuku.ShizukuAvail
import yporoc.czntoolkit.patcher.shizuku.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileCardView(
    val label: String,
    val rel: String,
    val exists: Boolean,
    val size: Long,
    val note: String,
    val good: Boolean,
)

data class DetectView(
    val path: String,
    val cards: List<FileCardView>,
    val manifestOk: Boolean,
    val manifestError: String?,
    val build: Long,
    val partCount: Int,
    val fileCount: Int,
    val textdbFound: Boolean,
    val etagMatch: Boolean,
    val partFooterOk: Boolean,
    val writeOk: Boolean,
    val backupExists: Boolean,
    val ready: Boolean,
)

sealed interface UiState {
    data object Idle : UiState
    data class Detecting(val path: String) : UiState
    data class Detected(val view: DetectView, val path: String) : UiState
    data class Building(val logs: List<String>) : UiState
    data class Built(val summary: String, val converted: Int, val logs: List<String>) : UiState
    data class Applying(val logs: List<String>) : UiState
    data class Applied(val logs: List<String>) : UiState
    data class Restoring(val logs: List<String>) : UiState
    data class Restored(val logs: List<String>) : UiState
    data class Failed(val message: String, val logs: List<String>) : UiState
}

class PatcherViewModel : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _shizuku = MutableStateFlow<ShizukuAvail>(ShizukuAvail.NotRunning)
    val shizuku: StateFlow<ShizukuAvail> = _shizuku.asStateFlow()

    val logs = MutableStateFlow<List<String>>(emptyList())
    val path = MutableStateFlow(DEFAULT_PATH)

    private var service: IPatchService? = null
    private var conn: ServiceConnection? = null
    private var bindContext: Context? = null
    private var appContext: Context? = null

    /** 在组合里先注入应用上下文，供无参回调使用。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun onBinderAlive() = refreshShizuku()

    fun onBinderDead() {
        service = null
        _shizuku.value = ShizukuAvail.NotRunning
    }

    fun refreshShizuku() {
        val ctx = appContext ?: return
        _shizuku.value = ShizukuHelper.avail(ctx)
    }

    fun requestPermission() {
        runCatching { ShizukuHelper.requestPermission(PERM_REQUEST_CODE) }
    }

    fun setPath(p: String) {
        path.value = p.trim().trimEnd('/')
    }

    fun reset() {
        runCatching { service?.discardPending() }
        _state.value = UiState.Idle
    }

    private suspend fun <T> withService(appContext: Context, block: (IPatchService) -> T): T =
        withContext(Dispatchers.IO) {
            var s = service
            if (s == null) {
                conn?.let { c -> bindContext?.let { ctx -> ShizukuHelper.unbind(ctx, c) } }
                val ctx = appContext.applicationContext
                bindContext = ctx
                conn = ShizukuHelper.bind(
                    ctx,
                    { svc -> service = svc },
                    { service = null },
                )
                val deadline = System.currentTimeMillis() + 15_000
                while (service == null && System.currentTimeMillis() < deadline) delay(50)
                s = service ?: error("Shizuku 服务连接超时：请先在 Shizuku 应用中启动服务（无线调试），再回到本应用重试")
            }
            block(s)
        }

    fun detect(context: Context, target: String = path.value) = viewModelScope.launch {
        _state.value = UiState.Detecting(target)
        try {
            val b = withService(context) { it.detect(target) }
            check(b.getBoolean(DetectKeys.OK)) { b.getString(DetectKeys.ERROR) ?: "检测失败" }
            _state.value = UiState.Detected(toView(b), target)
        } catch (t: Throwable) {
            android.util.Log.e("CZN", "ui detect failed", t)
            _state.value = UiState.Failed(t.message ?: t.toString(), logs.value)
        }
    }

    fun build(context: Context) = viewModelScope.launch {
        logs.value = emptyList()
        _state.value = UiState.Building(emptyList())
        try {
            val b = withService(context) { svc ->
                svc.build(path.value, object : IProgressCallback.Stub() {
                    override fun onLog(line: String?) {
                        if (line != null) appendLog(line)
                    }
                })
            }
            check(b.getBoolean(DetectKeys.OK)) { b.getString(DetectKeys.ERROR) ?: "构建失败" }
            _state.value = UiState.Built(
                summary = b.getString(DetectKeys.SUMMARY) ?: "",
                converted = b.getInt(DetectKeys.CONVERTED),
                logs = b.getStringArray(DetectKeys.LOG)?.toList() ?: logs.value,
            )
        } catch (t: Throwable) {
            android.util.Log.e("CZN", "ui build failed", t)
            _state.value = UiState.Failed(t.message ?: t.toString(), logs.value)
        }
    }

    fun apply(context: Context) = viewModelScope.launch {
        _state.value = UiState.Applying(logs.value)
        try {
            val b = withService(context) { it.apply(path.value) }
            check(b.getBoolean(DetectKeys.OK)) { b.getString(DetectKeys.ERROR) ?: "应用失败" }
            _state.value = UiState.Applied(
                b.getStringArray(DetectKeys.LOG)?.toList() ?: emptyList(),
            )
        } catch (t: Throwable) {
            android.util.Log.e("CZN", "ui apply failed", t)
            _state.value = UiState.Failed(t.message ?: t.toString(), logs.value)
        }
    }

    fun restore(context: Context) = viewModelScope.launch {
        _state.value = UiState.Restoring(logs.value)
        try {
            val b = withService(context) { it.restore(path.value) }
            check(b.getBoolean(DetectKeys.OK)) { b.getString(DetectKeys.ERROR) ?: "还原失败" }
            _state.value = UiState.Restored(emptyList())
        } catch (t: Throwable) {
            android.util.Log.e("CZN", "ui restore failed", t)
            _state.value = UiState.Failed(t.message ?: t.toString(), logs.value)
        }
    }

    suspend fun browse(context: Context, parent: String): List<String> = try {
        withService(context) { it.listDirs(parent).toList() }
    } catch (t: Throwable) {
        android.util.Log.e("CZN", "ui browse failed", t)
        emptyList()
    }

    private fun appendLog(line: String) {
        logs.value = logs.value + line
        val cur = _state.value
        if (cur is UiState.Building) _state.value = cur.copy(logs = logs.value)
    }

    private fun toView(b: Bundle): DetectView {
        fun bl(k: String) = b.getBoolean(k)
        fun sg(k: String) = b.getString(k)
        val ready = bl(DetectKeys.MANIFEST_OK) && bl(DetectKeys.TEXTDB_FOUND) &&
            bl(DetectKeys.WRITE_OK) && bl(DetectKeys.ETAG_EXISTS)
        val cards = listOf(
            FileCardView(
                label = "资源清单", rel = "manifest.ssra",
                exists = bl(DetectKeys.MANIFEST_EXISTS), size = b.getLong(DetectKeys.MANIFEST_SIZE),
                note = if (bl(DetectKeys.MANIFEST_OK)) {
                    "解析正常 · build=${b.getLong(DetectKeys.BUILD)} · 卷${b.getInt(DetectKeys.PART_COUNT)}/文件${b.getInt(DetectKeys.FILE_COUNT)}"
                } else {
                    sg(DetectKeys.MANIFEST_ERROR) ?: "解析失败"
                },
                good = bl(DetectKeys.MANIFEST_OK),
            ),
            FileCardView(
                label = "语言分卷", rel = "chunks/${SsraEngine.TARGET_PART}",
                exists = bl(DetectKeys.PART_EXISTS), size = b.getLong(DetectKeys.PART_SIZE),
                note = when {
                    !bl(DetectKeys.TEXTDB_FOUND) -> sg(DetectKeys.TEXTDB_PART)
                        ?.let { "text.db 在 $it，非目标分卷" } ?: "未定位到 text.db"
                    else -> "text.db 定位成功 · footer " + if (bl(DetectKeys.PART_FOOTER_OK)) "校验通过" else "校验失败"
                },
                good = bl(DetectKeys.TEXTDB_FOUND) && bl(DetectKeys.PART_FOOTER_OK),
            ),
            FileCardView(
                label = "身份记录", rel = SsraEngine.ETAG_REL,
                exists = bl(DetectKeys.ETAG_EXISTS), size = b.getLong(DetectKeys.ETAG_SIZE),
                note = if (bl(DetectKeys.ETAG_MATCH)) "与当前 manifest 匹配（补丁已应用或官方一致）" else "与 manifest 不匹配",
                good = bl(DetectKeys.ETAG_MATCH),
            ),
        )
        return DetectView(
            path = sg(DetectKeys.PATH) ?: "",
            cards = cards,
            manifestOk = bl(DetectKeys.MANIFEST_OK),
            manifestError = sg(DetectKeys.MANIFEST_ERROR),
            build = b.getLong(DetectKeys.BUILD),
            partCount = b.getInt(DetectKeys.PART_COUNT),
            fileCount = b.getInt(DetectKeys.FILE_COUNT),
            textdbFound = bl(DetectKeys.TEXTDB_FOUND),
            etagMatch = bl(DetectKeys.ETAG_MATCH),
            partFooterOk = bl(DetectKeys.PART_FOOTER_OK),
            writeOk = bl(DetectKeys.WRITE_OK),
            backupExists = bl(DetectKeys.BACKUP_EXISTS),
            ready = ready,
        )
    }

    companion object {
        const val DEFAULT_PATH =
            "/storage/emulated/0/Android/data/com.smilegate.chaoszero.stove.google/files/gameres"
        const val PERM_REQUEST_CODE = 100
    }
}
