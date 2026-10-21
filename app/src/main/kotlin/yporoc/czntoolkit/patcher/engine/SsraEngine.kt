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
    const val BACKUP_DIR = "czn_backup_original"

    /** 压缩档位表：由引擎持有；选取规则是"首个余量为 0 或 >=8 的档"，注入的 codec 只负责单档压缩。 */
    val COMPRESS_LEVELS = intArrayOf(3, 6, 9, 12, 16, 19)

    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
    private val SSRC_MAGIC = "SSRC".toByteArray()
    private val SKIPPABLE_MAGIC = byteArrayOf(0x50, 0x2A, 0x4D, 0x18)

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
        /** 清单推导出的繁中卷名（不写死）。 */
        val partName: String,
        val etagMatch: Boolean,
        val partFooterOk: Boolean,
        val writeOk: Boolean,
        val backupExists: Boolean,
    )

    // ───────────────────────── detect ─────────────────────────

    fun detect(gameres: File): DetectInfo {
        val manF = File(gameres, "manifest.ssra")
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
        var partName = ""
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
                partName = textdbPart
                textdbFound = textdbPart.isNotEmpty() && File(gameres, "chunks/$textdbPart").isFile
            }
            if (etagF.isFile && manF.isFile) {
                val (lines, _) = parseEtag(etagF.readBytes())
                if (lines.size >= 4) {
                    val manBytes = manF.readBytes()
                    etagMatch = lines[2] == Xxh64.hash(manBytes).toString() &&
                        lines[3] == manBytes.size.toString()
                }
            }
            val pf = if (partName.isNotEmpty()) File(gameres, "chunks/$partName") else null
            if (pf != null && pf.isFile && pf.length() > 16) {
                partFooterOk = verifyPartFooter(pf)
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
            partExists = partName.isNotEmpty() && File(gameres, "chunks/$partName").isFile,
            etagExists = etagF.isFile,
            manifestSize = manF.length(),
            partSize = if (partName.isNotEmpty()) File(gameres, "chunks/$partName").length() else 0L,
            etagSize = etagF.length(),
            manifestOk = manifestOk,
            manifestError = manifestError,
            build = build,
            partCount = partCount,
            fileCount = fileCount,
            textdbFound = textdbFound,
            textdbPart = textdbPart,
            partName = partName,
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
        if (f.meth != 1) {
            error(
                "$TEXT_DB 的压缩方法变成 meth=${f.meth}（不再是 zstd 帧），等长垫帧的打法不适用；" +
                    "要先搞清新的封装方式才能继续",
            )
        }
        val seg = man.locateSegment(f.grp, f.off)
        val partName = man.partNames[seg.k]
        if (partName != TARGET_PART) {
            log("提示: $TEXT_DB 现在在 $partName 卷里（历史卷名 $TARGET_PART），已按清单动态处理")
        }
        if (f.off + f.comp > seg.end) {
            error(
                "$TEXT_DB 跨了分卷边界（结束 ${f.off + f.comp} > 卷内上界 ${seg.end}），等长改写不成立；" +
                    "这一版需要整组重建分卷，本工具未实现该路径",
            )
        }
        val partFile = File(gameres, "chunks/$partName")
        if (!partFile.isFile) {
            error("分卷不在盘上：${partFile.absolutePath}\n请先启动游戏把繁中资源下完，再做繁转简")
        }
        val origPart = partFile.readBytes()
        val local = (f.off - seg.start).toInt()
        if (local + 4 > origPart.size) {
            error(
                "$TEXT_DB 的卷内偏移 $local 加上 4 字节帧头已越过卷尾（卷长 ${origPart.size}），" +
                    "清单与磁盘对不上，请先让游戏更新一轮资源",
            )
        }
        if ((0 until 4).any { origPart[local + it] != ZSTD_MAGIC[it] }) {
            error(
                "$TEXT_DB 在卷内偏移 $local 处不是 zstd 帧头（实得 ${hexOf(origPart, local, 4)}，" +
                    "期望 ${hexOf(ZSTD_MAGIC, 0, 4)}），官方改了条目封装方式，需要重新逆向",
            )
        }
        val gapNonZero = (local + f.comp.toInt() until origPart.size - 16).count { origPart[it] != 0.toByte() }
        if (gapNonZero > 0) {
            error(
                "$TEXT_DB 之后同一卷里还有别的内容（实测 $gapNonZero 个非零字节），改它的长度会挤动后续条目；" +
                    "这一版需要整组重建分卷，本工具未实现该路径",
            )
        }

        val enc = man.extract(f, decrypt = false)
        val phase = InnerXor.findPhase(enc.copyOfRange(0, minOf(64, enc.size)))
            ?: error(
                "在 $TEXT_DB 明文头里找不到内层 XOR 相位（前 16 字节 ${hexOf(enc, 0, 16)}），" +
                    "官方改了 .db 的加密方式，需要重新逆向",
            )
        InnerXor.transformInPlace(enc, phase) // 就地解密，enc 现为明文
        log("text.db 解密: phase=$phase size=${enc.size}")

        val parsed = TextDb.parse(enc)
        // 重建自证：一条都不转也必须逐字节还原官方明文，否则后面所有产出都不可信
        val identity = TextDb.rebuild(
            parsed.header, parsed.hashCount, parsed.buckets, parsed.entries, emptyMap(), parsed.tail,
        )
        if (!identity.contentEquals(enc)) {
            var diff = 0
            var first = -1
            val n = minOf(identity.size, enc.size)
            for (i in 0 until n) {
                if (identity[i] != enc[i]) {
                    if (first < 0) first = i
                    diff++
                }
            }
            error(
                "重建器无法逐字节还原官方 text.db（差异 $diff 字节，首处 @$first，" +
                    "长度 ${identity.size} vs ${enc.size}），结构假设已失效，不能继续构建补丁",
            )
        }
        log("重建自证通过: 尾部原样字节 ${parsed.tail.size}")
        val stats = ConvertStats()
        val newValues = convertValues(parsed.entries, stats)
        log("转换统计: ${stats.summary()}")
        val newdb = TextDb.rebuild(
            parsed.header, parsed.hashCount, parsed.buckets, parsed.entries, newValues, parsed.tail,
        )

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

        if (newdb.size.toLong() != f.dec) {
            val grow = newValues.filterKeys { i -> newValues.getValue(i).size != parsed.entries[i].value.size }
            error(
                "转换后 text.db 尺寸变了 ${newdb.size - f.dec} 字节（${f.dec} -> ${newdb.size}），" +
                    "其中 ${grow.size} 条值的字节长度被转换器改动；等长改写不成立，得先定新的填充策略",
            )
        }

        // 压回存储态（就地加密）
        InnerXor.transformInPlace(newdb, phase)
        // 分卷必须与官方逐字节同尺寸：CDN 上分卷尺寸固定，更新器按清单声明的尺寸发 Range 请求，
        // 一旦超出官方 EOF 就是 HTTP 416 死循环。所以帧压到不超过原 comp，再用 zstd skippable
        // frame 垫满。skippable frame 自己占 8 字节头 ⇒ 余量只能是 0 或 >=8，1~7 要换档而不是硬垫。
        val compressAtLevel = ZstdCodec.requireCompressAtLevel()
        var chosen: ByteArray? = null
        var frameLevel = -1
        var padTotal = 0
        val tried = ArrayList<String>()
        for (lvl in COMPRESS_LEVELS) {
            val cand = compressAtLevel(newdb, lvl)
            val pad = f.comp - cand.size
            tried.add("$lvl:${if (pad >= 0) "+" else ""}$pad")
            if (chosen == null && (pad == 0L || pad >= 8L)) {
                chosen = cand
                frameLevel = lvl
                padTotal = pad.toInt()
            }
        }
        if (chosen == null) {
            error(
                "简体内容压不进官方帧尺寸（各档余量 ${tried.joinToString(" ")}，需要 0 或 >=8 字节），" +
                    "等长改写不成立，得整组重建分卷",
            )
        }
        val cand = chosen
        log("zstd 压缩: 档=$frameLevel ${cand.size}B ≤ ${f.comp}B（余量 $padTotal；各档 ${tried.joinToString(" ")}）")
        check(cand.size.toLong() <= f.comp) { "压缩帧超过原帧尺寸（${cand.size} > ${f.comp}）" }

        // 数据帧回读校验（垫帧前）
        val decompress = ZstdCodec.requireDecompress()
        val back = decompress(cand, newdb.size)
        check(back.size == newdb.size && back.contentEquals(newdb)) { "压缩回读不一致" }

        // zstd skippable frame 垫满到与官方帧完全同尺寸
        if (padTotal in 1..7) {
            error("内部不变式被破坏：余量 $padTotal 落不下 8 字节帧头（档位循环应已排除此情形）")
        }
        var padded = cand
        if (padTotal > 0) {
            val n = padTotal - 8
            padded = cand.copyOf(cand.size + padTotal)
            SKIPPABLE_MAGIC.copyInto(padded, cand.size)
            Le.put32(padded, cand.size + 4, n)
        }
        val frame = padded
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

    /** 取一段字节的十六进制表示，停机消息里要给出实得值。 */
    private fun hexOf(data: ByteArray, off: Int, len: Int): String =
        (off until minOf(off + len, data.size)).joinToString(" ") { "%02x".format(data[it]) }

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
        // 不碰 manifest.ssra.prev：那是更新器自己维护的上一版清单，盖上去会让差分合成拿到假基线
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
    }

    // ───────────────────────── conversion ─────────────────────────

    internal fun convertValues(entries: List<TextDb.Entry>, stats: ConvertStats): Map<Int, ByteArray> {
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
