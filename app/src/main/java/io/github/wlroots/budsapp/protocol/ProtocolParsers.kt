package io.github.wlroots.budsapp.protocol

import io.github.wlroots.budsapp.model.BatteryReading
import io.github.wlroots.budsapp.model.BatteryState
import io.github.wlroots.budsapp.model.FitResult
import io.github.wlroots.budsapp.model.GestureState
import io.github.wlroots.budsapp.model.RemoteDevice

/** Pure payload parsers, kept free of Android APIs so they can be unit-tested. */
object ProtocolParsers {
    const val BATTERY_LEFT = 0x02
    const val BATTERY_RIGHT = 0x03
    const val BATTERY_CASE = 0x04
    const val BATTERY_SINGLE = 0x06

    fun battery(payload: ByteArray): BatteryState {
        if (payload.isEmpty()) return BatteryState()
        val count = payload[0].toInt() and 0xFF
        var left: BatteryReading? = null
        var right: BatteryReading? = null
        var case: BatteryReading? = null
        var single: BatteryReading? = null

        for (index in 0 until count) {
            val offset = 1 + index * 2
            if (offset + 1 >= payload.size) break
            val deviceId = payload[offset].toInt() and 0xFF
            val raw = payload[offset + 1].toInt() and 0xFF
            val reading = BatteryReading(
                level = raw and 0x7F,
                charging = (raw and 0x80) != 0,
            )
            when (deviceId) {
                BATTERY_LEFT -> left = reading
                BATTERY_RIGHT -> right = reading
                BATTERY_CASE -> case = reading
                BATTERY_SINGLE -> single = reading
            }
        }

        return BatteryState(left = left, right = right, case = case, single = single)
    }

    fun modelId(payload: ByteArray): String? {
        if (payload.size < 2) return null
        val value = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        return value.toString(16).uppercase()
    }

    fun ancMode(payload: ByteArray): Int? {
        if (payload.size < 2) return null
        return payload[1].toInt() and 0xFF
    }

    fun personalizedAnc(payload: ByteArray): Int? {
        if (payload.isEmpty()) return null
        return payload[0].toInt() and 0xFF
    }

    fun eqPreset(payload: ByteArray): Int? {
        if (payload.isEmpty()) return null
        return payload[0].toInt() and 0xFF
    }

    fun customEq(payload: ByteArray): List<Float>? = EqCodec.parseCustomEq(payload)

    fun enhancedBass(payload: ByteArray): Pair<Boolean, Int>? {
        if (payload.size < 2) return null
        val enabled = booleanFromByte(payload[0].toInt() and 0xFF) ?: return null
        val level = (payload[1].toInt() and 0xFF) / 2
        return enabled to level
    }

    fun latency(payload: ByteArray): Boolean? {
        if (payload.isEmpty()) return null
        return (payload[0].toInt() and 0xFF) == 0x01
    }

    fun inEar(payload: ByteArray): Boolean? {
        if (payload.size < 3) return null
        return booleanFromByte(payload[2].toInt() and 0xFF)
    }

    fun spatial(payload: ByteArray): Boolean? {
        if (payload.isEmpty()) return null
        return (payload[0].toInt() and 0xFF) == 0x01
    }

    fun gestures(payload: ByteArray): List<GestureState> {
        if (payload.isEmpty()) return emptyList()
        val count = payload[0].toInt() and 0xFF
        val result = ArrayList<GestureState>(count)
        var offset = 1
        repeat(count) {
            if (offset + 3 >= payload.size) return@repeat
            result.add(
                GestureState(
                    device = payload[offset].toInt() and 0xFF,
                    buttonId = payload[offset + 1].toInt() and 0xFF,
                    type = payload[offset + 2].toInt() and 0xFF,
                    action = payload[offset + 3].toInt() and 0xFF,
                ),
            )
            offset += 4
        }
        return result
    }

    fun multipoint(payload: ByteArray): Boolean? {
        if (payload.isEmpty()) return null
        return (payload[0].toInt() and 0xFF) == 0x01
    }

    data class DevicePage(val devices: List<RemoteDevice>, val addedNew: Boolean)

    fun multipointDevices(payload: ByteArray): DevicePage? {
        if (payload.size < 3) return null
        var count = payload[2].toInt() and 0xFF
        var index = 3
        var addedNew = false
        val devices = ArrayList<RemoteDevice>()

        while (index + 8 <= payload.size && count > 0) {
            val flags = payload[index].toInt() and 0xFF
            val connected = (flags and 0x0F) == 0x01
            val mac = bytesToHex(payload, index + 1, 6)
            val lengthByte = payload[index + 7].toInt() and 0xFF
            val clipped = (lengthByte and 0x80) != 0
            val nameLength = if (clipped) 0x1F else lengthByte and 0x7F
            index += 8
            if (index + nameLength > payload.size) break

            var name = String(payload, index, nameLength, Charsets.UTF_8)
                .replace("\uFFFD", "")
            if (clipped) name += "..."
            index += nameLength
            count--

            val device = RemoteDevice(mac = mac, name = name, connected = connected)
            val existing = devices.indexOfFirst { it.mac == mac }
            if (existing >= 0) {
                devices[existing] = device
            } else {
                devices.add(device)
                addedNew = true
            }
        }

        return DevicePage(devices, addedNew)
    }

    data class DeviceEvent(val connected: Boolean, val mac: String, val needsRefresh: Boolean)

    fun multipointEvent(payload: ByteArray): DeviceEvent? {
        if (payload.size < 7) return null
        return DeviceEvent(
            connected = (payload[0].toInt() and 0xFF) == 0x01,
            mac = bytesToHex(payload, 1, 6),
            needsRefresh = payload.size > 7 && (payload[7].toInt() and 0xFF) == 0x01,
        )
    }

    fun fitTest(payload: ByteArray): FitResult? {
        if (payload.size < 2) return null
        return FitResult(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF)
    }

    fun firmware(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        return String(payload, Charsets.UTF_8).trim('\u0000', ' ')
    }

    fun serial(payload: ByteArray): String? {
        if (payload.size < 2) return null
        val data = String(payload, 1, payload.size - 1, Charsets.UTF_8)
        for (line in data.trim().split('\n')) {
            val parts = line.split(',')
            if (parts.size != 3) continue
            val type = parts[1].trim().toIntOrNull() ?: continue
            val value = parts[2].trim()
            if (type == 4 && value.isNotEmpty()) return value
        }
        return null
    }

    fun booleanFromByte(value: Int): Boolean? = when (value) {
        0 -> false
        1 -> true
        else -> null
    }

    fun bytesToHex(bytes: ByteArray, offset: Int, length: Int): String {
        val builder = StringBuilder(length * 2)
        for (i in offset until offset + length) {
            builder.append("%02x".format(bytes[i].toInt() and 0xFF))
        }
        return builder.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(":", "").replace(" ", "")
        if (clean.length % 2 != 0) return ByteArray(0)
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
