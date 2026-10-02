package yporoc.czntoolkit.patcher

import yporoc.czntoolkit.patcher.engine.SsraEngine
import yporoc.czntoolkit.patcher.engine.SsraManifest
import yporoc.czntoolkit.patcher.engine.ZstdCodec
import yporoc.czntoolkit.patcher.engine.Xxh64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 黄金测试，两组数据：
 * 1. PC 端 zhcn_patch/backup_original（内容已是简体）——验证容器机制与 PC 工具逐字节一致；
 * 2. 手机官方繁中（testdata/phone_gameres）——验证真实繁转简与全部铁律。
 */
class GoldenTest {

    private val zhcnRoot = sequenceOf(
        System.getenv("CZN_ZHCN_ROOT"),
        "../ChaosZero-Toolkit-Extra/zhcn_patch",
        "../../czn/ChaosZero-Toolkit-Extra/zhcn_patch",
        "../../../czn/ChaosZero-Toolkit-Extra/zhcn_patch",
    ).firstOrNull { it != null && File(it).isDirectory }?.let(::File)

    private val testData = sequenceOf(
        File("testdata"),
        File("../testdata"),
        File("../../testdata"),
    ).firstOrNull { it.isDirectory }

    private fun makeGameres(root: File, manifest: ByteArray, part: ByteArray, etag: ByteArray): File {
        val gameres = File(root, "gameres")
        File(gameres, "chunks").mkdirs()
        File(gameres, "manifest.ssra").writeBytes(manifest)
        File(gameres, "chunks/${SsraEngine.TARGET_PART}").writeBytes(part)
        File(gameres, SsraEngine.ETAG_REL).writeBytes(etag)
        return gameres
    }

    /** PC 端注入 zstd-jni（Windows .so）压缩器；真机由服务注入 cznfast（bionic zstd）。同为标准 zstd 帧。 */
    private fun injectCompressor() {
        ZstdCodec.compress = { data, maxSize -> compressWithLevels(data, maxSize) }
        ZstdCodec.decompress = { blob, maxOut ->
            com.github.luben.zstd.ZstdInputStream(java.io.ByteArrayInputStream(blob)).use { it.readNBytes(maxOut) }
        }
    }

    private fun compressWithLevels(data: ByteArray, maxSize: Int): ByteArray {
        for (lvl in intArrayOf(3, 6, 9, 12, 16, 19)) {
            val c = com.github.luben.zstd.Zstd.compress(data, lvl)
            if (c.size <= maxSize) return c
        }
        throw IllegalStateException("所有压缩级别均超过原帧尺寸（maxSize=$maxSize）")
    }

    private fun assertSelfConsistent(res: SsraEngine.BuildResult, gameres: File) {
        // 分卷 footer：SSRC + 卷号 + XXH64(payload)
        val payload = res.part.copyOfRange(0, res.part.size - 16)
        assertEquals("SSRC", String(res.part, payload.size, 4, Charsets.UTF_8))
        var expected = 0uL
        for (i in 7 downTo 0) expected = (expected shl 8) or (res.part[payload.size + 8 + i].toLong() and 0xFFL).toULong()
        assertEquals("footer XXH64 与载荷不符", Xxh64.hash(payload), expected)

        // manifest 中目标卷的 hash 字段 == footer hash（目标卷不一定是第 0 卷）
        val verify = Files.createTempDirectory("czn-verify").toFile()
        File(verify, "chunks").mkdirs()
        File(verify, "manifest.ssra").writeBytes(res.manifest)
        File(verify, "chunks/${SsraEngine.TARGET_PART}").writeBytes(res.part)
        val man = SsraManifest(verify)
        val part = man.parts.first { man.partNames[it.k] == SsraEngine.TARGET_PART }
        assertEquals("manifest 卷 hash 未同步", Xxh64.hash(payload), part.hash)

        // etag：行2 = XXH64(manifest)，行3 = 尺寸（分隔符自适应）
        val (lines, _) = SsraEngine.parseEtag(res.etag)
        assertTrue(lines.size >= 4)
        assertEquals(Xxh64.hash(res.manifest).toString(), lines[2])
        assertEquals(res.manifest.size.toString(), lines[3])
    }

    @Test
    fun `容器机制与 PC 工具产物逐字节一致`() {
        val zhcn = zhcnRoot
        assumeTrue(zhcn != null && testData != null)
        val bak = File(zhcn, "backup_original")
        assumeTrue(File(bak, "manifest.ssra.bak").isFile)
        val tmp = Files.createTempDirectory("czn-pc").toFile()
        val gameres = makeGameres(
            tmp,
            File(bak, "manifest.ssra.bak").readBytes(),
            File(bak, "chunks__lang_zht_b03_0.ssrc.bak").readBytes(),
            File(bak, "manifest.ssra.etag.bak").readBytes(),
        )
        injectCompressor()
        val res = SsraEngine.build(gameres) { }
        // 该备份内容已是简体：转换为 0 是预期，产物应与 PC 工具在相同输入上的输出逐字节一致
        assertEquals(0, res.stats.converted)
        assertArrayEquals("manifest 与 PC 产物不一致", File(zhcn, "manifest.ssra").readBytes(), res.manifest)
        assertArrayEquals("分卷与 PC 产物不一致", File(zhcn, SsraEngine.TARGET_PART).readBytes(), res.part)
        assertSelfConsistent(res, gameres)
    }

    @Test
    fun `官方繁中数据真实转换并满足全部铁律`() {
        assumeTrue(testData != null)
        val phone = File(testData, "phone_gameres")
        assumeTrue(File(phone, "manifest.ssra").isFile)
        val tmp = Files.createTempDirectory("czn-phone").toFile()
        val gameres = makeGameres(
            tmp,
            File(phone, "manifest.ssra").readBytes(),
            File(phone, "chunks/${SsraEngine.TARGET_PART}").readBytes(),
            File(phone, "manifest.ssra.etag").readBytes(),
        )
        val origPartSize = File(gameres, "chunks/${SsraEngine.TARGET_PART}").length()

        injectCompressor()
        val res = SsraEngine.build(gameres) { }
        assertTrue("转换条数异常: ${res.stats.converted}", res.stats.converted in 90_000..300_000)
        assertEquals("分卷尺寸必须与官方逐字节同尺寸", origPartSize, res.part.size.toLong())
        assertSelfConsistent(res, gameres)

        // 确定性：重复构建逐字节一致（幂等）
        val res2 = SsraEngine.build(gameres) { }
        assertArrayEquals(res.manifest, res2.manifest)
        assertArrayEquals(res.part, res2.part)
        assertArrayEquals(res.etag, res2.etag)
    }
}
