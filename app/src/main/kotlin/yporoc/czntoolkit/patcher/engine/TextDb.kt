package yporoc.czntoolkit.patcher.engine

/**
 * PLPcK v1 文本库解析/重建（移植自 ssra_zhcn.py 的 parse_textdb / rebuild_textdb）。
 * 结构：43B 头 + 哈希桶表(每桶 5B，40 位链头) + 条目链(15B 头 + key + value)。
 */
internal object TextDb {
    private val MAGIC = "PLPcK".toByteArray()

    class Entry(val key: ByteArray, val value: ByteArray, val flags: Int, val origOff: Long)

    class Parsed(
        val header: ByteArray,
        val hashCount: Int,
        val buckets: Array<IntArray>,
        val entries: List<Entry>,
    )

    fun parse(data: ByteArray): Parsed {
        check(data.size > 43) { "text.db 过小" }
        for (i in MAGIC.indices) check(data[i] == MAGIC[i]) { "text.db 魔数错误（非 PLPcK）" }
        val hashCount = Le.u32(data, 21).toInt()
        val entries = ArrayList<Entry>()
        val indexByOff = HashMap<Long, Int>()
        val bucketLists = ArrayList<List<Int>>(hashCount)
        for (b in 0 until hashCount) {
            val o = 43 + b * 5
            var chain = Le.u40(data, o)
            val lst = ArrayList<Int>()
            val seen = HashSet<Long>()
            while (chain != 0L && chain + 15 <= data.size && seen.add(chain)) {
                val idx = indexByOff.getOrPut(chain) {
                    val flags = data[(chain + 4).toInt()].toInt() and 0xFF
                    val keyLen = data[(chain + 5).toInt()].toInt() and 0xFF
                    val valSize = Le.u32(data, (chain + 6).toInt()).toInt()
                    val kStart = (chain + 15).toInt()
                    val vStart = kStart + keyLen
                    entries.add(
                        Entry(
                            key = data.copyOfRange(kStart, vStart),
                            value = data.copyOfRange(vStart, vStart + valSize),
                            flags = flags,
                            origOff = chain,
                        )
                    )
                    entries.size - 1
                }
                lst.add(idx)
                chain = Le.u40(data, (chain + 10).toInt())
            }
            bucketLists.add(lst)
        }
        check(bucketLists.sumOf { it.size } == entries.size) { "链覆盖不完整" }
        return Parsed(
            header = data.copyOfRange(0, 43),
            hashCount = hashCount,
            buckets = bucketLists.map { it.toIntArray() }.toTypedArray(),
            entries = entries,
        )
    }

    /**
     * 重建：按原始物理顺序写出条目（保证桶链 40 位偏移可回填），
     * 桶分配与键集不变，值按 newValues 替换。
     */
    fun rebuild(
        header: ByteArray,
        hashCount: Int,
        buckets: Array<IntArray>,
        entries: List<Entry>,
        newValues: Map<Int, ByteArray>,
    ): ByteArray {
        val headerLen = 43 + hashCount * 5
        val nxt = HashMap<Int, Int>()
        for (lst in buckets) {
            for (i in 0 until lst.size - 1) nxt[lst[i]] = lst[i + 1]
        }
        val phys = entries.indices.sortedBy { entries[it].origOff }
        val offs = IntArray(entries.size)
        // body 从 0 起算（相对坐标），总长精确预分配
        val body = ByteArray(
            entries.indices.sumOf { i -> 15 + entries[i].key.size + (newValues[i] ?: entries[i].value).size }
        )
        var cur = 0
        for (i in phys) {
            val e = entries[i]
            val v = newValues[i] ?: e.value
            val sz = 15 + e.key.size + v.size
            offs[i] = cur
            Le.put32(body, cur, sz)
            body[cur + 4] = e.flags.toByte()
            body[cur + 5] = e.key.size.toByte()
            Le.put32(body, cur + 6, v.size)
            body[cur + 10] = 0
            Le.put32(body, cur + 11, 0)
            e.key.copyInto(body, cur + 15)
            v.copyInto(body, cur + 15 + e.key.size)
            cur += sz
        }
        for (i in entries.indices) {
            // 指针值必须是绝对偏移（body 相对位置 + headerLen）
            val p: Long = nxt[i]?.let { (offs[it] + headerLen).toLong() } ?: 0L
            val o = offs[i] + 10
            body[o] = ((p shr 32) and 0xFFL).toByte()
            Le.put32(body, o + 1, (p and 0xFFFFFFFFL).toInt())
        }
        val out = ByteArray(headerLen + body.size)
        header.copyInto(out)
        body.copyInto(out, headerLen)
        for (b in buckets.indices) {
            val lst = buckets[b]
            if (lst.isNotEmpty()) {
                val p = (offs[lst[0]] + headerLen).toLong()
                out[43 + b * 5] = ((p shr 32) and 0xFFL).toByte()
                Le.put32(out, 43 + b * 5 + 1, (p and 0xFFFFFFFFL).toInt())
            }
        }
        return out
    }

    fun keyHex(key: ByteArray): String = key.joinToString("") { "%02x".format(it) }
}
