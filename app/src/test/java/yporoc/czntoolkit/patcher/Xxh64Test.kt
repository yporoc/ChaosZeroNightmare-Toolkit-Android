package yporoc.czntoolkit.patcher

import yporoc.czntoolkit.patcher.engine.Xxh64
import org.junit.Assert.assertEquals
import org.junit.Test

class Xxh64Test {
    private val range200 = ByteArray(200) { it.toByte() }

    @Test
    fun `标准测试向量与 python-xxhash 一致`() {
        assertEquals(0xEF46DB3751D8E999uL, Xxh64.hash(byteArrayOf()))
        assertEquals(0xD24EC4F1A98C6E5BuL, Xxh64.hash("a".toByteArray()))
        assertEquals(0x44BC2CF5AD770999uL, Xxh64.hash("abc".toByteArray()))
        assertEquals(0x50DC1079B99E879CuL, Xxh64.hash(range200))
        assertEquals(0x60DD0D01083B99F0uL, Xxh64.hash(ByteArray(31) { 'x'.code.toByte() }))
    }

    @Test
    fun `分段哈希与切片哈希一致`() {
        val data = ByteArray(1000) { (it * 31).toByte() }
        assertEquals(
            Xxh64.hash(data.copyOfRange(400, 900)),
            Xxh64.hash(data, 400, 500),
        )
    }
}
