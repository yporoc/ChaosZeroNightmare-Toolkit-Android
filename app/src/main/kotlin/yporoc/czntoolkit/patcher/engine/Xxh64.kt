package yporoc.czntoolkit.patcher.engine

/**
 * XXH64（seed=0）纯 Kotlin 实现，行为与 libxxhash / python-xxhash 完全一致。
 * 所有运算用 ULong 天然做 2^64 回绕。
 */
object Xxh64 {
    private val P1 = 0x9E3779B185EBCA87uL
    private val P2 = 0xC2B2AE3D27D4EB4FuL
    private val P3 = 0x165667B19E3779F9uL
    private val P4 = 0x85EBCA77C2B2AE63uL
    private val P5 = 0x27D4EB2F165667C5uL

    fun hash(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ULong {
        var idx = offset
        val end = offset + length
        var h: ULong
        if (length >= 32) {
            var v1 = P1 + P2
            var v2 = P2
            var v3 = 0uL
            var v4 = 0uL - P1
            val limit = end - 32
            while (idx <= limit) {
                v1 = round(v1, readLong(data, idx))
                v2 = round(v2, readLong(data, idx + 8))
                v3 = round(v3, readLong(data, idx + 16))
                v4 = round(v4, readLong(data, idx + 24))
                idx += 32
            }
            h = (v1 rotl 1) + (v2 rotl 7) + (v3 rotl 12) + (v4 rotl 18)
            h = mergeRound(h, v1)
            h = mergeRound(h, v2)
            h = mergeRound(h, v3)
            h = mergeRound(h, v4)
        } else {
            h = P5
        }
        h += length.toULong()
        while (idx + 8 <= end) {
            h = h xor round(0uL, readLong(data, idx))
            h = (h rotl 27) * P1 + P4
            idx += 8
        }
        if (idx + 4 <= end) {
            h = h xor (readInt(data, idx).toULong() * P1)
            h = (h rotl 23) * P2 + P3
            idx += 4
        }
        while (idx < end) {
            h = h xor ((data[idx].toLong() and 0xFF).toULong() * P5)
            h = (h rotl 11) * P1
            idx++
        }
        return finalize(h)
    }

    private fun round(acc: ULong, input: ULong): ULong {
        var a = acc + input * P2
        a = a rotl 31
        return a * P1
    }

    private fun mergeRound(h: ULong, v: ULong): ULong {
        var r = h xor round(0uL, v)
        r = r * P1 + P4
        return r
    }

    private fun finalize(h: ULong): ULong {
        var r = h xor (h shr 33)
        r *= P2
        r = r xor (r shr 29)
        r *= P3
        r = r xor (r shr 32)
        return r
    }

    private fun readLong(d: ByteArray, o: Int): ULong {
        var r = 0uL
        for (i in 7 downTo 0) r = (r shl 8) or (d[o + i].toLong() and 0xFFL).toULong()
        return r
    }

    private fun readInt(d: ByteArray, o: Int): Int {
        var r = 0
        for (i in 3 downTo 0) r = (r shl 8) or (d[o + i].toInt() and 0xFF)
        return r
    }

    private infix fun ULong.rotl(n: Int): ULong = (this shl n) or (this shr (64 - n))
}
