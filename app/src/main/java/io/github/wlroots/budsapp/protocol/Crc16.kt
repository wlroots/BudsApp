package io.github.wlroots.budsapp.protocol

/**
 * CRC-16/ANSI (a.k.a. CRC-16/MODBUS) as used by the Nothing/CMF SPP protocol.
 */
object Crc16 {
    private val table = IntArray(256) { index ->
        var crc = index
        repeat(8) {
            crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
        }
        crc
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = (crc ushr 8) xor table[(crc xor data[i].toInt()) and 0xFF]
        }
        return crc and 0xFFFF
    }
}
