package io.github.wlroots.budsapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqCodecTest {

    @Test
    fun buildsReferenceSizedPayload() {
        val payload = EqCodec.buildCustomEqPayload(0f, 0f, 0f)
        assertEquals(53, payload.size)
    }

    @Test
    fun floatRoundTrip() {
        val values = floatArrayOf(-6f, -2.5f, -0.1f, 0f, 0.1f, 1.5f, 3.25f, 6f)
        for (value in values) {
            val encoded = EqCodec.encodeFloat(value)
            val decoded = EqCodec.decodeFloat(encoded, 0)
            assertEquals("value $value", value, decoded, 0.0001f)
        }
    }

    @Test
    fun customEqRoundTrip() {
        val payload = EqCodec.buildCustomEqPayload(bass = -3f, mid = 1.5f, treble = 4.25f)
        val parsed = EqCodec.parseCustomEq(payload)

        assertEquals(listOf(-3f, 1.5f, 4.25f), parsed)
    }

    @Test
    fun customEqIsClampedToProtocolRange() {
        val payload = EqCodec.buildCustomEqPayload(bass = -20f, mid = 0f, treble = 20f)
        val parsed = EqCodec.parseCustomEq(payload)

        assertEquals(-6f, parsed?.get(0))
        assertEquals(6f, parsed?.get(2))
    }

    @Test
    fun rejectsShortPayload() {
        assertNull(EqCodec.parseCustomEq(ByteArray(10)))
        assertTrue(EqCodec.parseCustomEq(ByteArray(45)) != null)
    }

    @Test
    fun totalFieldIsZeroForFlatEq() {
        val payload = EqCodec.buildCustomEqPayload(0f, 0f, 0f)
        assertEquals(0x00, payload[1].toInt() and 0xFF)
        assertEquals(0x80, payload[4].toInt() and 0xFF)
    }
}
