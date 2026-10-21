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
    const val IDENTITY_FILE = "identity.txt"

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
        /** 这份补丁"是从哪一份官方现场长出来的"，apply 前必须与现场对上。 */
        val identity: PatchIdentity,
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
            backupExists = hasUsableBackup(gameres),
        )
    }

    /** 有没有可用备份：新布局看带身份的 build 子目录，旧布局只认它的存在（还原时会被拒绝并说明原因）。 */
    private fun hasUsableBackup(gameres: File): Boolean {
        val root = File(gameres.parentFile, BACKUP_DIR)
        if (!root.isDirectory) return false
        if (File(root, "manifest.ssra.bak").isFile) return true
        return root.listFiles { f -> f.isDirectory }?.any { File(it, IDENTITY_FILE).isFile } == true
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
        val rebuiltPlain = TextDb.rebuild(
            parsed.header, parsed.hashCount, parsed.buckets, parsed.entries, emptyMap(), parsed.tail,
        )
        if (!rebuiltPlain.contentEquals(enc)) {
            var diff = 0
            var first = -1
            val n = minOf(rebuiltPlain.size, enc.size)
            for (i in 0 until n) {
                if (rebuiltPlain[i] != enc[i]) {
                    if (first < 0) first = i
                    diff++
                }
            }
            error(
                "重建器无法逐字节还原官方 text.db（差异 $diff 字节，首处 @$first，" +
                    "长度 ${rebuiltPlain.size} vs ${enc.size}），结构假设已失效，不能继续构建补丁",
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
        if (newValues.isEmpty()) {
            error(
                "现场 $TEXT_DB 已无繁体可转（已转换=0），多半这里已经是汉化态。" +
                    "请先「还原官方繁中」或让游戏更新一轮资源再构建——" +
                    "否则会把我们自己的补丁当成官方原件备份下来",
            )
        }

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
        val officialManHash = Xxh64.hash(manBytes)
        val officialManSize = manBytes.size.toLong()
        val o = 0x40 + seg.k * 32
        val oldA = Le.u64(manBytes, o + 8)
        val oldB = Le.u64(manBytes, o + 16)
        check(newPart.size - 16L == oldA && newPart.size.toLong() == oldB) { "分卷尺寸字段意外变化" }
        Le.put64(manBytes, o + 24, payloadHash)

        val etagF = File(gameres, ETAG_REL)
        check(etagF.isFile) { "缺少 ${ETAG_REL}，无法同步身份记录" }
        val etag = syncEtagBytes(manBytes, etagF)

        val officialPartHash = Xxh64.hash(origPart, 0, origPart.size - 16)
        val identity = PatchIdentity(
            build = man.build,
            context = TreeIdentity.context(gameres),
            part = partName,
            official = PatchIdentity.Side(officialManHash, officialManSize, officialPartHash, origPart.size.toLong()),
            patched = PatchIdentity.Side(Xxh64.hash(manBytes), manBytes.size.toLong(), payloadHash, newPart.size.toLong()),
            textDb = PatchIdentity.TextDbStats(
                comp = f.comp, dec = f.dec, entries = parsed.entries.size,
                converted = newValues.size, tail = parsed.tail.size, phase = phase,
            ),
        )
        log("补丁已生成: manifest.ssra(${manBytes.size}B) $partName(${newPart.size}B) 身份 build=${man.build} 卷 $officialPartHash -> $payloadHash")
        return BuildResult(manBytes, newPart, etag, stats, partName, logs, identity)
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
        val ident = res.identity
        val part = ident.part
        val live = TreeIdentity.manifest(gameres)
            ?: error("现场清单读不到，无法核对身份：${File(gameres, "manifest.ssra").absolutePath}")
        val liveBuild = TreeIdentity.build(gameres)
        if (liveBuild != ident.build) {
            error("现场是 build $liveBuild，这份补丁是给 build ${ident.build} 做的\n请重新构建补丁")
        }
        val off = ident.official
        val pat = ident.patched
        val livePart = TreeIdentity.part(gameres, part)
            ?: error("现场分卷读不到：${File(gameres, "chunks/$part").absolutePath}")
        if (live == (pat.manifestHash to pat.manifestSize) && livePart == (pat.partHash to pat.partSize)) {
            log("现场已经是这份补丁的结果（build ${ident.build}），无需重复应用")
            return
        }
        if (live != (off.manifestHash to off.manifestSize)) {
            error(
                "现场清单与补丁记录的官方清单不符：现场 ${live.first}/${live.second}，" +
                    "记录 ${off.manifestHash}/${off.manifestSize}\n" +
                    "多半是游戏又更新了，请重新构建补丁再应用",
            )
        }
        if (livePart != (off.partHash to off.partSize)) {
            error(
                "现场分卷 $part 不是补丁记录的那个官方件：现场 ${livePart.first}/${livePart.second}，" +
                    "记录 ${off.partHash}/${off.partSize}\n" +
                    "拒绝备份，否则会把非官方卷当成\"原件\"存下来",
            )
        }

        // 备份：每次都从刚核实过的官方现场重取，并按 build+context 分目录（旧逻辑"已存在就不覆盖"
        // 正是跨版本把补丁件当原件的根因）
        val backupDir = File(File(gameres.parentFile, BACKUP_DIR), ident.tag())
        backupDir.mkdirs()
        for (rel in listOf("manifest.ssra", "chunks/$part", ETAG_REL)) {
            val src = File(gameres, rel)
            if (!src.isFile) error("要备份的文件不存在：${src.absolutePath}")
            val dst = File(backupDir, bakName(rel))
            src.copyTo(dst, overwrite = true)
            log("备份 $rel -> ${dst.name}")
        }
        File(backupDir, IDENTITY_FILE).writeText(ident.toText(), Charsets.UTF_8)
        log("身份记录已写入 ${File(backupDir, IDENTITY_FILE).path}")

        File(gameres, "manifest.ssra").writeBytes(res.manifest)
        log("应用 manifest.ssra")
        File(gameres, "chunks/$part").writeBytes(res.part)
        log("应用 chunks/$part")

        // 反向核对：写完必须读回来证明自己写对了，"没抛异常"不算成功
        val after = TreeIdentity.manifest(gameres) ?: error("应用后读不回清单")
        val afterPart = TreeIdentity.part(gameres, part) ?: error("应用后读不回分卷")
        if (after != (pat.manifestHash to pat.manifestSize) || afterPart != (pat.partHash to pat.partSize)) {
            error(
                "应用后回读身份与补丁记录不符，现场可能没写成功：清单 ${after.first}/${after.second}，" +
                    "记录 ${pat.manifestHash}/${pat.manifestSize}",
            )
        }
        log("应用后身份核对通过: build=${ident.build} 卷=$part")

        val etagF = File(gameres, ETAG_REL)
        if (!etagF.isFile) error("缺少 ${ETAG_REL}，无法同步身份记录")
        etagF.writeBytes(res.etag)
        log("同步 ${ETAG_REL}")
        // 不碰 manifest.ssra.prev：那是更新器自己维护的上一版清单，盖上去会让差分合成拿到假基线
    }

    fun restore(gameres: File, onLog: (String) -> Unit = {}) {
        val broot = File(gameres.parentFile, BACKUP_DIR)
        if (!broot.isDirectory) error("没有备份目录 ${broot.absolutePath}，无法还原")
        val idents = LinkedHashMap<String, PatchIdentity>()
        val badTags = ArrayList<String>()
        broot.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val f = File(d, IDENTITY_FILE)
            if (!f.isFile) return@forEach
            val parsed = runCatching { PatchIdentity.load(f) }
            if (parsed.isFailure) {
                badTags.add("${d.name}(${parsed.exceptionOrNull()?.message})")
            } else {
                idents[d.name] = parsed.getOrThrow()
            }
        }
        if (idents.isEmpty()) {
            val legacy = File(broot, "manifest.ssra.bak")
            error(
                if (legacy.isFile) {
                    "备份目录里有旧版（0.0.1beta）不带版本身份的备份，无法核对它是哪个 build 的原件，" +
                        "不敢拿来还原。要回官方繁中，最稳的是让游戏自己更新一轮资源"
                } else {
                    "备份目录 ${broot.absolutePath} 里没有带版本身份的备份，无法还原" +
                        (if (badTags.isEmpty()) "" else "；损坏的身份记录: $badTags")
                },
            )
        }
        val live = TreeIdentity.manifest(gameres) ?: error("现场清单读不到：${gameres.absolutePath}")
        val liveBuild = TreeIdentity.build(gameres)
        var chosen: Pair<String, PatchIdentity>? = null
        for ((tag, ident) in idents) {
            val (ph, ps) = ident.patched
            val (oh, os) = ident.official
            if (live == (ph to ps) && liveBuild == ident.build) {
                chosen = tag to ident
                break
            }
            if (live == (oh to os) && liveBuild == ident.build) {
                onLog("现场已是 build ${ident.build} 的官方态，无需还原")
                return
            }
        }
        if (chosen == null) {
            error(
                "现场清单（build $liveBuild, ${live.first}/${live.second}）与任何一份带身份的备份都对不上，" +
                    "拒绝写入。备份里有: ${idents.keys}",
            )
        }
        val (tag, ident) = chosen
        val bd = File(broot, tag)
        val part = ident.part
        val off = ident.official
        for (rel in listOf("manifest.ssra", "chunks/$part", ETAG_REL)) {
            val bak = File(bd, bakName(rel))
            if (!bak.isFile) error("备份缺文件 ${bak.absolutePath}")
            bak.copyTo(File(gameres, rel), overwrite = true)
            onLog("还原 $rel")
        }
        val after = TreeIdentity.manifest(gameres) ?: error("还原后读不回清单")
        val afterPart = TreeIdentity.part(gameres, part) ?: error("还原后读不回分卷")
        if (after != (off.manifestHash to off.manifestSize) || afterPart != (off.partHash to off.partSize)) {
            error(
                "还原后身份与官方记录不符，请人工检查 ${bd.absolutePath}：清单 ${after.first}/${after.second}，" +
                    "官方记录 ${off.manifestHash}/${off.manifestSize}",
            )
        }
        onLog("还原后身份核对通过: build=${ident.build} 卷=$part")
    }

    /** 相对路径扁平化成备份文件名（chunks/lang_zht_b03_0.ssrc -> chunks__lang_zht_b03_0.ssrc.bak）。 */
    private fun bakName(rel: String): String = rel.replace("/", "__") + ".bak"

    // ───────────────────────── 汉化状态 ─────────────────────────

    /** 六态读数。快路只比身份（几十毫秒，可以待在检测按钮里）；深扫才真解压数残留。 */
    const val ST_MISSING = "资源未就绪"
    const val ST_UNKNOWN = "未判定"
    const val ST_OFFICIAL = "未汉化"
    const val ST_PATCHED = "已汉化"
    const val ST_STALE = "汉化失效"
    const val ST_FOREIGN = "现场异常"

    class ZhcnStatus(val state: String, val detail: String, val residual: Int = -1)

    /** 备份根目录下所有带身份的补丁记录（tag -> identity）；残缺的单独回给调用方，不静默丢弃。 */
    private fun identitiesOnDisk(gameres: File): Pair<LinkedHashMap<String, PatchIdentity>, List<String>> {
        val out = LinkedHashMap<String, PatchIdentity>()
        val bad = ArrayList<String>()
        val root = File(gameres.parentFile, BACKUP_DIR)
        root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val f = File(d, IDENTITY_FILE)
            if (!f.isFile) return@forEach
            runCatching { PatchIdentity.load(f) }
                .onSuccess { out[d.name] = it }
                .onFailure { bad.add("${d.name}（${it.message}）") }
        }
        return out to bad
    }

    /**
     * 现场汉化状态。
     *
     * 判据是"清单身份 **和** 分卷身份双双对得上"才算已汉化或官方态：只看清单会被
     * "更新器换回清单但卷没换"这类混合态骗过去，只看卷会被"我们写了卷没写清单"骗过去。
     */
    fun classify(gameres: File, deep: Boolean = false): ZhcnStatus {
        if (!File(gameres, "manifest.ssra").isFile) {
            return ZhcnStatus(ST_MISSING, "没有 manifest.ssra，资源未就绪或路径不对")
        }
        val man = try {
            SsraManifest(gameres)
        } catch (t: Throwable) {
            return ZhcnStatus(ST_FOREIGN, "清单解析失败：${t.message}")
        }
        val rec = man.files.firstOrNull { it.name == TEXT_DB }
            ?: return ZhcnStatus(ST_FOREIGN, "清单里找不到 $TEXT_DB（官方可能换了文本载体）")
        val partName = runCatching { man.partNames[man.locateSegment(rec.grp, rec.off).k] }
            .getOrElse { return ZhcnStatus(ST_FOREIGN, "无法定位 $TEXT_DB 所在分卷：${it.message}") }
        val partF = File(gameres, "chunks/$partName")
        if (!partF.isFile) {
            return ZhcnStatus(ST_MISSING, "分卷 $partName 不在盘上，请先让游戏下完繁中资源")
        }
        val live = TreeIdentity.manifest(gameres) ?: return ZhcnStatus(ST_MISSING, "清单读不到")
        val livePart = TreeIdentity.part(gameres, partName)
            ?: return ZhcnStatus(ST_FOREIGN, "分卷 $partName 长度异常（不足 16 字节卷尾）")
        val liveBuild = TreeIdentity.build(gameres)
        val (idents, bad) = identitiesOnDisk(gameres)

        // 深扫：真解压文本库、按本应用的词典复算还有多少条可转简。
        // 这一步与身份记录无关，因为"身份说已汉化"恰恰是最需要用文本再验一遍的时候。
        val residual: Int? = if (deep) {
            val plain = runCatching { man.extract(rec, decrypt = true) }
            val entries = plain.getOrNull()?.let { runCatching { TextDb.parse(it).entries }.getOrNull() }
            if (entries == null) {
                return ZhcnStatus(ST_FOREIGN, "深扫失败，读不回 $TEXT_DB 明文：${plain.exceptionOrNull()?.message}")
            }
            convertValues(entries, ConvertStats()).size
        } else {
            null
        }

        for ((tag, ident) in idents) {
            val off = ident.official
            val pat = ident.patched
            if (partName != ident.part) {
                return ZhcnStatus(
                    ST_STALE,
                    "$TEXT_DB 现在在 $partName 卷里，补丁记录的是 ${ident.part}——官方把繁中文本挪了卷，" +
                        "重新点一次繁转简即可（$tag）",
                )
            }
            val manOff = live == (off.manifestHash to off.manifestSize)
            val manPat = live == (pat.manifestHash to pat.manifestSize)
            val partOff = livePart == (off.partHash to off.partSize)
            val partPat = livePart == (pat.partHash to pat.partSize)
            if (manPat && partPat) {
                if (residual != null && residual > 0) {
                    return ZhcnStatus(
                        ST_FOREIGN,
                        "身份记录说已汉化，但按本应用词典复算还有 $residual 条可转简——补丁与文本不一致，" +
                            "建议先还原再重新繁转简（$tag）",
                        residual,
                    )
                }
                return ZhcnStatus(
                    ST_PATCHED,
                    "已汉化（build ${ident.build}，${ident.textDb.converted} 条已转简，清单与分卷身份都对得上" +
                        (if (residual == null) "）" else "，深扫残留 $residual 条）"),
                    residual ?: -1,
                )
            }
            if (manOff && partOff) {
                return ZhcnStatus(
                    ST_OFFICIAL,
                    "官方态（build $liveBuild），还没汉化" +
                        (if (residual == null) "" else "，深扫可转 $residual 条"),
                    residual ?: -1,
                )
            }
            if (ident.build != liveBuild) {
                return ZhcnStatus(
                    ST_STALE,
                    "补丁是给 build ${ident.build} 做的，现场已是 build $liveBuild——资源更新把汉化冲掉了，" +
                        "重新点一次繁转简即可（$tag）",
                )
            }
            if (manOff && partPat) {
                return ZhcnStatus(
                    ST_FOREIGN,
                    "清单是官方态但分卷还是补丁件（$partName）——按\"重新繁转简\"可自动纠正",
                )
            }
            if (manPat && partOff) {
                return ZhcnStatus(
                    ST_FOREIGN,
                    "清单还是补丁件但分卷已被换回官方（$partName）——汉化实际已失效，重新繁转简即可恢复",
                )
            }
        }
        val extra = if (bad.isEmpty()) "" else "；残缺的身份记录: $bad"
        if (residual == null) {
            return ZhcnStatus(
                ST_UNKNOWN,
                "build $liveBuild，本目录还没有可用的补丁身份记录，无法只凭清单判定" +
                    "（点「手动检测」会真读一遍文本库数残留）$extra",
            )
        }
        val entries = runCatching { TextDb.parse(man.extract(rec, decrypt = true)).entries.size }.getOrDefault(0)
        return if (residual == 0) {
            ZhcnStatus(ST_PATCHED, "build $liveBuild，文本已无繁体残留（$entries 条）", residual)
        } else {
            ZhcnStatus(ST_OFFICIAL, "build $liveBuild，还有 $residual 条是繁体待转简（共 $entries 条）", residual)
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
