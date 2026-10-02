package yporoc.czntoolkit.patcher.engine

import com.github.houbb.opencc4j.util.ZhConverterUtil
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * ssra 繁转简引擎（纯 Kotlin，无 Android 依赖，JVM 可单测）。
 * 移植自 PC 端 GPL-3.0 项目 ChaosZero-Toolkit-Extra 的 ssra_zhcn.py，本应用同以 GPL-3.0 发布。
 */
object SsraEngine {
    const val TEXT_DB = "text/zht/text.db"
    const val TARGET_PART = "lang_zht_b03_0.ssrc"
    const val ETAG_REL = "manifest.ssra.etag"
    const val PREV_REL = "manifest.ssra.prev"
    const val BACKUP_DIR = "czn_backup_original"
    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
    private val SSRC_MAGIC = "SSRC".toByteArray()

    class ConvertStats {
        var converted = 0
        var alreadySimplified = 0
        var refsKept = 0
        var skippedKept = 0
        var nonTextKept = 0
        var doubleConverted = 0

        fun summary(): String =
            "已转换=$converted 已是简体=$alreadySimplified 二次转换=$doubleConverted " +
                "引用保持=$refsKept 跳过保持=$skippedKept 非文本保持=$nonTextKept"
    }

    class BuildResult(
        val manifest: ByteArray,
        val part: ByteArray,
        val etag: ByteArray,
        val stats: ConvertStats,
        val partName: String,
        val logs: List<String>,
    )

    data class DetectInfo(
        val gameres: File,
        val manifestExists: Boolean,
        val partExists: Boolean,
        val etagExists: Boolean,
        val manifestSize: Long,
        val partSize: Long,
        val etagSize: Long,
        val manifestOk: Boolean,
        val manifestError: String?,
        val build: Long,
        val partCount: Int,
        val fileCount: Int,
        val textdbFound: Boolean,
        val textdbPart: String,
        val etagMatch: Boolean,
        val partFooterOk: Boolean,
        val writeOk: Boolean,
        val backupExists: Boolean,
    )

    // ───────────────────────── detect ─────────────────────────

    fun detect(gameres: File): DetectInfo {
        val manF = File(gameres, "manifest.ssra")
        val partF = File(gameres, "chunks/$TARGET_PART")
        val etagF = File(gameres, ETAG_REL)
        var manifestOk = false
        var manifestError: String? = null
        var build = 0L
        var partCount = 0
        var fileCount = 0
        var textdbFound = false
        var textdbPart = ""
        var etagMatch = false
        var partFooterOk = false
        try {
            val man = SsraManifest(gameres)
            manifestOk = true
            build = man.build
            partCount = man.partCount
            fileCount = man.fileCount
            val rec = man.files.firstOrNull { it.name == TEXT_DB }
            if (rec != null) {
                val seg = man.groupSegs.getValue(rec.grp).firstOrNull { rec.off >= it.start && rec.off < it.end }
                textdbPart = seg?.let { man.partNames[it.k] } ?: ""
                textdbFound = textdbPart == TARGET_PART
            }
            if (etagF.isFile && manF.isFile) {
                val (lines, _) = parseEtag(etagF.readBytes())
                if (lines.size >= 4) {
                    val manBytes = manF.readBytes()
                    etagMatch = lines[2] == Xxh64.hash(manBytes).toString() &&
                        lines[3] == manBytes.size.toString()
                }
            }
            if (partF.isFile && partF.length() > 16) {
                partFooterOk = verifyPartFooter(partF)
            }
        } catch (t: Throwable) {
            manifestError = t.message ?: t.toString()
        }
        val writeOk = try {
            val probe = File(gameres, ".czn_write_probe")
            probe.writeText("1")
            probe.delete()
            true
        } catch (_: Throwable) {
            false
        }
        return DetectInfo(
            gameres = gameres,
            manifestExists = manF.isFile,
            partExists = partF.isFile,
            etagExists = etagF.isFile,
            manifestSize = manF.length(),
            partSize = partF.length(),
            etagSize = etagF.length(),
            manifestOk = manifestOk,
            manifestError = manifestError,
            build = build,
            partCount = partCount,
            fileCount = fileCount,
            textdbFound = textdbFound,
            textdbPart = textdbPart,
            etagMatch = etagMatch,
            partFooterOk = partFooterOk,
            writeOk = writeOk,
            backupExists = File(File(gameres.parentFile, BACKUP_DIR), "manifest.ssra.bak").isFile,
        )
    }

    private fun verifyPartFooter(partF: File): Boolean {
        RandomAccessFile(partF, "r").use { raf ->
            val size = raf.length()
            raf.seek(size - 16)
            val footer = ByteArray(16).also { raf.readFully(it) }
            for (i in SSRC_MAGIC.indices) if (footer[i] != SSRC_MAGIC[i]) return false
            val payloadHash = Le.u64(footer, 8).toULong()
            raf.seek(0)
            val payload = ByteArray((size - 16).toInt())
            raf.readFully(payload)
            return Xxh64.hash(payload) == payloadHash
        }
    }

    // ───────────────────────── build ─────────────────────────

    fun build(gameres: File, onLog: (String) -> Unit = {}): BuildResult {
        val logs = ArrayList<String>()
        fun log(s: String) {
            logs.add(s)
            onLog(s)
        }

        val man = SsraManifest(gameres)
        log("manifest build=${man.build} parts=${man.partCount} files=${man.fileCount}")
        val f = man.record(TEXT_DB)
        val seg = man.segmentContaining(f.grp, f.off)
        val partName = man.partNames[seg.k]
        check(partName == TARGET_PART) { "text.db 所在分卷为 $partName，非 $TARGET_PART" }
        val partFile = File(gameres, "chunks/$partName")
        val origPart = partFile.readBytes()
        val local = (f.off - seg.start).toInt()
        check(local + 4 <= origPart.size) { "text.db 帧入口越界" }
        check((0 until 4).all { origPart[local + it] == ZSTD_MAGIC[it] }) { "text.db 入口不是 zstd 帧" }
        check(
            (local + f.comp.toInt() until origPart.size - 16).all { it >= origPart.size || origPart[it] == 0.toByte() }
        ) { "text.db 不是该卷最后条目" }

        val enc = man.extract(f, decrypt = false)
        val phase = InnerXor.findPhase(enc.copyOfRange(0, minOf(64, enc.size)))
            ?: error("未找到内层 XOR 相位")
        InnerXor.transformInPlace(enc, phase) // 就地解密，enc 现为明文
        log("text.db 解密: phase=$phase size=${enc.size}")

        val parsed = TextDb.parse(enc)
        val stats = ConvertStats()
        val newValues = convertValues(parsed.entries, stats)
        log("转换统计: ${stats.summary()}")
        val newdb = TextDb.rebuild(parsed.header, parsed.hashCount, parsed.buckets, parsed.entries, newValues)

        // 自检：键集 / 桶分配 / 值逐条一致
        val re = TextDb.parse(newdb)
        check(re.entries.size == parsed.entries.size && re.hashCount == parsed.hashCount) { "重建后条目数不一致" }
        val kb1 = HashMap<String, Int>()
        parsed.buckets.forEachIndexed { b, lst -> lst.forEach { kb1[TextDb.keyHex(parsed.entries[it].key)] = b } }
        val kb2 = HashMap<String, Int>()
        re.buckets.forEachIndexed { b, lst -> lst.forEach { kb2[TextDb.keyHex(re.entries[it].key)] = b } }
        check(kb1 == kb2) { "桶分配漂移" }
        val expect = HashMap<String, ByteArray>()
        newValues.forEach { (i, v) -> expect[TextDb.keyHex(parsed.entries[i].key)] = v }
        val origByKey = HashMap<String, ByteArray>()
        parsed.entries.forEach { origByKey[TextDb.keyHex(it.key)] = it.value }
        for (e in re.entries) {
            val k = TextDb.keyHex(e.key)
            val want = expect[k] ?: origByKey[k] ?: error("自检键缺失")
            check(e.value.contentEquals(want)) { "值不一致: ${String(e.key, Charsets.UTF_8).take(40)}" }
        }
        log("自检通过: ${re.entries.size} 条，键集/桶分配/值全部一致")
        if (newValues.isEmpty()) log("官方 text.db 已全部为简体，无需转换（生成的补丁与官方内容一致）")

        check(newdb.size.toLong() == f.dec) {
            "转换改变了 text.db 尺寸（${newdb.size} != ${f.dec}），会破坏分卷尺寸一致性"
        }

        // 压回存储态（就地加密），用注入的真实 zstd 压缩器（输出为标准 zstd 帧）
        InnerXor.transformInPlace(newdb, phase)
        val compress = ZstdCodec.requireCompress()
        val cand = compress(newdb, f.comp.toInt())
        log("zstd 压缩: ${cand.size}B ≤ ${f.comp}B")
        check(cand.size.toLong() <= f.comp) { "压缩帧超过原帧尺寸（${cand.size} > ${f.comp}）" }

        // 数据帧回读校验（垫帧前）
        val decompress = ZstdCodec.requireDecompress()
        val back = decompress(cand, newdb.size)
        check(back.size == newdb.size && back.contentEquals(newdb)) { "压缩回读不一致" }

        // zstd skippable frame 垫满到与官方帧完全同尺寸
        val padTotal = (f.comp - cand.size).toInt()
        var frame = cand
        if (padTotal > 0) {
            val n = padTotal - 8
            frame = cand.copyOf(cand.size + padTotal)
            byteArrayOf(0x50, 0x2A, 0x4D, 0x18).copyInto(frame, cand.size)
            Le.put32(frame, cand.size + 4, n)
        }
        check(frame.size.toLong() == f.comp) { "帧尺寸不等于原压缩尺寸" }

        // 组装新分卷：原载荷替换帧段，重算 SSRC footer
        val payload = ByteArray(origPart.size - 16)
        origPart.copyInto(payload, 0, 0, local)
        frame.copyInto(payload, local)
        origPart.copyInto(payload, local + frame.size, local + frame.size, origPart.size - 16)
        check(payload.size == origPart.size - 16)
        val newPart = ByteArray(payload.size + 16)
        payload.copyInto(newPart, 0)
        SSRC_MAGIC.copyInto(newPart, payload.size)
        Le.put32(newPart, payload.size + 4, seg.idx)
        val payloadHash = Xxh64.hash(payload)
        Le.put64(newPart, payload.size + 8, payloadHash)
        check(newPart.size == origPart.size) { "分卷尺寸与官方不一致" }

        // manifest 只更新该卷 XXH64 字段
        val manBytes = File(gameres, "manifest.ssra").readBytes()
        val o = 0x40 + seg.k * 32
        val oldA = Le.u64(manBytes, o + 8)
        val oldB = Le.u64(manBytes, o + 16)
        check(newPart.size - 16L == oldA && newPart.size.toLong() == oldB) { "分卷尺寸字段意外变化" }
        Le.put64(manBytes, o + 24, payloadHash)

        val etagF = File(gameres, ETAG_REL)
        check(etagF.isFile) { "缺少 ${ETAG_REL}，无法同步身份记录" }
        val etag = syncEtagBytes(manBytes, etagF)

        log("补丁已生成: manifest.ssra(${manBytes.size}B) $partName(${newPart.size}B)")
        return BuildResult(manBytes, newPart, etag, stats, partName, logs)
    }

    /**
     * etag 行 2 = XXH64(manifest) 十进制，行 3 = manifest 尺寸；其余行保持不变。
     * 分隔符自适应：PC 版为 CRLF，手机版为 LF——保留原文件自身的风格。
     */
    private fun syncEtagBytes(manifest: ByteArray, etagFile: File): ByteArray {
        val (lines, sep) = parseEtag(etagFile.readBytes())
        check(lines.size >= 4) { "etag 行数异常: ${lines.size}" }
        val out = lines.toMutableList()
        out[2] = Xxh64.hash(manifest).toString()
        out[3] = manifest.size.toString()
        return out.joinToString(sep).toByteArray(Charsets.UTF_8)
    }

    /** etag 解析：检测 CRLF 或 LF，返回行列表与所用分隔符。 */
    fun parseEtag(data: ByteArray): Pair<List<String>, String> {
        val text = String(data, Charsets.UTF_8)
        val sep = if (text.contains("\r\n")) "\r\n" else "\n"
        return text.split(sep) to sep
    }

    // ───────────────────────── apply / restore ─────────────────────────

    fun apply(gameres: File, res: BuildResult, onLog: (String) -> Unit = {}) {
        val logs = ArrayList<String>()
        fun log(s: String) {
            logs.add(s)
            onLog(s)
        }
        val backupDir = File(gameres.parentFile, BACKUP_DIR)
        backupDir.mkdirs()
        val targets = listOf(
            File(gameres, "manifest.ssra") to "manifest.ssra.bak",
            File(gameres, "chunks/${res.partName}") to "$TARGET_PART.bak",
            File(gameres, ETAG_REL) to "manifest.ssra.etag.bak",
        )
        for ((src, name) in targets) {
            if (src.isFile) {
                val dst = File(backupDir, name)
                if (!dst.exists()) {
                    src.copyTo(dst, overwrite = false)
                    log("备份 $name")
                }
            }
        }
        File(gameres, "manifest.ssra").writeBytes(res.manifest)
        log("应用 manifest.ssra")
        File(gameres, "chunks/${res.partName}").writeBytes(res.part)
        log("应用 chunks/${res.partName}")
        File(gameres, ETAG_REL).writeBytes(res.etag)
        log("同步 ${ETAG_REL}")
        File(gameres, PREV_REL).writeBytes(res.manifest)
        log("写入 $PREV_REL")
    }

    fun restore(gameres: File, onLog: (String) -> Unit = {}) {
        val backupDir = File(gameres.parentFile, BACKUP_DIR)
        val pairs = listOf(
            "manifest.ssra.bak" to File(gameres, "manifest.ssra"),
            "$TARGET_PART.bak" to File(gameres, "chunks/$TARGET_PART"),
            "manifest.ssra.etag.bak" to File(gameres, ETAG_REL),
        )
        for ((bak, dst) in pairs) {
            val src = File(backupDir, bak)
            check(src.isFile) { "缺少备份: ${src.absolutePath}" }
            src.copyTo(dst, overwrite = true)
            onLog("还原 $bak")
        }
        val prev = File(gameres, PREV_REL)
        if (prev.isFile) {
            prev.delete()
            onLog("移除 $PREV_REL")
        }
    }

    // ───────────────────────── conversion ─────────────────────────

    private fun convertValues(entries: List<TextDb.Entry>, stats: ConvertStats): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        for ((i, e) in entries.withIndex()) {
            val v = e.value
            val j = v.indexOf(0)
            if (j < 0) {
                stats.refsKept++
                continue
            }
            val tailStart = j + 1
            val hasTerm = v.isNotEmpty() && v[v.size - 1] == 0.toByte()
            val textEnd = if (hasTerm) v.size - 1 else v.size
            val textLen = textEnd - tailStart
            if (textLen <= 0) {
                stats.skippedKept++
                continue
            }
            var hasInnerZero = false
            for (t in tailStart until textEnd) {
                if (v[t] == 0.toByte()) {
                    hasInnerZero = true
                    break
                }
            }
            if (hasInnerZero) {
                stats.skippedKept++
                continue
            }
            val s = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(v, tailStart, textLen))
                    .toString()
            } catch (_: Exception) {
                stats.nonTextKept++
                continue
            }
            var conv = ZhConverterUtil.toSimple(s)
            val conv2 = ZhConverterUtil.toSimple(conv)
            if (conv2 != conv) {
                stats.doubleConverted++
                conv = conv2
            }
            if (conv == s) {
                stats.alreadySimplified++
                continue
            }
            val convBytes = conv.toByteArray(Charsets.UTF_8)
            val nb = ByteArray(tailStart + convBytes.size + if (hasTerm) 1 else 0)
            v.copyInto(nb, 0, 0, tailStart)
            convBytes.copyInto(nb, tailStart)
            if (hasTerm) nb[nb.size - 1] = 0
            out[i] = nb
            stats.converted++
        }
        return out
    }

    // ───────────────────────── utils ─────────────────────────

    fun splitCrlf(data: ByteArray): List<ByteArray> {
        val lines = ArrayList<ByteArray>()
        var start = 0
        var i = 0
        while (i + 1 < data.size) {
            if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte()) {
                lines.add(data.copyOfRange(start, i))
                i += 2
                start = i
            } else {
                i++
            }
        }
        if (start < data.size) lines.add(data.copyOfRange(start, data.size))
        else if (start == data.size && data.isNotEmpty()) lines.add(ByteArray(0))
        return lines
    }
}

private fun ByteArray.indexOf(byte: Byte): Int {
    for (i in indices) if (this[i] == byte) return i
    return -1
}
