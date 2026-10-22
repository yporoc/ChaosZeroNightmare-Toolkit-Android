package yporoc.czntoolkit.patcher

import com.github.luben.zstd.Zstd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yporoc.czntoolkit.patcher.engine.PatchIdentity
import yporoc.czntoolkit.patcher.engine.SsraEngine
import yporoc.czntoolkit.patcher.engine.TreeIdentity
import yporoc.czntoolkit.patcher.engine.Xxh64
import yporoc.czntoolkit.patcher.engine.ZstdCodec
import java.io.File

/**
 * 身份闸门的判据。要防的事故场景：备份目录不带版本、"已存在就永不覆盖"，
 * 跨版本点还原会把旧 build 的清单与旧卷塞进新资源树（同一台手机上确实同时躺着 126 和 88）。
 */
class ZhcnIdentityTest {

    private val testData = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "testdata") }.firstOrNull { it.isDirectory }
        ?: error("找不到 testdata 目录")

    private val ctxJson = """{"build_number": 126, "context": "pack-126-3554674e9022", "updated": true}"""
    private val expectedTag = "b126-pack-126-3554674e9022"

    private fun tree(): File {
        val src = File(testData, "phone_gameres_b126")
        check(File(src, "manifest.ssra").isFile) { "缺 build 126 样本: ${src.absolutePath}" }
        val root = File(File("build").absoluteFile, "test-scratch/" + System.nanoTime())
        val gameres = File(root, "gameres")
        gameres.mkdirs()
        src.copyRecursively(gameres, overwrite = true)
        File(gameres, "patch-complete.json").writeText(ctxJson, Charsets.UTF_8)
        ZstdCodec.compressAtLevel = { data, level -> Zstd.compress(data, level) }
        ZstdCodec.decompress = { blob, maxOut -> Zstd.decompress(blob, maxOut) }
        return gameres
    }

    private fun pristine(): Pair<ULong, Long> {
        val b = File(testData, "phone_gameres_b126/manifest.ssra").readBytes()
        return Xxh64.hash(b) to b.size.toLong()
    }

    private fun pristinePart(): Pair<ULong, Long> {
        val b = File(testData, "phone_gameres_b126/chunks/${SsraEngine.TARGET_PART}").readBytes()
        return Xxh64.hash(b, 0, b.size - 16) to b.size.toLong()
    }

    private fun failApply(gameres: File, res: SsraEngine.BuildResult): String {
        val r = runCatching { SsraEngine.apply(gameres, res) { } }
        assertTrue("本该拒绝却成功了", r.isFailure)
        return checkNotNull(r.exceptionOrNull()?.message)
    }

    @Test
    fun `身份记录文本能往返且缺键即报错`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        val back = PatchIdentity.fromText(res.identity.toText())
        assertEquals("build", res.identity.build, back.build)
        assertEquals("context", res.identity.context, back.context)
        assertEquals("卷名", res.identity.part, back.part)
        assertEquals("官方清单哈希", res.identity.official.manifestHash, back.official.manifestHash)
        assertEquals("补丁卷哈希", res.identity.patched.partHash, back.patched.partHash)
        assertEquals("尾部页脚长度", res.identity.textDb.tail, back.textDb.tail)
        assertEquals("备份目录名", expectedTag, res.identity.tag())

        val missing = res.identity.toText().lines().filterNot { it.startsWith("patched.part=") }.joinToString("\n")
        val r = runCatching { PatchIdentity.fromText(missing) }
        assertTrue("缺字段必须报错而不是当 0 用", r.isFailure)
        assertTrue("错误要点名缺哪个键: ${r.exceptionOrNull()?.message}",
            (r.exceptionOrNull()?.message ?: "").contains("patched.part"))
    }

    @Test
    fun `成功腿 应用到官方现场后备份带身份且现场可回读`() {
        val gameres = tree()
        val (offMan, offManSize) = pristine()
        val (offPart, offPartSize) = pristinePart()
        val res = SsraEngine.build(gameres) { }
        assertEquals("身份里的官方清单哈希要独立算得一致", offMan, res.identity.official.manifestHash)
        assertEquals("身份里的官方卷哈希要独立算得一致", offPart, res.identity.official.partHash)

        SsraEngine.apply(gameres, res) { }

        val bd = File(File(gameres.parentFile, SsraEngine.BACKUP_DIR), expectedTag)
        assertTrue("备份目录必须按 build+context 分目录: ${bd.absolutePath}", bd.isDirectory)
        for (n in listOf("manifest.ssra.bak", "chunks__${SsraEngine.TARGET_PART}.bak", "manifest.ssra.etag.bak", SsraEngine.IDENTITY_FILE)) {
            assertTrue("缺备份文件 $n", File(bd, n).isFile)
        }
        assertEquals("备份的清单就是官方那份", offManSize, File(bd, "manifest.ssra.bak").length())
        assertEquals("备份的卷就是官方那份", offPartSize, File(bd, "chunks__${SsraEngine.TARGET_PART}.bak").length())
        assertTrue("备份卷内容必须逐字节等于官方", File(bd, "chunks__${SsraEngine.TARGET_PART}.bak").readBytes()
            .contentEquals(File(testData, "phone_gameres_b126/chunks/${SsraEngine.TARGET_PART}").readBytes()))

        val live = TreeIdentity.manifest(gameres)!!
        assertEquals("现场清单已换成补丁件", res.identity.patched.manifestHash, live.first)
        val livePart = TreeIdentity.part(gameres, SsraEngine.TARGET_PART)!!
        assertEquals("现场卷已换成补丁件", res.identity.patched.partHash, livePart.first)
        assertNotEqualsHash(offPart, livePart.first)
    }

    @Test
    fun `成功腿 还原回到官方态且第二次点还原只说不需要`() {
        val gameres = tree()
        val (offMan, _) = pristine()
        val (offPart, _) = pristinePart()
        val res = SsraEngine.build(gameres) { }
        SsraEngine.apply(gameres, res) { }
        val logs = ArrayList<String>()
        SsraEngine.restore(gameres) { logs.add(it) }
        assertEquals("还原后清单回到官方哈希", offMan, TreeIdentity.manifest(gameres)!!.first)
        assertEquals("还原后卷回到官方哈希", offPart, TreeIdentity.part(gameres, SsraEngine.TARGET_PART)!!.first)
        assertTrue("还原必须有反向核对日志: $logs", logs.any { it.contains("还原后身份核对通过") })

        val logs2 = ArrayList<String>()
        SsraEngine.restore(gameres) { logs2.add(it) }
        assertTrue("再点一次应说明无需还原: $logs2", logs2.any { it.contains("无需还原") })
    }

    @Test
    fun `毒 现场清单被改过 apply 必须拒绝并给两边的数`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        val manF = File(gameres, "manifest.ssra")
        val tampered = manF.readBytes()
        tampered[0x400] = (tampered[0x400].toInt() xor 0x01).toByte()
        manF.writeBytes(tampered)

        val msg = failApply(gameres, res)
        assertTrue("必须说不符: $msg", msg.contains("现场清单与补丁记录的官方清单不符"))
        assertTrue("必须给出现场与记录两个数: $msg", msg.contains("请重新构建补丁"))
        assertFalse("拒绝时不许留下备份", File(File(gameres.parentFile, SsraEngine.BACKUP_DIR), expectedTag).exists())
    }

    @Test
    fun `毒 现场分卷是补丁件而清单是官方件 apply 必须拒绝备份`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        SsraEngine.apply(gameres, res) { }
        // 模拟更新器只把清单换回官方、卷还是我们那份
        File(gameres, "manifest.ssra").writeBytes(File(testData, "phone_gameres_b126/manifest.ssra").readBytes())

        val msg = failApply(gameres, res)
        assertTrue("必须指出分卷不是官方件: $msg", msg.contains("不是补丁记录的那个官方件"))
        assertTrue("必须说明拒绝备份的理由: $msg", msg.contains("拒绝备份"))
    }

    @Test
    fun `毒 已汉化态再构建必须被拒而不是把补丁当原件备份`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        SsraEngine.apply(gameres, res) { }

        val r = runCatching { SsraEngine.build(gameres) { } }
        assertTrue("已汉化态构建本该失败", r.isFailure)
        val msg = checkNotNull(r.exceptionOrNull()?.message)
        assertTrue("必须说清现场已无繁体可转: $msg", msg.contains("已无繁体可转"))
        assertTrue("必须给下一步: $msg", msg.contains("还原官方繁中") || msg.contains("更新一轮资源"))
    }

    @Test
    fun `毒 重复应用第二次只报告无需重复且不重取备份`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        SsraEngine.apply(gameres, res) { }
        val bd = File(File(gameres.parentFile, SsraEngine.BACKUP_DIR), expectedTag)
        val before = File(bd, "manifest.ssra.bak").readBytes()
        val logs = ArrayList<String>()
        SsraEngine.apply(gameres, res) { logs.add(it) }
        assertTrue("第二次必须说明无需重复: $logs", logs.any { it.contains("无需重复应用") })
        assertTrue("备份仍是官方那份", before.contentEquals(File(testData, "phone_gameres_b126/manifest.ssra").readBytes()))
    }

    @Test
    fun `毒 跨版本备份对不上现场时 restore 拒绝写入`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        val root = File(gameres.parentFile, SsraEngine.BACKUP_DIR)
        // 只放一份"给 build 88 做的"带身份备份（现场是 126）
        val stale = File(root, "b88-pack-88-e8a4e49e3a1e")
        stale.mkdirs()
        val fake = res.identity.toText()
            .replace("build=126", "build=88")
            .replace("context=pack-126-3554674e9022", "context=pack-88-e8a4e49e3a1e")
        File(stale, SsraEngine.IDENTITY_FILE).writeText(fake, Charsets.UTF_8)

        val r = runCatching { SsraEngine.restore(gameres) { } }
        assertTrue("跨版本备份不该被选中", r.isFailure)
        val msg = checkNotNull(r.exceptionOrNull()?.message)
        assertTrue("必须列出可用备份并拒绝: $msg", msg.contains("拒绝写入") && msg.contains("b88-pack-88-e8a4e49e3a1e"))
    }

    @Test
    fun `毒 旧版无身份备份 restore 拒绝并指路`() {
        val gameres = tree()
        val root = File(gameres.parentFile, SsraEngine.BACKUP_DIR)
        root.mkdirs()
        // 0.0.1beta 的备份形状：根目录三个 .bak，没有身份记录
        File(gameres, "manifest.ssra").copyTo(File(root, "manifest.ssra.bak"), overwrite = true)
        val r = runCatching { SsraEngine.restore(gameres) { } }
        assertTrue("无身份备份必须拒绝", r.isFailure)
        val msg = checkNotNull(r.exceptionOrNull()?.message)
        assertTrue("必须说明为什么不敢用: $msg", msg.contains("不带版本身份"))
        assertTrue("必须给替代路径: $msg", msg.contains("让游戏自己更新一轮资源"))
    }

    @Test
    fun `毒 身份记录被截断时 restore 不猜默认值`() {
        val gameres = tree()
        val res = SsraEngine.build(gameres) { }
        SsraEngine.apply(gameres, res) { }
        val idf = File(File(gameres.parentFile, SsraEngine.BACKUP_DIR), "$expectedTag/${SsraEngine.IDENTITY_FILE}")
        val kept = idf.readLines().filter { it.startsWith("build=") || it.startsWith("context=") || it.startsWith("part=") }
        idf.writeText(kept.joinToString("\n"), Charsets.UTF_8)

        val r = runCatching { SsraEngine.restore(gameres) { } }
        assertTrue("残缺身份必须拒绝", r.isFailure)
        val msg = checkNotNull(r.exceptionOrNull()?.message)
        assertTrue("必须点名损坏的身份记录: $msg", msg.contains("缺少字段"))
    }

    private fun assertNotEqualsHash(a: ULong, b: ULong) {
        assertTrue("官方与补丁哈希不应相同（$a）", a != b)
    }
}
