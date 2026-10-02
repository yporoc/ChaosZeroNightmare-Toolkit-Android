package yporoc.czntoolkit.patcher.engine

import java.io.File
import java.io.RandomAccessFile

/** zstd 编解码注入点：Android 注入 cznfast 原生实现，PC 单测注入 zstd-jni。 */
object ZstdCodec {
    /** 解压 meth==1 的帧：参数 (压缩数据, 解压上限)，返回原始字节。 */
    @Volatile
    var decompress: ((blob: ByteArray, maxOut: Int) -> ByteArray)? = null

    /** 压缩：参数 (数据, 最大输出)，返回标准 zstd 帧。 */
    @Volatile
    var compress: ((data: ByteArray, maxSize: Int) -> ByteArray)? = null

    fun requireDecompress(): (ByteArray, Int) -> ByteArray =
        checkNotNull(decompress) { "未注入 zstd 解压器" }

    fun requireCompress(): (ByteArray, Int) -> ByteArray =
        checkNotNull(compress) { "未注入 zstd 压缩器" }
}

/** 小端序字节读写工具（对应 Python struct '<...'）。 */
internal object Le {
    fun u16(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    fun u32(d: ByteArray, o: Int): Long =
        (u16(d, o) or (u16(d, o + 2) shl 16)).toLong() and 0xFFFFFFFFL

    fun u64(d: ByteArray, o: Int): Long {
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or (d[o + i].toLong() and 0xFF)
        return r
    }

    /** 40 位偏移：1 字节高 8 位在前 + u32 低 32 位 LE（对应 Python `u32(o+1) + data[o]<<32`）。 */
    fun u40(d: ByteArray, o: Int): Long = u32(d, o + 1) or ((d[o].toLong() and 0xFFL) shl 32)

    fun put32(d: ByteArray, o: Int, v: Int) {
        d[o] = (v and 0xFF).toByte()
        d[o + 1] = ((v shr 8) and 0xFF).toByte()
        d[o + 2] = ((v shr 16) and 0xFF).toByte()
        d[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    fun put64(d: ByteArray, o: Int, v: ULong) {
        var x = v
        for (i in 0 until 8) {
            d[o + i] = (x and 0xFFuL).toByte()
            x = x shr 8
        }
    }
}

/** manifest.ssra 清单与 chunks 目录下 .ssrc 分卷的只读视图（移植自 ssra_zhcn.py 的 Ssra 类）。 */
class SsraManifest(gameres: File) {
    data class Part(val idx: Int, val grp: Int, val k: Int, val payloadEnd: Long, val total: Long, val hash: ULong)
    data class FileRec(val k: Int, val name: String, val grp: Int, val meth: Int, val off: Long, val comp: Long, val dec: Long)
    data class Seg(val idx: Int, val k: Int, val start: Long, val end: Long)

    val build: Long
    val partCount: Int
    val fileCount: Int
    val parts: List<Part>
    val partNames: List<String>
    val files: List<FileRec>
    val groupSegs: Map<Int, List<Seg>>
    private val chunkDir: File = File(gameres, "chunks")

    init {
        val d = File(gameres, "manifest.ssra").readBytes()
        check(d.size > 0x40) { "manifest.ssra 过小（${d.size}B）" }
        check(
            d[0] == 'S'.code.toByte() && d[1] == 'S'.code.toByte() &&
                d[2] == 'R'.code.toByte() && d[3] == 'A'.code.toByte()
        ) { "manifest.ssra 魔数错误（非 SSRA）" }
        build = Le.u32(d, 8)
        partCount = Le.u32(d, 12).toInt()
        fileCount = Le.u32(d, 16).toInt()
        val namesOff = Le.u32(d, 24).toInt()
        val filesOff = Le.u64(d, 0x30)

        parts = (0 until partCount).map { k ->
            val o = 0x40 + k * 32
            Part(
                idx = Le.u32(d, o).toInt(),
                grp = Le.u16(d, o + 4),
                k = k,
                payloadEnd = Le.u64(d, o + 8),
                total = Le.u64(d, o + 16),
                hash = Le.u64(d, o + 24).toULong(),
            )
        }
        partNames = buildList {
            var q = namesOff
            repeat(partCount) {
                val e = d.indexOf(0, q)
                add(String(d, q, e - q, Charsets.UTF_8))
                q = e + 1
            }
        }
        files = (0 until fileCount).map { k ->
            val o = (filesOff + k * 40L).toInt()
            val noff = Le.u32(d, o + 28).toInt()
            val ns = namesOff + noff
            val ne = d.indexOf(0, ns)
            FileRec(
                k = k,
                name = String(d, ns, ne - ns, Charsets.UTF_8),
                grp = Le.u16(d, o + 34),
                meth = Le.u16(d, o + 32),
                off = Le.u64(d, o + 8),
                comp = Le.u32(d, o + 16),
                dec = Le.u32(d, o + 20),
            )
        }
        groupSegs = parts.groupBy { it.grp }.mapValues { (_, list) ->
            var acc = 0L
            list.sortedBy { it.idx }.map { p ->
                val s = acc
                acc += p.payloadEnd
                Seg(p.idx, p.k, s, acc)
            }
        }
    }

    fun record(name: String): FileRec =
        files.firstOrNull { it.name == name } ?: error("manifest 中不存在条目: $name")

    fun segmentContaining(grp: Int, off: Long): Seg =
        groupSegs.getValue(grp).first { off >= it.start && off < it.end }

    fun segmentOfPart(grp: Int, k: Int): Seg =
        groupSegs.getValue(grp).first { it.k == k }

    /** 读取组内 [off, off+n) 跨越的分卷数据；不足返回 null。 */
    fun readSpanning(grp: Int, off: Long, n: Long): ByteArray? {
        if (n > Int.MAX_VALUE) return null
        val out = ByteArray(n.toInt())
        for (seg in groupSegs.getValue(grp)) {
            if (off < seg.end && off + n > seg.start) {
                val dstOff = (maxOf(off, seg.start) - off).toInt()
                val srcStart = maxOf(off, seg.start) - seg.start
                val srcLen = (minOf(off + n, seg.end) - maxOf(off, seg.start)).toInt()
                RandomAccessFile(File(chunkDir, partNames[seg.k]), "r").use { raf ->
                    raf.seek(srcStart)
                    var read = 0
                    while (read < srcLen) {
                        val r = raf.read(out, dstOff + read, srcLen - read)
                        if (r < 0) return null
                        read += r
                    }
                }
            }
        }
        return out
    }

    /**
     * 提取条目：跨卷读取 → meth==1 时 zstd 解压。
     * decrypt=true 时对 .db/.dblang 追加内层 XOR 解密（就地）。
     */
    fun extract(record: FileRec, decrypt: Boolean): ByteArray {
        val blob = readSpanning(record.grp, record.off, record.comp)
            ?: error("分卷跨区读取失败: ${record.name}")
        val raw = if (record.meth == 1) {
            ZstdCodec.requireDecompress()(blob, record.dec.toInt())
        } else {
            blob
        }
        if (decrypt && (record.name.endsWith(".db") || record.name.endsWith(".dblang"))) {
            val phase = InnerXor.findPhase(raw.copyOfRange(0, minOf(64, raw.size)))
                ?: error("未找到内层 XOR 相位: ${record.name}")
            return InnerXor.transformInPlace(raw, phase)
        }
        return raw
    }
}

private fun ByteArray.indexOf(byte: Byte, from: Int): Int {
    for (i in from until size) if (this[i] == byte) return i
    return -1
}
