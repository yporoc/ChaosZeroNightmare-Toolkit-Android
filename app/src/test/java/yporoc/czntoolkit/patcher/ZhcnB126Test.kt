package yporoc.czntoolkit.patcher

import com.github.luben.zstd.Zstd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import yporoc.czntoolkit.patcher.engine.InnerXor
import yporoc.czntoolkit.patcher.engine.SsraEngine
import yporoc.czntoolkit.patcher.engine.SsraManifest
import yporoc.czntoolkit.patcher.engine.TextDb
import yporoc.czntoolkit.patcher.engine.Xxh64
import yporoc.czntoolkit.patcher.engine.ZstdCodec
import java.io.File
import java.nio.file.Files

/**
 * build 126（官方 text.db 带 38 字节尾部页脚）的回归样本。
 * 与 GoldenTest 用的 build 88 样本互补：那棵树 tail 长度是 0，这棵树是 38。
 * 判据只吃结构量，不吃压缩字节——真机 cznfast(zstd 1.5.6) 与这里 zstd-jni(1.5.7) 出的帧字节本就不同。
 */
class ZhcnB126Test {

    /** 向上走目录找 testdata（避免"克隆放深一层就静默跳过"）；也可用 CZN_TESTDATA 直接指定。 */
    private val testData = sequenceOf(
        System.getenv("CZN_TESTDATA")?.let(::File),
    ).filterNotNull().plus(
        generateSequence(File(".").absoluteFile) { it.parentFile }.map { File(it, "testdata") },
    ).firstOrNull { it.isDirectory }
        ?: error("找不到测试数据目录：请设 CZN_TESTDATA 或在某级父目录放 testdata/")

    private val b126 = File(testData, "phone_gameres_b126").also {
        check(File(it, "manifest.ssra").isFile) { "缺测试数据: ${it.absolutePath}/manifest.ssra" }
        check(File(it, "chunks/${SsraEngine.TARGET_PART}").isFile) { "缺测试数据: 分卷" }
    }

    private fun injectZstdJni() {
        ZstdCodec.compress = { data, maxSize ->
            var out: ByteArray? = null
            for (lvl in intArrayOf(3, 6, 9, 12, 16, 19)) {
                val c = Zstd.compress(data, lvl)
                if (c.size <= maxSize) { out = c; break }
            }
            out ?: error("所有压缩级别均超过原帧尺寸（maxSize=$maxSize）")
        }
        ZstdCodec.decompress = { blob, maxOut -> Zstd.decompress(blob, maxOut) }
    }

    private fun gameresTree(partName: String, manifest: ByteArray, part: ByteArray, etag: ByteArray): File {
        val dir = Files.createTempDirectory("czn-b126").toFile()
        File(dir, "chunks").mkdirs()
        File(dir, "manifest.ssra").writeBytes(manifest)
        File(dir, "chunks/$partName").writeBytes(part)
        File(dir, SsraEngine.ETAG_REL).writeBytes(etag)
        return dir
    }

    /** 按真读者的路径把产物卷读回 text.db 明文。 */
    private fun readBackPlain(gameres: File): Pair<ByteArray, SsraManifest.FileRec> {
        val man = SsraManifest(gameres)
        val rec = man.record(SsraEngine.TEXT_DB)
        val enc = man.extract(rec, decrypt = false)
        val phase = checkNotNull(InnerXor.findPhase(enc.copyOfRange(0, minOf(64, enc.size)))) { "读不回相位" }
        return InnerXor.transformInPlace(enc, phase) to rec
    }

    @Test
    fun `build126 尾部页脚原样带回且分卷与官方等长`() {
        injectZstdJni()
        val officialPart = File(b126, "chunks/${SsraEngine.TARGET_PART}").readBytes()
        val officialManifest = File(b126, "manifest.ssra").readBytes()
        val officialEtag = File(b126, SsraEngine.ETAG_REL).readBytes()

        val res = SsraEngine.build(b126) { }
        assertTrue(
            "构建日志里必须有页脚自证这一行: ${res.logs}",
            res.logs.any { it.startsWith("重建自证通过: 尾部原样字节 38") },
        )

        // 分卷逐字节等长（CDN Range 越界会 E22411 死循环）
        assertEquals("分卷必须与官方逐字节同尺寸", officialPart.size.toLong(), res.part.size.toLong())

        // 清单只允许该卷 XXH64 一处 8 字节不同
        val diffs = res.manifest.indices.filter { res.manifest[it] != officialManifest[it] }
        val allowed = (0x2F8 until 0x300).toSet()
        assertEquals("清单差异字节数", 8, diffs.size)
        assertTrue("清单差异必须全部落在该卷 XXH64 字段 @$allowed，实得 $diffs", allowed.containsAll(diffs))
        assertEquals("官方清单长度不变", officialManifest.size, res.manifest.size)

        // etag 行 2/3 同步为新清单身份，行 0/1 与分隔符（手机实测 LF）保持原样
        val (oLines, oSep) = SsraEngine.parseEtag(officialEtag)
        val (nLines, nSep) = SsraEngine.parseEtag(res.etag)
        assertEquals("etag 分隔符不能变", oSep, nSep)
        assertEquals("etag 行数", oLines.size, nLines.size)
        assertEquals("etag 首行 URL 不动", oLines[0], nLines[0])
        assertEquals("etag 第 2 行不动", oLines[1], nLines[1])
        assertEquals("etag 第 3 行 = XXH64(新清单)", Xxh64.hash(res.manifest).toString(), nLines[2])
        assertEquals("etag 第 4 行 = 新清单尺寸", res.manifest.size.toString(), nLines[3])
    }

    @Test
    fun `build126 产物读回后无繁体残留且键集与页脚不变`() {
        injectZstdJni()
        val res = SsraEngine.build(b126) { }
        val tree = gameresTree(res.partName, res.manifest, res.part, res.etag)

        // 官方明文（对照组）
        val (offPlain, offRec) = readBackPlain(b126)
        val off = TextDb.parse(offPlain)
        val (newPlain, newRec) = readBackPlain(tree)
        val neu = TextDb.parse(newPlain)

        assertEquals("解压尺寸必须等于清单 dec", offRec.dec.toInt(), newPlain.size)
        assertEquals("清单 dec 未被改动", offRec.dec, newRec.dec)
        assertEquals("条目数不变", off.entries.size, neu.entries.size)
        assertEquals("桶数不变", off.hashCount, neu.hashCount)
        assertEquals("尾部页脚逐字节原样", off.tail.toList(), neu.tail.toList())
        assertEquals("尾部页脚长度（build 126 实测 38）", 38, neu.tail.size)

        // 键与未转值一字未改；转过的值只改文本段
        val offByKey = off.entries.associate { TextDb.keyHex(it.key) to it.value }
        var changed = 0
        for (e in neu.entries) {
            val o = offByKey[TextDb.keyHex(e.key)] ?: error("键集漂移: ${String(e.key)}")
            if (!o.contentEquals(e.value)) {
                changed++
                assertEquals("值的 id 前缀段不许动", o.indexOfFirst { it == 0.toByte() }, e.value.indexOfFirst { it == 0.toByte() })
                assertTrue("值总长必须逐条不变", o.size == e.value.size)
            }
        }
        assertTrue("实际转换量异常: $changed", changed in 100_000..110_000)

        // 功能判据：产物再过一次转换器必须零残留
        val stats = SsraEngine.convertValues(neu.entries, SsraEngine.ConvertStats()).size
        assertEquals("产物里不应再有可转简的繁体: $stats", 0, stats)
    }

    @Test
    fun `build126 重复构建逐字节幂等`() {
        injectZstdJni()
        val a = SsraEngine.build(b126) { }
        val b = SsraEngine.build(b126) { }
        assertTrue("manifest 幂等", a.manifest.contentEquals(b.manifest))
        assertTrue("分卷幂等", a.part.contentEquals(b.part))
        assertTrue("etag 幂等", a.etag.contentEquals(b.etag))
    }

    /** 互补样本：build 88 的 text.db 没有尾部页脚（实测 tail=0），页脚逻辑必须对它保持"什么都不搬"。 */
    @Test
    fun `build88 互补样本 tail 为零且仍然等长`() {
        injectZstdJni()
        val b88 = File(testData, "phone_gameres")
        check(File(b88, "manifest.ssra").isFile) { "缺 build 88 样本: ${b88.absolutePath}" }
        val officialPart = File(b88, "chunks/${SsraEngine.TARGET_PART}").readBytes()
        val officialManifest = File(b88, "manifest.ssra").readBytes()
        val res = SsraEngine.build(b88) { }
        assertTrue("88 样本必须走『尾部 0 字节』这条腿: ${res.logs}", res.logs.any { it == "重建自证通过: 尾部原样字节 0" })
        assertEquals("88 分卷等长", officialPart.size.toLong(), res.part.size.toLong())
        assertEquals("88 官方卷尺寸（与 126 的 25027184 不同）", 24399744L, res.part.size.toLong())
        val diffs = res.manifest.indices.filter { res.manifest[it] != officialManifest[it] }
        assertEquals("88 清单也只差 8 字节", 8, diffs.size)
    }
}
