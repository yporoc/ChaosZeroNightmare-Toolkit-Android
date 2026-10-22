package yporoc.czntoolkit.patcher

import com.github.luben.zstd.Zstd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import yporoc.czntoolkit.patcher.engine.SsraEngine
import yporoc.czntoolkit.patcher.engine.SsraManifest
import yporoc.czntoolkit.patcher.engine.TextDb
import yporoc.czntoolkit.patcher.engine.Xxh64
import yporoc.czntoolkit.patcher.engine.ZstdCodec
import java.io.File

/**
 * 拿 PC 参考实现当**外部**判据（判据不与被测产物同源）。
 *
 * 夹具 `testdata/oracle_b126/` 是 PC 端 `ssra_zhcn.py`(main c3f9edb) 直接在手机那份
 * build 126 官方树上跑出来的读数：官方明文哈希、PC 产物明文哈希、以及两侧结论不同的
 * 117 条（全部是「乾」：PC 转成「干」，opencc4j 1.14.0 没转，因为它的单字表刻意不含「乾」，
 * 词组表这条路需要 optional 的 jieba）。
 *
 * 最硬的一条是第三条：把我们产物里这 117 条换成 PC 的值后重算哈希，必须**正好等于**
 * PC 产物的哈希——这等价于证明除这 117 条外两侧逐字节一致。
 */
class ZhcnOracleTest {

    private val testData = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "testdata") }.firstOrNull { it.isDirectory }
        ?: error("找不到 testdata 目录")

    private val oracle = File(testData, "oracle_b126").also {
        check(File(it, "oracle_meta.txt").isFile) { "缺外部判据夹具: ${it.absolutePath}" }
    }

    private val meta: Map<String, String> = File(oracle, "oracle_meta.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { line ->
            val eq = line.indexOf('=')
            check(eq > 0) { "夹具行格式异常: $line" }
            line.substring(0, eq) to line.substring(eq + 1)
        }

    private data class Divergent(val key: ByteArray, val official: ByteArray, val ref: ByteArray, val kt: ByteArray)

    private val divergent: List<Divergent> by lazy {
        File(oracle, "opencc_divergence.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { p ->
                val c = p.split('\t')
                check(c.size == 4) { "分歧表列数异常: ${c.size}" }
                Divergent(unhex(c[0]), unhex(c[1]), unhex(c[2]), unhex(c[3]))
            }
    }

    private fun b126(): File = File(testData, "phone_gameres_b126").also {
        check(File(it, "manifest.ssra").isFile) { "缺 build 126 样本" }
    }

    private fun injectZstdJni() {
        ZstdCodec.compressAtLevel = { data, level -> Zstd.compress(data, level) }
        ZstdCodec.decompress = { blob, maxOut -> Zstd.decompress(blob, maxOut) }
    }

    private fun unhex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun plainOf(gameres: File): Pair<ByteArray, SsraManifest> {
        val man = SsraManifest(gameres)
        val rec = man.record(SsraEngine.TEXT_DB)
        return man.extract(rec, decrypt = true) to man
    }

    private fun patchedTree(res: SsraEngine.BuildResult): File {
        val root = File(File("build").absoluteFile, "test-scratch/oracle-" + System.nanoTime())
        val g = File(root, "gameres")
        File(g, "chunks").mkdirs()
        File(g, "manifest.ssra").writeBytes(res.manifest)
        File(g, "chunks/${res.partName}").writeBytes(res.part)
        File(g, SsraEngine.ETAG_REL).writeBytes(res.etag)
        return g
    }

    @Test
    fun `解码链与 PC 参考在同一份手机官方字节上一致`() {
        injectZstdJni()
        val (plain, man) = plainOf(b126())
        val parsed = TextDb.parse(plain)
        val partName = man.partNames[man.locateSegment(man.record(SsraEngine.TEXT_DB).grp,
            man.record(SsraEngine.TEXT_DB).off).k]
        assertEquals("外部判据要求的卷名", "lang_zht_b03_0.ssrc", partName)

        assertEquals("官方明文哈希必须等于 PC 参考读到的",
            meta.getValue("official_plain_xxh64").toULong(), Xxh64.hash(plain))
        assertEquals("官方明文长度", meta.getValue("official_plain_len").toInt(), plain.size)
        assertEquals("条目数", meta.getValue("official_entries").toInt(), parsed.entries.size)
        assertEquals("桶数", meta.getValue("official_hash_count").toInt(), parsed.hashCount)
        assertEquals("尾部页脚长度", meta.getValue("official_tail_len").toInt(), parsed.tail.size)

        val part = File(b126(), "chunks/$partName").readBytes()
        assertEquals("官方卷载荷哈希（现网官方态）",
            meta.getValue("official_part_payload_xxh64").toULong(), Xxh64.hash(part, 0, part.size - 16))
        val manB = File(b126(), "manifest.ssra").readBytes()
        assertEquals("官方清单哈希", meta.getValue("official_manifest_xxh64").toULong(), Xxh64.hash(manB))
    }

    @Test
    fun `产物明文与 PC 参考只差记录在案的 117 条乾字`() {
        injectZstdJni()
        val res = SsraEngine.build(b126()) { }
        val (ourPlain, _) = plainOf(patchedTree(res))
        val ourHash = Xxh64.hash(ourPlain)
        assertEquals("opencc4j 产物的哈希漂移了（转换器/词典/代码任一变了都要重新对账）",
            meta.getValue("kt_patched_plain_xxh64").toULong(), ourHash)
        assertNotEquals("与 PC 产物哈希必须不同（否则夹具与事实不符）",
            meta.getValue("ref_patched_plain_xxh64").toULong(), ourHash)

        val parsed = TextDb.parse(ourPlain)
        val byKey = HashMap<String, Int>()
        parsed.entries.forEachIndexed { i, e -> byKey[TextDb.keyHex(e.key)] = i }
        val subs = HashMap<Int, ByteArray>()
        for (d in divergent) {
            val k = TextDb.keyHex(d.key)
            val i = byKey[k] ?: error("分歧键在产物里找不到: $k")
            assertEquals("分歧条目的 PC 值长度必须与官方一致", d.official.size, d.ref.size)
            subs[i] = d.ref
        }
        assertEquals("分歧表必须覆盖夹具记录的全部键", divergent.size, subs.size)

        val rebuilt = TextDb.rebuild(parsed.header, parsed.hashCount, parsed.buckets, parsed.entries, subs, parsed.tail)
        assertEquals("替换后长度仍应等于清单 dec", meta.getValue("ref_patched_plain_len").toInt(), rebuilt.size)
        assertEquals(
            "把这 117 条换成 PC 的值后，必须逐字节等于 PC 参考产物（否则两侧还有别的分歧没被记录）",
            meta.getValue("ref_patched_plain_xxh64").toULong(), Xxh64.hash(rebuilt),
        )
    }

    @Test
    fun `分歧集合就是记录在案的那些键且全部是乾字`() {
        injectZstdJni()
        val res = SsraEngine.build(b126()) { }
        val (ourPlain, _) = plainOf(patchedTree(res))
        val (offPlain, _) = plainOf(b126())
        val ours = TextDb.parse(ourPlain).entries.associate { TextDb.keyHex(it.key) to it.value }
        val offs = TextDb.parse(offPlain).entries.associate { TextDb.keyHex(it.key) to it.value }
        assertEquals("键集必须一致", offs.size, ours.size)

        val changed = ours.filter { (k, v) -> !offs.getValue(k).contentEquals(v) }
        assertEquals("我们改动的条数必须等于夹具读数",
            meta.getValue("changed_official_by_kt").toInt(), changed.size)
        val fixtureKeys = divergent.map { TextDb.keyHex(it.key) }.toSet()
        assertEquals("夹具记录的分歧条数", meta.getValue("divergent_count").toInt(), fixtureKeys.size)

        // 分歧有两种形状，必须分开钉住：
        //   A 类：两侧都改了，只差「乾/干」这一个字（114 条）
        //   B 类：PC 改了、opencc4j 一个字没动，我们的值仍等于官方（3 条）
        var a = 0
        var b = 0
        for (d in divergent) {
            val k = TextDb.keyHex(d.key)
            assertEquals("该键我们的值必须等于夹具的 opencc4j 列", d.kt.toList(), ours.getValue(k).toList())
            assertTrue("该键的 PC 值必须与我们的不同", !d.kt.contentEquals(d.ref))
            if (d.kt.contentEquals(d.official)) {
                assertFalse("B 类键不该出现在改动集合里: $k", changed.containsKey(k))
                b++
            } else {
                assertTrue("A 类键必须在我们改动过的集合里: $k", changed.containsKey(k))
                assertTrue(
                    "A 类分歧必须只是 乾↔干 这一个字: $k",
                    String(d.ref, Charsets.UTF_8).contains("干") && String(d.kt, Charsets.UTF_8).contains("乾"),
                )
                a++
            }
        }
        assertEquals("A 类（乾/干之差）条数漂移了", meta.getValue("divergent_kt_also_changed").toInt(), a)
        assertEquals("B 类（opencc4j 完全没转）条数漂移了", meta.getValue("divergent_kt_left_official").toInt(), b)
        assertEquals("两类加起来必须正好是分歧表全部行", divergent.size, a + b)
    }
}
