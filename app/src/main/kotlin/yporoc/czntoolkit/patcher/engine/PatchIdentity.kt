package yporoc.czntoolkit.patcher.engine

import java.io.File

/**
 * 补丁的版本身份记录（`czn_backup_original/<tag>/identity.txt`）。
 *
 * 存在的理由：备份目录名不含版本、且旧逻辑"文件已存在就永不覆盖"，跨版本点还原会把
 * 旧 build 的清单与旧卷塞进新资源树；同一台机器上同时装着新旧两个版本时尤其容易踩。
 * 这里把补丁产物和"它当初是从哪一份官方现场长出来的"钉在一起，
 * apply/restore 都要现场对得上记录才动手。
 *
 * 格式刻意保持扁平 k=v：引擎不依赖 Android API（JVM 可单测），也不为此引入 JSON 库。
 * 缺键一律报错，绝不给默认值——给默认值等于把"读不到身份"伪装成"身份是 0"。
 */
class PatchIdentity(
    val build: Long,
    val context: String,
    val part: String,
    val official: Side,
    val patched: Side,
    val textDb: TextDbStats,
) {
    data class Side(val manifestHash: ULong, val manifestSize: Long, val partHash: ULong, val partSize: Long)

    class TextDbStats(
        val comp: Long,
        val dec: Long,
        val entries: Int,
        val converted: Int,
        val tail: Int,
        val phase: Int,
    )

    /** 备份分目录名：build + 资源上下文，非 [A-Za-z0-9._-] 一律换成下划线。 */
    fun tag(): String {
        val ctx = context.ifEmpty { "nocontext" }
        val safe = ctx.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
        return "b$build-$safe"
    }

    fun toText(): String = buildString {
        append("build=").append(build).append('\n')
        append("context=").append(context).append('\n')
        append("part=").append(part).append('\n')
        append("official.manifest=").append(official.manifestHash.toString()).append('\n')
        append("official.manifestSize=").append(official.manifestSize).append('\n')
        append("official.part=").append(official.partHash.toString()).append('\n')
        append("official.partSize=").append(official.partSize).append('\n')
        append("patched.manifest=").append(patched.manifestHash.toString()).append('\n')
        append("patched.manifestSize=").append(patched.manifestSize).append('\n')
        append("patched.part=").append(patched.partHash.toString()).append('\n')
        append("patched.partSize=").append(patched.partSize).append('\n')
        append("textDb.comp=").append(textDb.comp).append('\n')
        append("textDb.dec=").append(textDb.dec).append('\n')
        append("textDb.entries=").append(textDb.entries).append('\n')
        append("textDb.converted=").append(textDb.converted).append('\n')
        append("textDb.tail=").append(textDb.tail).append('\n')
        append("textDb.phase=").append(textDb.phase).append('\n')
    }

    companion object {
        private val REQUIRED = listOf(
            "build", "context", "part",
            "official.manifest", "official.manifestSize", "official.part", "official.partSize",
            "patched.manifest", "patched.manifestSize", "patched.part", "patched.partSize",
        )

        fun fromText(text: String): PatchIdentity {
            val kv = HashMap<String, String>()
            for (line in text.split('\n')) {
                val t = line.trim()
                if (t.isEmpty()) continue
                val eq = t.indexOf('=')
                if (eq <= 0) throw IllegalArgumentException("identity.txt 行格式异常: \"$t\"")
                kv[t.substring(0, eq)] = t.substring(eq + 1)
            }
            val missing = REQUIRED.filter { !kv.containsKey(it) || kv[it]!!.isBlank() }
            if (missing.isNotEmpty()) {
                throw IllegalArgumentException("identity.txt 缺少字段 $missing，身份记录不完整，不敢据此写入")
            }
            fun num(k: String): Long = kv.getValue(k).toLongOrNull()
                ?: throw IllegalArgumentException("identity.txt 字段 $k 不是十进制数: \"${kv[k]}\"")
            fun unum(k: String): ULong = kv.getValue(k).toULongOrNull()
                ?: throw IllegalArgumentException("identity.txt 字段 $k 不是无符号十进制数: \"${kv[k]}\"")
            return PatchIdentity(
                build = num("build"),
                context = kv.getValue("context"),
                part = kv.getValue("part"),
                official = Side(unum("official.manifest"), num("official.manifestSize"), unum("official.part"), num("official.partSize")),
                patched = Side(unum("patched.manifest"), num("patched.manifestSize"), unum("patched.part"), num("patched.partSize")),
                textDb = TextDbStats(
                    comp = kv["textDb.comp"]?.toLongOrNull() ?: 0L,
                    dec = kv["textDb.dec"]?.toLongOrNull() ?: 0L,
                    entries = kv["textDb.entries"]?.toIntOrNull() ?: 0,
                    converted = kv["textDb.converted"]?.toIntOrNull() ?: 0,
                    tail = kv["textDb.tail"]?.toIntOrNull() ?: 0,
                    phase = kv["textDb.phase"]?.toIntOrNull() ?: -1,
                ),
            )
        }

        fun load(file: File): PatchIdentity = fromText(file.readText(Charsets.UTF_8))
    }
}

/**
 * 现场资源树的身份读数。所有写入决策都以这里的读数为准，不看文件名也不看 mtime。
 * 读不到就是 null，绝不返回 0 冒充"读到了 0"。
 */
object TreeIdentity {

    /** 清单 + 指定分卷的现场身份；任一文件缺失即 null。 */
    fun snapshot(gameres: File, partName: String): PatchIdentity.Side? {
        val manF = File(gameres, "manifest.ssra")
        val partF = if (partName.isEmpty()) null else File(gameres, "chunks/$partName")
        if (!manF.isFile || partF == null || !partF.isFile || partF.length() < 16) return null
        val man = manF.readBytes()
        val part = partF.readBytes()
        val payload = part.copyOfRange(0, part.size - 16)
        return PatchIdentity.Side(Xxh64.hash(man), man.size.toLong(), Xxh64.hash(payload), part.size.toLong())
    }

    /** 只读清单身份（清单不在则为 null）。 */
    fun manifest(gameres: File): Pair<ULong, Long>? {
        val manF = File(gameres, "manifest.ssra")
        if (!manF.isFile) return null
        val b = manF.readBytes()
        return Xxh64.hash(b) to b.size.toLong()
    }

    /** 只读分卷载荷身份（卷不在或过短则为 null）。 */
    fun part(gameres: File, partName: String): Pair<ULong, Long>? {
        if (partName.isEmpty()) return null
        val f = File(gameres, "chunks/$partName")
        if (!f.isFile || f.length() < 16) return null
        val b = f.readBytes()
        return Xxh64.hash(b.copyOfRange(0, b.size - 16)) to b.size.toLong()
    }

    fun build(gameres: File): Long {
        val manF = File(gameres, "manifest.ssra")
        if (!manF.isFile) return -1L
        val b = manF.readBytes()
        if (b.size < 12) return -1L
        return Le.u32(b, 8)
    }

    /** 资源上下文来自更新器写的 patch-complete.json；没有就当空串（备份目录名退化为 nocontext）。 */
    fun context(gameres: File): String {
        val f = File(gameres, "patch-complete.json")
        if (!f.isFile) return ""
        val text = f.readText(Charsets.UTF_8)
        val m = Regex("\"context\"\\s*:\\s*\"([^\"]*)\"").find(text)
        return m?.groupValues?.get(1) ?: ""
    }

    /** 按清单推导繁中卷名（不写死）；推导不出返回空串。 */
    fun currentPartName(gameres: File): String = try {
        val man = SsraManifest(gameres)
        val rec = man.record(SsraEngine.TEXT_DB)
        man.partNames[man.locateSegment(rec.grp, rec.off).k]
    } catch (_: Throwable) {
        ""
    }
}
