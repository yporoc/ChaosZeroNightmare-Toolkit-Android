package yporoc.czntoolkit.patcher

import com.github.luben.zstd.Zstd
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yporoc.czntoolkit.patcher.engine.SsraEngine
import yporoc.czntoolkit.patcher.engine.ZstdCodec
import java.io.File

/**
 * apply/restore 落在真实文件上的行为断言（不看代码，只看现场）。
 * 临时树建在模块 build/ 下：apply 会把备份写到 gameres 的同级目录，
 * 建在系统 temp 会污染工作区外，建在这里则随构建目录一起清理。
 */
class ZhcnApplyTest {

    private val testData = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "testdata") }.firstOrNull { it.isDirectory }
        ?: error("找不到 testdata 目录")

    /** 返回一棵可写的 gameres 树，其父目录用于存放 czn_backup_original。 */
    private fun tree(): File {
        val src = File(testData, "phone_gameres_b126")
        check(File(src, "manifest.ssra").isFile) { "缺 build 126 样本: ${src.absolutePath}" }
        val root = File(File("build").absoluteFile, "test-scratch/" + System.nanoTime())
        val gameres = File(root, "gameres")
        gameres.mkdirs()
        src.copyRecursively(gameres, overwrite = true)
        ZstdCodec.compressAtLevel = { data, level -> Zstd.compress(data, level) }
        ZstdCodec.decompress = { blob, maxOut -> Zstd.decompress(blob, maxOut) }
        return gameres
    }

    @Test
    fun `应用补丁只改三件套且不碰 manifest ssra prev`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        SsraEngine.apply(gameres, res) { }

        assertTrue("manifest 应已写入补丁", File(gameres, "manifest.ssra").readBytes().contentEquals(res.manifest))
        assertTrue("分卷应已写入补丁", File(gameres, "chunks/${res.partName}").readBytes().contentEquals(res.part))
        assertTrue("etag 应已同步", File(gameres, SsraEngine.ETAG_REL).readBytes().contentEquals(res.etag))
        // manifest.ssra.prev 是更新器自己维护的上一版清单，我们既不写它也不删它
        assertFalse("不该出现 manifest.ssra.prev", File(gameres, "manifest.ssra.prev").exists())
        val leftovers = gameres.walkTopDown().filter { it.isFile }
            .map { it.name }.filter { it.endsWith(".prev") || it.endsWith(".tmp") }.toList()
        assertTrue("不该留下临时/prev 文件: $leftovers", leftovers.isEmpty())
    }
}
