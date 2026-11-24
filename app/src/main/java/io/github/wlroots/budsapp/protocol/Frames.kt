package io.github.wlroots.budsapp.protocol

/**
 * The Nothing/CMF frame format:
 *
 *   55 | 60 01 | command (LE16) | payload length (LE16) | seq | payload | crc16 (LE16)
 *
 * The third header byte doubles as the low byte of the control field (0x0160);
 * bit 0x20 of the control field indicates that a CRC is present.
 */
object Frames {
    const val SOF = 0x55
    const val CTRL_LOW = 0x60
    const val CTRL_HIGH = 0x01
    const val HEADER_SIZE = 8
    const val CRC_SIZE = 2
    const val MASK_CRC = 0x0020

    data class Frame(
        val command: Int,
        val seq: Int,
        val payload: ByteArray,
        val crcValid: Boolean,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return command == other.command && seq == other.seq && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = command
            result = 31 * result + seq
            result = 31 * result + payload.contentHashCode()
            return result
        }

        fun payloadHex(): String = payload.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun encode(command: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val payloadLength = payload.size
        val out = ByteArray(HEADER_SIZE + payloadLength + CRC_SIZE)
        out[0] = SOF.toByte()
        out[1] = CTRL_LOW.toByte()
        out[2] = CTRL_HIGH.toByte()
        out[3] = (command and 0xFF).toByte()
        out[4] = ((command ushr 8) and 0xFF).toByte()
        out[5] = (payloadLength and 0xFF).toByte()
        out[6] = ((payloadLength ushr 8) and 0xFF).toByte()
        out[7] = (seq and 0xFF).toByte()
        payload.copyInto(out, HEADER_SIZE)
        val crc = Crc16.compute(out, 0, HEADER_SIZE + payloadLength)
        out[HEADER_SIZE + payloadLength] = (crc and 0xFF).toByte()
        out[HEADER_SIZE + payloadLength + 1] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    /**
     * Incremental stream decoder. Feed it whatever a Bluetooth read() returns; it returns
     * every complete frame found and keeps the partial tail for the next call.
     */
    class Decoder(private val maxPayload: Int = 8192) {
        private var buffer = ByteArray(256)
        private var size = 0

        fun feed(chunk: ByteArray): List<Frame> {
            append(chunk)
            val frames = ArrayList<Frame>()

            var offset = 0
            while (true) {
                var start = offset
                while (start < size && buffer[start] != SOF.toByte()) start++
                if (start >= size) {
                    offset = size
                    break
                }
                if (size - start < HEADER_SIZE) {
                    offset = start
                    break
                }

                val control = (buffer[start + 1].toInt() and 0xFF) or
                    ((buffer[start + 2].toInt() and 0xFF) shl 8)
                val hasCrc = (control and MASK_CRC) != 0
                val payloadLength = (buffer[start + 5].toInt() and 0xFF) or
                    ((buffer[start + 6].toInt() and 0xFF) shl 8)

                if (payloadLength > maxPayload) {
                    // Not a plausible header; skip this SOF byte and keep scanning.
                    offset = start + 1
                    continue
                }

                val total = HEADER_SIZE + payloadLength + if (hasCrc) CRC_SIZE else 0
                if (start + total > size) {
                    offset = start
                    break
                }

                val command = (buffer[start + 3].toInt() and 0xFF) or
                    ((buffer[start + 4].toInt() and 0xFF) shl 8)
                val seq = buffer[start + 7].toInt() and 0xFF
                val payload = buffer.copyOfRange(start + HEADER_SIZE, start + HEADER_SIZE + payloadLength)

                var crcValid = true
                if (hasCrc) {
                    val expected = (buffer[start + HEADER_SIZE + payloadLength].toInt() and 0xFF) or
                        ((buffer[start + HEADER_SIZE + payloadLength + 1].toInt() and 0xFF) shl 8)
                    crcValid = Crc16.compute(buffer, start, HEADER_SIZE + payloadLength) == expected
                }

                frames.add(Frame(command, seq, payload, crcValid))
                offset = start + total
            }

            compact(offset)
            return frames
        }

        fun reset() {
            size = 0
        }

        private fun append(chunk: ByteArray) {
            if (size + chunk.size > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, size + chunk.size))
            }
            chunk.copyInto(buffer, size)
            size += chunk.size
        }

        private fun compact(consumed: Int) {
            if (consumed == 0) {
                if (size > 4096) size = 0 // garbage guard, mirrors the BudsLink overflow reset
                return
            }
            val remaining = size - consumed
            if (remaining > 0) buffer.copyInto(buffer, 0, consumed, size)
            size = remaining
        }
    }
}
