package io.github.wlroots.budsapp.protocol

import io.github.wlroots.budsapp.model.GestureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramesTest {

    @Test
    fun crcMatchesModbusCheckVector() {
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x4B37, Crc16.compute(data))
    }

    @Test
    fun encodesHeaderPayloadAndCrc() {
        val frame = Frames.encode(0xC007, 5, byteArrayOf(0x01, 0x02, 0x03))

        assertEquals(13, frame.size)
        assertEquals(0x55, frame[0].toInt() and 0xFF)
        assertEquals(0x60, frame[1].toInt() and 0xFF)
        assertEquals(0x01, frame[2].toInt() and 0xFF)
        assertEquals(0x07, frame[3].toInt() and 0xFF)
        assertEquals(0xC0, frame[4].toInt() and 0xFF)
        assertEquals(0x03, frame[5].toInt() and 0xFF)
        assertEquals(0x00, frame[6].toInt() and 0xFF)
        assertEquals(0x05, frame[7].toInt() and 0xFF)
        assertEquals(0x01, frame[8].toInt() and 0xFF)

        val crc = Crc16.compute(frame, 0, 11)
        assertEquals(crc and 0xFF, frame[11].toInt() and 0xFF)
        assertEquals((crc ushr 8) and 0xFF, frame[12].toInt() and 0xFF)
    }

    @Test
    fun decodesFrameSplitAcrossReads() {
        val encoded = Frames.encode(0x4007, 42, byteArrayOf(0x02, 0x55, 0x03, 0x80.toByte()))
        val decoder = Frames.Decoder()

        assertTrue(decoder.feed(encoded.copyOfRange(0, 3)).isEmpty())
        assertTrue(decoder.feed(encoded.copyOfRange(3, 7)).isEmpty())
        val frames = decoder.feed(encoded.copyOfRange(7, encoded.size))

        assertEquals(1, frames.size)
        val frame = frames.first()
        assertEquals(0x4007, frame.command)
        assertEquals(42, frame.seq)
        assertTrue(frame.crcValid)
        assertTrue(frame.payload.contentEquals(byteArrayOf(0x02, 0x55, 0x03, 0x80.toByte())))
    }

    @Test
    fun skipsGarbageBeforeFrame() {
        val encoded = Frames.encode(0xC01F, 7)
        val decoder = Frames.Decoder()
        val frames = decoder.feed(byteArrayOf(0x00, 0x11, 0x22) + encoded)

        assertEquals(1, frames.size)
        assertEquals(0xC01F, frames.first().command)
    }

    @Test
    fun flagsBadCrcButStillReturnsFrame() {
        val encoded = Frames.encode(0xC006, 9)
        encoded[encoded.size - 1] = (encoded.last().toInt() xor 0xFF).toByte()

        val frames = Frames.Decoder().feed(encoded)

        assertEquals(1, frames.size)
        assertFalse(frames.first().crcValid)
    }

    @Test
    fun decodesTwoFramesFromOneRead() {
        val first = Frames.encode(0xC007, 1, byteArrayOf(1))
        val second = Frames.encode(0x4007, 2, byteArrayOf(2, 3))
        val frames = Frames.Decoder().feed(first + second)

        assertEquals(2, frames.size)
        assertEquals(0xC007, frames[0].command)
        assertEquals(0x4007, frames[1].command)
        assertTrue(frames[1].payload.contentEquals(byteArrayOf(2, 3)))
    }
}

class ProtocolParsersTest {

    @Test
    fun parsesBatteryLevelsAndCharging() {
        val payload = byteArrayOf(
            0x03,
            0x02, 0x55,
            0x03, (0x80 or 40).toByte(),
            0x04, 0x40,
        )

        val battery = ProtocolParsers.battery(payload)

        assertEquals(85, battery.left?.level)
        assertEquals(false, battery.left?.charging)
        assertEquals(40, battery.right?.level)
        assertEquals(true, battery.right?.charging)
        assertEquals(64, battery.case?.level)
        assertNull(battery.single)
        assertEquals("L 85% · R 40% · Case 64%", battery.summary())
    }

    @Test
    fun parsesSingleBattery() {
        val battery = ProtocolParsers.battery(byteArrayOf(0x01, 0x06, 0x63))

        assertEquals(99, battery.single?.level)
        assertEquals("99%", battery.summary())
    }

    @Test
    fun parsesModelIdLittleEndian() {
        assertEquals("B155", ProtocolParsers.modelId(byteArrayOf(0x55, 0xB1.toByte())))
        assertNull(ProtocolParsers.modelId(byteArrayOf(0x01)))
    }

    @Test
    fun parsesAncAndEq() {
        assertEquals(5, ProtocolParsers.ancMode(byteArrayOf(0x01, 0x05, 0x00)))
        assertEquals(0x05, ProtocolParsers.eqPreset(byteArrayOf(0x05)))
        assertNull(ProtocolParsers.ancMode(byteArrayOf(0x01)))
    }

    @Test
    fun parsesToggles() {
        assertEquals(true, ProtocolParsers.latency(byteArrayOf(0x01, 0x00)))
        assertEquals(false, ProtocolParsers.latency(byteArrayOf(0x02, 0x00)))
        assertEquals(true, ProtocolParsers.inEar(byteArrayOf(0x01, 0x01, 0x01)))
        assertEquals(false, ProtocolParsers.inEar(byteArrayOf(0x01, 0x01, 0x00)))
        assertEquals(false, ProtocolParsers.spatial(byteArrayOf(0x00)))
        assertEquals(true, ProtocolParsers.multipoint(byteArrayOf(0x01)))
    }

    @Test
    fun parsesEnhancedBass() {
        val parsed = ProtocolParsers.enhancedBass(byteArrayOf(0x01, 0x04))
        assertEquals(true, parsed?.first)
        assertEquals(2, parsed?.second)
    }

    @Test
    fun parsesGestures() {
        val payload = byteArrayOf(0x02, 0x02, 0x01, 0x02, 0x09, 0x03, 0x01, 0x07, 0x16)

        val gestures = ProtocolParsers.gestures(payload)

        assertEquals(2, gestures.size)
        assertEquals(GestureState(2, 1, 2, 9), gestures[0])
        assertEquals(GestureState(3, 1, 7, 22), gestures[1])
    }

    @Test
    fun parsesMultipointDevicePage() {
        val payload = byteArrayOf(
            0x00, 0x00, 0x01,
            0x01,
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
            0x04,
            'T'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
        )

        val page = ProtocolParsers.multipointDevices(payload)

        assertEquals(1, page?.devices?.size)
        assertEquals("aabbccddeeff", page?.devices?.first()?.mac)
        assertEquals("Test", page?.devices?.first()?.name)
        assertEquals(true, page?.devices?.first()?.connected)
    }

    @Test
    fun parsesMultipointEvent() {
        val mac = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val event = ProtocolParsers.multipointEvent(byteArrayOf(0x01) + mac + byteArrayOf(0x01))

        assertEquals(true, event?.connected)
        assertEquals("112233445566", event?.mac)
        assertEquals(true, event?.needsRefresh)
    }

    @Test
    fun parsesFitTestAndSerial() {
        val fit = ProtocolParsers.fitTest(byteArrayOf(0x00, 0x01))
        assertEquals(0, fit?.left)
        assertEquals(1, fit?.right)

        val serial = ProtocolParsers.serial(byteArrayOf(0x01) + "x,3,other\nx,4,ABC123\n".toByteArray())
        assertEquals("ABC123", serial)
        assertNull(ProtocolParsers.serial(byteArrayOf(0x01) + "x,3,other\n".toByteArray()))
    }

    @Test
    fun hexHelpersRoundTrip() {
        assertEquals("aabbcc", ProtocolParsers.bytesToHex(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), 0, 3))
        assertTrue(
            ProtocolParsers.hexToBytes("AA:BB:CC").contentEquals(
                byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
            ),
        )
    }
}
