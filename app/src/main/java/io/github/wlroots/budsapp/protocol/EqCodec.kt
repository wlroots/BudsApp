package io.github.wlroots.budsapp.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Custom EQ payload codec. The earbuds send three 4-byte "floats" with a peculiar
 * encoding (see BudsLink _decodeEqFloat/_encodeEqFloat):
 *
 *   bytes are reversed, and for non-zero positive values the top bit of the last
 *   transmitted byte is used as a sign/valid marker.
 *
 * Bands are transmitted as mid, treble, bass while the UI exposes bass, mid, treble.
 */
object EqCodec {
    fun decodeFloat(bytes: ByteArray, offset: Int): Float {
        if (offset + 4 > bytes.size) return 0f
        var b0 = bytes[offset + 3].toInt() and 0xFF
        val b1 = bytes[offset + 2].toInt() and 0xFF
        val b2 = bytes[offset + 1].toInt() and 0xFF
        val b3 = bytes[offset].toInt() and 0xFF

        val negative = b0 == 0x00 && b1 == 0x00 && b2 == 0x00 && (b3 and 0x80) != 0
        if (negative) {
            b0 = b0 and 0x7F
            val raw = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            return -Float.fromBits(raw)
        }

        val raw = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        return Float.fromBits(raw)
    }

    fun encodeFloat(value: Float, total: Boolean = false): ByteArray {
        if (total && value >= 0f) return byteArrayOf(0x00, 0x00, 0x00, 0x80.toByte())

        val raw = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(value).array()
        val bytes = raw.copyOf()
        if (value != 0f && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte()) {
            bytes[3] = (bytes[3].toInt() or 0x80).toByte()
        }
        return byteArrayOf(bytes[3], bytes[2], bytes[1], bytes[0])
    }

    /** Returns [bass, mid, treble] clamped to -6..+6 dB. */
    fun parseCustomEq(payload: ByteArray): List<Float>? {
        if (payload.size < 45) return null
        val levels = ArrayList<Float>(3)
        for (band in 0 until 3) {
            val offset = 6 + band * 13
            if (offset + 4 > payload.size) return null
            levels.add(decodeFloat(payload, offset))
        }
        val clamp = { value: Float -> value.coerceIn(-6f, 6f) }
        return listOf(clamp(levels[2]), clamp(levels[0]), clamp(levels[1]))
    }

    private val BASE_PAYLOAD = hexToBytes(
        "0300000000010000000000007544" +
            "c3f5283f020000000000c05a4500" +
            "00803f000000000000000c43cdcc" +
            "4c3f000000000000000000",
    )

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    /** Builds the 55-byte CUSTOM_EQ_SET payload from [bass, mid, treble] in dB (-6..+6). */
    fun buildCustomEqPayload(bass: Float, mid: Float, treble: Float): ByteArray {
        val payload = BASE_PAYLOAD.copyOf()
        val values = floatArrayOf(mid, treble, bass)
        var highest = 0f
        for (value in values) highest = maxOf(highest, kotlin.math.abs(value))

        encodeFloat(-highest, total = true).copyInto(payload, 1)
        values.forEachIndexed { index, value ->
            encodeFloat(value).copyInto(payload, 6 + index * 13)
        }
        return payload
    }
}
