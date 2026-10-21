package yporoc.czntoolkit.patcher

import com.github.luben.zstd.Zstd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import yporoc.czntoolkit.patcher.engine.SsraEngine
import yporoc.czntoolkit.patcher.engine.SsraManifest
import yporoc.czntoolkit.patcher.engine.ZstdCodec
import java.io.File
import java.nio.file.Files

/**
 * P2 的停机腿：官方把 text.db 挪卷、加后随条目、换封装方式、改帧头、或某档余量落不进帧头时，
 * 工具必须带实测数字停下来并说清下一步，不许静默罢工、也不许硬垫出一个坏帧。
 * 每条毒都配一条成功腿，防止"闸门在坏自己身上把被测物报成坏了"。
 */
class ZhcnP2PoisonTest {

    private val testData = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "testdata") }.firstOrNull { it.isDirectory }
        ?: error("找不到 testdata 目录")

    private val b126 = File(testData, "phone_gameres_b126").also {
        check(File(it, "manifest.ssra").isFile) { "缺 build 126 样本: ${it.absolutePath}" }
    }

    private val oldName = "lang_zht_b03_0.ssrc"
    private val newName = "lang_zht_b99_0.ssrc"

    /** 复制一棵可写的 gameres 树。 */
    private fun copyTree(): File {
        val dir = Files.createTempDirectory("czn-p2").toFile()
        dir.deleteRecursively()
        b126.copyRecursively(dir, overwrite = true)
        return dir
    }

    private fun injectRealZstd() {
        ZstdCodec.compressAtLevel = { data, level -> Zstd.compress(data, level) }
        ZstdCodec.decompress = { blob, maxOut -> Zstd.decompress(blob, maxOut) }
    }

    private fun failWith(dir: File): String {
        val r = runCatching { SsraEngine.build(dir) { } }
        assertTrue("本该停机却成功了: ${r.exceptionOrNull()}", r.isFailure)
        return checkNotNull(r.exceptionOrNull()?.message) { "停机消息为空" }
    }

    private fun ByteArray.occurrences(needle: ByteArray): ArrayList<Int> {
        val idx = ArrayList<Int>()
        var i = indexOf(needle, 0)
        while (i >= 0) {
            idx.add(i)
            i = indexOf(needle, i + 1)
        }
        return idx
    }

    private fun ByteArray.indexOf(needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        var i = from
        while (i <= size - needle.size) {
            var j = 0
            while (j < needle.size && this[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    private fun renamePartInManifest(manifest: ByteArray, to: String): ByteArray {
        val out = manifest.copyOf()
        val hits = out.occurrences(oldName.toByteArray(Charsets.UTF_8))
        assertEquals("清单里卷名应只出现 1 次（实测 ${hits.size} 次）", 1, hits.size)
        to.toByteArray(Charsets.UTF_8).copyInto(out, hits[0])
        return out
    }

    @Test
    fun `成功腿 卷名与文件同时改名后仍按清单出补丁`() {
        injectRealZstd()
        val dir = copyTree()
        File(dir, "manifest.ssra").writeBytes(renamePartInManifest(File(dir, "manifest.ssra").readBytes(), newName))
        val old = File(dir, "chunks/$oldName")
        assertTrue("改名分卷文件失败", old.renameTo(File(dir, "chunks/$newName")))

        val res = SsraEngine.build(dir) { }
        assertEquals("按清单动态定位到的新卷名", newName, res.partName)
        assertTrue("必须说明已按清单动态处理: ${res.logs}", res.logs.any { it.contains("已按清单动态处理") })
        assertEquals("产物仍与官方等长", File(dir, "chunks/$newName").length(), res.part.size.toLong())
        assertEquals("尾部页脚自证仍然通过", 1, res.logs.count { it == "重建自证通过: 尾部原样字节 38" })
    }

    @Test
    fun `毒1 清单卷名改了但分卷文件没改名 必须停机`() {
        injectRealZstd()
        val dir = copyTree()
        File(dir, "manifest.ssra").writeBytes(renamePartInManifest(File(dir, "manifest.ssra").readBytes(), newName))

        val msg = failWith(dir)
        assertTrue("停机消息要给出实得卷名与路径: $msg", msg.contains("分卷不在盘上") && msg.contains(newName))
        assertTrue("停机消息要给下一步: $msg", msg.contains("请先启动游戏把繁中资源下完"))
    }

    @Test
    fun `毒2 后续条目非零 必须停机并给实测计数`() {
        injectRealZstd()
        val dir = copyTree()
        val man = SsraManifest(dir)
        val rec = man.record(SsraEngine.TEXT_DB)
        val seg = man.segmentContaining(rec.grp, rec.off)
        val partF = File(dir, "chunks/${man.partNames[seg.k]}")
        val bytes = partF.readBytes()
        val local = (rec.off - seg.start).toInt()
        bytes[local + rec.comp.toInt()] = 0x7F
        bytes[local + rec.comp.toInt() + 3] = 0x11
        partF.writeBytes(bytes)

        val msg = failWith(dir)
        assertTrue("必须报实测非零字节数: $msg", msg.contains("实测 2 个非零字节"))
        assertTrue("必须说明后果: $msg", msg.contains("会挤动后续条目"))
    }

    @Test
    fun `毒3 压缩方法不再是 zstd 帧 必须停机`() {
        injectRealZstd()
        val dir = copyTree()
        val man = SsraManifest(dir)
        val rec = man.record(SsraEngine.TEXT_DB)
        val raw = File(dir, "manifest.ssra").readBytes()
        val filesOff = leU64(raw, 0x30)
        val methOff = (filesOff + rec.k * 40L + 32L).toInt()
        raw[methOff] = 2
        raw[methOff + 1] = 0
        File(dir, "manifest.ssra").writeBytes(raw)

        val msg = failWith(dir)
        assertTrue("必须报实得 meth: $msg", msg.contains("meth=2"))
        assertTrue("必须说清打法不适用: $msg", msg.contains("垫帧") || msg.contains("等长"))
    }

    @Test
    fun `毒4 卷内偏移处帧头被改 必须停机并给实得字节`() {
        injectRealZstd()
        val dir = copyTree()
        val man = SsraManifest(dir)
        val rec = man.record(SsraEngine.TEXT_DB)
        val seg = man.segmentContaining(rec.grp, rec.off)
        val partF = File(dir, "chunks/${man.partNames[seg.k]}")
        val bytes = partF.readBytes()
        val local = (rec.off - seg.start).toInt()
        bytes[local] = 0
        partF.writeBytes(bytes)

        val msg = failWith(dir)
        assertTrue("必须给出实得帧头: $msg", msg.contains("不是 zstd 帧头") && msg.contains("00 b5 2f fd"))
        assertTrue("必须给出卷内偏移: $msg", msg.contains("偏移 $local"))
    }

    @Test
    fun `毒5 每档余量都是 3 字节时必须拒绝硬垫而不是写出坏帧`() {
        val dir = copyTree()
        val comp = SsraManifest(dir).record(SsraEngine.TEXT_DB).comp
        ZstdCodec.decompress = { blob, maxOut -> Zstd.decompress(blob, maxOut) }
        // 6 个档全部返回"比官方帧小 3 字节"的假数据：放得下 8 字节帧头的条件是 pad==0 或 >=8
        ZstdCodec.compressAtLevel = { _, _ -> ByteArray((comp - 3).toInt()) }

        val msg = failWith(dir)
        assertTrue("必须报各档实测余量: $msg", msg.contains("各档余量 3:+3 6:+3 9:+3 12:+3 16:+3 19:+3"))
        assertTrue("必须说清需要 0 或 >=8: $msg", msg.contains("需要 0 或 >=8 字节"))
    }

    private fun leU64(d: ByteArray, o: Int): Long {
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or (d[o + i].toLong() and 0xFF)
        return r
    }
}
