package yporoc.czntoolkit.patcher.engine

/**
 * text.db 内层加密：256 字节 XOR 密钥，相位（起始偏移）逐文件固定。
 * XOR 对称，加密与解密同一函数；密钥与相位搜索逻辑移植自 PC 端 unpack_data.py / ssra_zhcn.py。
 */
object InnerXor {
    val KEY = byteArrayOf(
        0x91.toByte(), 0xae.toByte(), 0x4e.toByte(), 0xd4.toByte(), 0x64.toByte(), 0x4f.toByte(), 0x58.toByte(), 0x51.toByte(),
        0x62.toByte(), 0xec.toByte(), 0x1b.toByte(), 0xd5.toByte(), 0xef.toByte(), 0x24.toByte(), 0xad.toByte(), 0xdb.toByte(),
        0xaf.toByte(), 0x83.toByte(), 0x82.toByte(), 0x42.toByte(), 0xae.toByte(), 0xf5.toByte(), 0x1e.toByte(), 0x97.toByte(),
        0x80.toByte(), 0x4b.toByte(), 0x13.toByte(), 0x4f.toByte(), 0xfd.toByte(), 0x8c.toByte(), 0xe5.toByte(), 0xbb.toByte(),
        0x4f.toByte(), 0x6e.toByte(), 0x3e.toByte(), 0x64.toByte(), 0x51.toByte(), 0x14.toByte(), 0x7c.toByte(), 0xdf.toByte(),
        0x56.toByte(), 0xc3.toByte(), 0x18.toByte(), 0xe5.toByte(), 0xe9.toByte(), 0x64.toByte(), 0xc9.toByte(), 0x99.toByte(),
        0xc0.toByte(), 0xd9.toByte(), 0x5c.toByte(), 0xc8.toByte(), 0x60.toByte(), 0x82.toByte(), 0x2e.toByte(), 0x6b.toByte(),
        0x41.toByte(), 0x8b.toByte(), 0xe4.toByte(), 0x65.toByte(), 0xd7.toByte(), 0x9a.toByte(), 0x03.toByte(), 0x6d.toByte(),
        0xbf.toByte(), 0x67.toByte(), 0xab.toByte(), 0x3d.toByte(), 0xa7.toByte(), 0x2a.toByte(), 0xb1.toByte(), 0x02.toByte(),
        0x3a.toByte(), 0x45.toByte(), 0x61.toByte(), 0xf4.toByte(), 0x44.toByte(), 0xe5.toByte(), 0xce.toByte(), 0x85.toByte(),
        0x8d.toByte(), 0x23.toByte(), 0xea.toByte(), 0x10.toByte(), 0xfe.toByte(), 0xb4.toByte(), 0x89.toByte(), 0x91.toByte(),
        0x51.toByte(), 0xad.toByte(), 0x7e.toByte(), 0x43.toByte(), 0xff.toByte(), 0x3e.toByte(), 0x24.toByte(), 0x19.toByte(),
        0xa9.toByte(), 0x7b.toByte(), 0x4d.toByte(), 0xd3.toByte(), 0xaf.toByte(), 0x4e.toByte(), 0xf5.toByte(), 0xc8.toByte(),
        0x29.toByte(), 0xe5.toByte(), 0xaf.toByte(), 0x4a.toByte(), 0xce.toByte(), 0x94.toByte(), 0x36.toByte(), 0xf6.toByte(),
        0xb6.toByte(), 0xb6.toByte(), 0x38.toByte(), 0x2e.toByte(), 0x9d.toByte(), 0xfd.toByte(), 0x26.toByte(), 0x64.toByte(),
        0x20.toByte(), 0x99.toByte(), 0x01.toByte(), 0x1a.toByte(), 0x48.toByte(), 0x99.toByte(), 0x08.toByte(), 0x9c.toByte(),
        0x9d.toByte(), 0x4b.toByte(), 0x9f.toByte(), 0x80.toByte(), 0xbb.toByte(), 0xb0.toByte(), 0x0a.toByte(), 0x4c.toByte(),
        0xc7.toByte(), 0x32.toByte(), 0x55.toByte(), 0xce.toByte(), 0x1f.toByte(), 0x78.toByte(), 0x64.toByte(), 0x6e.toByte(),
        0x91.toByte(), 0xc9.toByte(), 0xc1.toByte(), 0x23.toByte(), 0x13.toByte(), 0xf5.toByte(), 0xd8.toByte(), 0x40.toByte(),
        0xdc.toByte(), 0x51.toByte(), 0x45.toByte(), 0x70.toByte(), 0x10.toByte(), 0xd3.toByte(), 0x7d.toByte(), 0x19.toByte(),
        0x61.toByte(), 0x5b.toByte(), 0xb6.toByte(), 0x98.toByte(), 0x88.toByte(), 0xb4.toByte(), 0x2b.toByte(), 0x19.toByte(),
        0xe7.toByte(), 0x49.toByte(), 0xf9.toByte(), 0x93.toByte(), 0xc0.toByte(), 0x03.toByte(), 0x37.toByte(), 0xe9.toByte(),
        0x33.toByte(), 0x2f.toByte(), 0x89.toByte(), 0xb3.toByte(), 0x20.toByte(), 0xc1.toByte(), 0x73.toByte(), 0xa5.toByte(),
        0x65.toByte(), 0x38.toByte(), 0x48.toByte(), 0x78.toByte(), 0x87.toByte(), 0x98.toByte(), 0xa7.toByte(), 0x71.toByte(),
        0x73.toByte(), 0x9e.toByte(), 0x72.toByte(), 0xdb.toByte(), 0xc8.toByte(), 0x4c.toByte(), 0x79.toByte(), 0x46.toByte(),
        0x59.toByte(), 0x71.toByte(), 0x49.toByte(), 0xbd.toByte(), 0xda.toByte(), 0xe4.toByte(), 0xe3.toByte(), 0xbd.toByte(),
        0x1a.toByte(), 0x17.toByte(), 0x85.toByte(), 0x6c.toByte(), 0x85.toByte(), 0xa5.toByte(), 0x55.toByte(), 0xcf.toByte(),
        0xa2.toByte(), 0x4f.toByte(), 0x63.toByte(), 0x52.toByte(), 0xd0.toByte(), 0x05.toByte(), 0x93.toByte(), 0x3b.toByte(),
        0x50.toByte(), 0x04.toByte(), 0x2b.toByte(), 0xe0.toByte(), 0xba.toByte(), 0x4c.toByte(), 0x70.toByte(), 0x8d.toByte(),
        0xe8.toByte(), 0xeb.toByte(), 0xb5.toByte(), 0x20.toByte(), 0x59.toByte(), 0xb2.toByte(), 0x05.toByte(), 0x9c.toByte(),
        0x9b.toByte(), 0xfe.toByte(), 0x90.toByte(), 0xd8.toByte(), 0x92.toByte(), 0x3d.toByte(), 0xf7.toByte(), 0x4b.toByte(),
        0x43.toByte(), 0x91.toByte(), 0x1b.toByte(), 0xbc.toByte(), 0x00.toByte(), 0xbb.toByte(), 0x6b.toByte(), 0xfa.toByte(),
    )

    private val MAGIC = "PLPcK".toByteArray()

    /** 用头 64 字节爆破 256 个相位，找到使头部解出 PLPcK 魔数的那个。 */
    fun findPhase(head: ByteArray): Int? {
        for (boff in 0 until 256) {
            var ok = true
            for (i in 0 until 5) {
                if ((head[i].toInt() xor KEY[(i + boff) % 256].toInt()) != MAGIC[i].toInt()) {
                    ok = false
                    break
                }
            }
            if (ok) return boff
        }
        return null
    }

    /** 就地 XOR 变换（对称），ks[i] = KEY[(phase + i) % 256]。 */
    fun transformInPlace(data: ByteArray, phase: Int): ByteArray {
        var k = phase % 256
        for (i in data.indices) {
            data[i] = (data[i].toInt() xor KEY[k].toInt()).toByte()
            if (++k == 256) k = 0
        }
        return data
    }
}
