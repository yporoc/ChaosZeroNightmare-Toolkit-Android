package yporoc.czntoolkit.patcher

import com.github.luben.zstd.Zstd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import yporoc.czntoolkit.patcher.engine.SsraEngine
import yporoc.czntoolkit.patcher.engine.ZstdCodec
import java.io.File

/**
 * 汉化状态读数的判据。要点：没有补丁身份记录时**不许**猜"未汉化"，只能报"未判定"——
 * 猜错的方向是"用户以为没生效而反复点"，代价比多一次点击大得多。
 */
class ZhcnStatusTest {

    private val testData = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "testdata") }.firstOrNull { it.isDirectory }
        ?: error("找不到 testdata 目录")

    private val tag = "b126-pack-126-3554674e9022"

    private fun tree(): File {
        val src = File(testData, "phone_gameres_b126")
        check(File(src, "manifest.ssra").isFile) { "缺 build 126 样本" }
        val root = File(File("build").absoluteFile, "test-scratch/" + System.nanoTime())
        val gameres = File(root, "gameres")
        gameres.mkdirs()
        src.copyRecursively(gameres, overwrite = true)
        File(gameres, "patch-complete.json").writeText(
            """{"build_number": 126, "context": "pack-126-3554674e9022"}""", Charsets.UTF_8,
        )
        ZstdCodec.compressAtLevel = { data, level -> Zstd.compress(data, level) }
        ZstdCodec.decompress = { blob, maxOut -> Zstd.decompress(blob, maxOut) }
        return gameres
    }

    private fun backupRoot(gameres: File) = File(gameres.parentFile, SsraEngine.BACKUP_DIR)

    @Test
    fun `官方态且无身份记录时只报未判定不解压`() {
        val gameres = tree()
        val st = SsraEngine.classify(gameres, deep = false)
        assertEquals("没有补丁记录不能断定是官方态", SsraEngine.ST_UNKNOWN, st.state)
        assertTrue("必须指引手动检测: ${st.detail}", st.detail.contains("手动检测"))
        assertEquals("快路不数残留", -1, st.residual)
    }

    @Test
    fun `深扫能认出未汉化并给出残留条数`() {
        val gameres = tree()
        val st = SsraEngine.classify(gameres, deep = true)
        assertEquals("官方繁体树应判为未汉化", SsraEngine.ST_OFFICIAL, st.state)
        assertTrue("残留条数要落在实测区间: ${st.residual}", st.residual in 100_000..110_000)
        assertTrue("详情带 build: ${st.detail}", st.detail.contains("build 126"))
    }

    @Test
    fun `应用后快路即判已汉化 深扫残留为零`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        SsraEngine.apply(gameres, res) { }
        val fast = SsraEngine.classify(gameres, deep = false)
        assertEquals("已汉化（清单与分卷身份双查）", SsraEngine.ST_PATCHED, fast.state)
        assertTrue("详情要给出转换条数: ${fast.detail}", fast.detail.contains("条已转简"))

        val deep = SsraEngine.classify(gameres, deep = true)
        assertEquals("深扫也必须判已汉化", SsraEngine.ST_PATCHED, deep.state)
        assertEquals("产物里不该再有可转的繁体", 0, deep.residual)
    }

    @Test
    fun `还原后有身份记录时判未汉化`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        SsraEngine.apply(gameres, res) { }
        SsraEngine.restore(gameres) { }
        val st = SsraEngine.classify(gameres, deep = false)
        assertEquals("回到官方态应判未汉化", SsraEngine.ST_OFFICIAL, st.state)
        assertTrue("详情带 build: ${st.detail}", st.detail.contains("官方态"))
    }

    @Test
    fun `版本被冲掉时判汉化失效并指路重新点一次`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        SsraEngine.apply(gameres, res) { }
        // 模拟资源更新：现场清单/卷都是 build 126 的官方件，备份区里只剩一份"给 build 88 做的"身份记录
        File(backupRoot(gameres), tag).deleteRecursively()
        val staleDir = File(backupRoot(gameres), "b88-pack-88-e8a4e49e3a1e")
        staleDir.mkdirs()
        File(staleDir, SsraEngine.IDENTITY_FILE).writeText(
            """
            build=88
            context=pack-88-e8a4e49e3a1e
            part=lang_zht_b03_0.ssrc
            official.manifest=1111111111111111111
            official.manifestSize=7506387
            official.part=2222222222222222222
            official.partSize=24399744
            patched.manifest=3333333333333333333
            patched.manifestSize=7506387
            patched.part=4444444444444444444
            patched.partSize=24399744
            textDb.converted=97497
            """.trimIndent(), Charsets.UTF_8,
        )
        File(gameres, "manifest.ssra").writeBytes(File(testData, "phone_gameres_b126/manifest.ssra").readBytes())
        File(gameres, "chunks/${SsraEngine.TARGET_PART}").writeBytes(
            File(testData, "phone_gameres_b126/chunks/${SsraEngine.TARGET_PART}").readBytes(),
        )
        val st = SsraEngine.classify(gameres, deep = false)
        assertEquals("跨版本应判失效（实得 ${st.state} / ${st.detail}）", SsraEngine.ST_STALE, st.state)
        assertTrue("必须给出下一步: ${st.detail}", st.detail.contains("重新点一次繁转简"))
    }

    @Test
    fun `清单与分卷状态不一致时判现场异常`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        SsraEngine.apply(gameres, res) { }
        // 混合态：清单被换回官方，卷还是补丁件
        File(gameres, "manifest.ssra").writeBytes(File(testData, "phone_gameres_b126/manifest.ssra").readBytes())
        val st = SsraEngine.classify(gameres, deep = false)
        assertEquals(SsraEngine.ST_FOREIGN, st.state)
        assertTrue("必须说明是哪一半: ${st.detail}", st.detail.contains("分卷还是补丁件"))
    }

    @Test
    fun `分卷缺失判资源未就绪`() {
        val gameres = tree()
        check(File(gameres, "chunks/${SsraEngine.TARGET_PART}").delete())
        val st = SsraEngine.classify(gameres, deep = false)
        assertEquals(SsraEngine.ST_MISSING, st.state)
        assertTrue("必须指路先下资源: ${st.detail}", st.detail.contains("请先让游戏下完繁中资源"))
    }

    @Test
    fun `残缺身份记录要显式说出来而不是当没有`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        SsraEngine.apply(gameres, res) { }
        val idf = File(File(backupRoot(gameres), tag), SsraEngine.IDENTITY_FILE)
        idf.writeText("build=126\ncontext=pack-126-3554674e9022\n", Charsets.UTF_8)
        val st = SsraEngine.classify(gameres, deep = false)
        assertEquals("没有可用身份时只能未判定", SsraEngine.ST_UNKNOWN, st.state)
        assertTrue("必须点名残缺记录: ${st.detail}", st.detail.contains("残缺的身份记录"))
    }
}
