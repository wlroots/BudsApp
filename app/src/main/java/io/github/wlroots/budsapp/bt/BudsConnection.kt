package io.github.wlroots.budsapp.bt

import android.content.Context
import io.github.wlroots.budsapp.model.BudsState
import io.github.wlroots.budsapp.model.ConnectionStatus
import io.github.wlroots.budsapp.model.DeviceCatalog
import io.github.wlroots.budsapp.model.DeviceConfig
import io.github.wlroots.budsapp.model.GestureState
import io.github.wlroots.budsapp.model.RemoteDevice
import io.github.wlroots.budsapp.model.RingSide
import io.github.wlroots.budsapp.protocol.EqCodec
import io.github.wlroots.budsapp.protocol.Frames
import io.github.wlroots.budsapp.protocol.PayloadType
import io.github.wlroots.budsapp.protocol.ProtocolParsers
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One live session with a pair of Nothing/CMF earbuds.
 *
 * Requests are serialized: one packet in flight at a time with a short response
 * timeout, mirroring the reference implementation. Notifications are processed
 * as they arrive and never block the queue.
 */
class BudsConnection(
    private val context: Context,
    private val deviceName: String,
    private val mac: String,
    private val transport: BudsTransport,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: BudsState)
        fun onLog(line: String)
        fun onClosed(unexpected: Boolean, reason: String)
    }

    private data class Outgoing(val command: Int, val payload: ByteArray, val note: String)

    private val stateLock = Any()
    private val queueLock = Any()
    private val queue = ArrayDeque<Outgoing>()
    private val decoder = Frames.Decoder()
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "buds-write").apply { isDaemon = true }
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "buds-timer").apply { isDaemon = true }
    }

    @Volatile
    private var state = BudsState(
        status = ConnectionStatus.CONNECTING,
        statusMessage = "Connecting…",
        deviceName = deviceName,
        mac = mac,
    )

    @Volatile
    private var running = true

    @Volatile
    private var closed = false

    @Volatile
    private var modelResolved = false

    private var modelFallback: ScheduledFuture<*>? = null
    private var pendingTimeout: ScheduledFuture<*>? = null
    private var pendingSeq = 0
    private var pendingCommand = 0
    private var seq = 0
    private var connectThread: Thread? = null
    private var readerThread: Thread? = null
    private var multipointRequests = 0
    private val multipointDevices = LinkedHashMap<String, RemoteDevice>()

    fun start() {
        publish(state)
        connectThread = Thread({ connectInternal() }, "buds-connect").apply {
            isDaemon = true
            start()
        }
    }

    /* --------------------------------------------------------------- lifecycle */

    private fun connectInternal() {
        try {
            log("Opening ${transport.label}")
            transport.open()
            update { it.copy(status = ConnectionStatus.CONNECTED, statusMessage = "Connected") }
            readerThread = Thread({ readLoop() }, "buds-reader").apply {
                isDaemon = true
                start()
            }

            send(PayloadType.PROTOCOL_GET, note = "Request protocol")
            send(PayloadType.PROTOCOL_SET, note = "Set protocol")
            send(PayloadType.DEVICE_MODEL_GET, note = "Request model id")

            modelFallback = scheduler.schedule(
                {
                    if (!modelResolved) resolveByDeviceName()
                },
                2,
                TimeUnit.SECONDS,
            )
        } catch (e: Exception) {
            fail("Connection failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(2048)
        try {
            while (running) {
                val read = transport.read(buffer)
                if (read < 0) {
                    if (running) fail("Earbuds closed the connection")
                    return
                }
                if (read > 0) {
                    decoder.feed(buffer.copyOf(read)).forEach { handleFrame(it) }
                }
            }
        } catch (e: Exception) {
            if (running) fail("Read failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** User-initiated close. */
    fun close() {
        if (closed) return
        closed = true
        running = false
        modelFallback?.cancel(false)
        pendingTimeout?.cancel(false)
        runCatching { transport.close() }
        update { it.copy(status = ConnectionStatus.DISCONNECTED, statusMessage = "Disconnected") }
        listener.onClosed(false, "Disconnected")
        scheduler.shutdownNow()
        writeExecutor.shutdownNow()
        readerThread?.interrupt()
    }

    private fun fail(reason: String) {
        if (closed) return
        closed = true
        running = false
        log(reason)
        modelFallback?.cancel(false)
        pendingTimeout?.cancel(false)
        runCatching { transport.close() }
        update { it.copy(status = ConnectionStatus.ERROR, statusMessage = reason) }
        listener.onClosed(true, reason)
        scheduler.shutdownNow()
        writeExecutor.shutdownNow()
        readerThread?.interrupt()
    }

    /* --------------------------------------------------------------- tx queue */

    private fun send(command: Int, payload: ByteArray = EMPTY, note: String = "") {
        if (!running) return
        synchronized(queueLock) {
            queue.addLast(Outgoing(command, payload, note))
            pumpLocked()
        }
    }

    private fun pumpLocked() {
        if (!running || pendingSeq != 0) return
        val item = queue.pollFirst() ?: return

        seq = if (seq >= 250) 1 else seq + 1
        val thisSeq = seq
        pendingSeq = thisSeq
        pendingCommand = item.command

        val bytes = Frames.encode(item.command, thisSeq, item.payload)
        if (item.note.isNotEmpty()) {
            log("→ ${item.note} [0x%04X seq %d]".format(item.command, thisSeq))
        }
        log("TX ${hex(bytes)}")

        try {
            writeExecutor.execute {
                try {
                    transport.write(bytes)
                } catch (e: Exception) {
                    fail("Write failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return
        }

        pendingTimeout?.cancel(false)
        pendingTimeout = try {
            scheduler.schedule(
                { onRequestTimeout(thisSeq, item.command) },
                REQUEST_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            null
        }
    }

    private fun onRequestTimeout(timedOutSeq: Int, command: Int) {
        synchronized(queueLock) {
            if (pendingSeq == timedOutSeq) {
                log("… no response to 0x%04X (seq %d)".format(command, timedOutSeq))
                pendingSeq = 0
                pendingTimeout = null
                pumpLocked()
            }
        }
    }

    private fun completePending(frameSeq: Int) {
        synchronized(queueLock) {
            if (pendingSeq != 0 && frameSeq == pendingSeq) {
                pendingTimeout?.cancel(false)
                pendingTimeout = null
                pendingSeq = 0
                pumpLocked()
            }
        }
    }

    /* --------------------------------------------------------------- rx frames */

    private fun handleFrame(frame: Frames.Frame) {
        log("RX 0x%04X seq %d %s%s".format(
            frame.command,
            frame.seq,
            frame.payloadHex(),
            if (frame.crcValid) "" else " (BAD CRC)",
        ))
        completePending(frame.seq)

        val payload = frame.payload
        when (frame.command) {
            PayloadType.PROTOCOL_RET,
            PayloadType.DEVICE_MODEL_RET
            -> {
                if (!modelResolved) {
                    val id = ProtocolParsers.modelId(payload)
                    if (id != null) {
                        val config = DeviceCatalog.byModelId(context, id)
                        if (config != null) {
                            resolveModel(config, "model id $id")
                        } else {
                            log("Unknown model id: $id")
                        }
                    }
                }
            }

            PayloadType.FIRMWARE_NTFY -> {
                val firmware = ProtocolParsers.firmware(payload)
                if (firmware.isNotEmpty()) update { it.copy(firmware = firmware) }
            }

            PayloadType.SERIAL_RET -> {
                val serial = ProtocolParsers.serial(payload)
                if (serial != null) update { it.copy(serial = serial) }
            }

            PayloadType.BATTERY_PRIMARY_NTFY,
            PayloadType.BATTERY_SECONDARY_NTFY
            -> update { it.copy(battery = ProtocolParsers.battery(payload)) }

            PayloadType.ANC_PRIMARY_NTFY,
            PayloadType.ANC_SECONDARY_NTFY
            -> {
                val mode = ProtocolParsers.ancMode(payload) ?: return
                val config = state.config
                if (config != null && mode in (config.noise?.allModeBytes() ?: emptyList())) {
                    update { it.copy(ancRaw = mode) }
                }
            }

            PayloadType.PERSONALIZED_ANC_NTFY -> {
                val value = ProtocolParsers.personalizedAnc(payload) ?: return
                val enabled = ProtocolParsers.booleanFromByte(value) ?: return
                update { it.copy(personalizedAnc = enabled) }
            }

            PayloadType.EQ_NTFY,
            PayloadType.LISTENING_MODE_NTFY
            -> {
                val preset = ProtocolParsers.eqPreset(payload) ?: return
                val config = state.config ?: return
                if (preset in config.eqPresets.values) {
                    update { it.copy(eqPresetRaw = preset) }
                    if (preset == config.eqPresets["custom"]) send(PayloadType.CUSTOM_EQ_GET)
                }
            }

            PayloadType.CUSTOM_EQ_NTFY -> {
                val eq = ProtocolParsers.customEq(payload) ?: return
                update { it.copy(customEq = eq) }
            }

            PayloadType.ENHANCED_BASS_RET,
            PayloadType.ENHANCED_BASS_NTFY
            -> {
                val (enabled, level) = ProtocolParsers.enhancedBass(payload) ?: return
                update { it.copy(bassEnabled = enabled, bassLevel = level) }
            }

            PayloadType.LATENCY_RET,
            PayloadType.LATENCY_NTFY
            -> {
                val enabled = ProtocolParsers.latency(payload) ?: return
                update { it.copy(lowLatency = enabled) }
            }

            PayloadType.IN_EAR_NTFY -> {
                val enabled = ProtocolParsers.inEar(payload) ?: return
                update { it.copy(inEar = enabled) }
            }

            PayloadType.SPATIAL_AUDIO_NTFY -> {
                val enabled = ProtocolParsers.spatial(payload) ?: return
                update { it.copy(spatial = enabled) }
            }

            PayloadType.GESTURES_NTFY -> {
                if (state.config?.gestures != null) {
                    val slots = ProtocolParsers.gestures(payload)
                    if (slots.isNotEmpty()) update { it.copy(gestures = slots) }
                }
            }

            PayloadType.DUAL_CONNECTION_RET,
            PayloadType.DUAL_CONNECTION_NTFY
            -> {
                val enabled = ProtocolParsers.multipoint(payload) ?: return
                update { it.copy(multipoint = enabled) }
            }

            PayloadType.DUAL_CONNECTION_DEVICES_RET -> handleMultipointDevices(payload)

            PayloadType.DUAL_CONNECTION_DEVICES_NTFY -> handleMultipointEvent(payload)

            PayloadType.EAR_FIT_TEST_NTFY -> {
                val result = ProtocolParsers.fitTest(payload) ?: return
                update { it.copy(fitResult = result) }
            }
        }
    }

    private fun resolveByDeviceName() {
        if (modelResolved) return
        val config = DeviceCatalog.byName(context, deviceName)
            ?: DeviceCatalog.generic(deviceName)
        resolveModel(config, if (config.modelId.isEmpty()) "fallback" else "device name")
    }

    private fun resolveModel(config: DeviceConfig, source: String) {
        if (modelResolved) return
        modelResolved = true
        modelFallback?.cancel(false)
        log("Model resolved via $source: ${config.name} (${config.modelId.ifEmpty { "unknown" }})")
        update { it.copy(config = config) }

        val defaults = config.gestures?.defaultStates().orEmpty()
        if (defaults.isNotEmpty()) update { it.copy(gestures = defaults) }

        send(PayloadType.BATTERY_GET, note = "Request battery")
        if (config.hasEq) {
            send(
                if (config.eqListeningModeType) PayloadType.LISTENING_MODE_GET else PayloadType.EQ_GET,
                note = "Request EQ preset",
            )
        }
        send(PayloadType.FIRMWARE_GET, note = "Request firmware")
        send(PayloadType.SERIAL_GET, note = "Request serial")
        if (!config.noUtcTimeUpdate) sendUtcTime()
        if (config.inEarDetection) send(PayloadType.IN_EAR_GET, note = "Request in-ear state")
        if (config.lowLatencyMode) send(PayloadType.LATENCY_GET, note = "Request low latency")
        if (config.personalizeAnc) send(PayloadType.PERSONALIZED_ANC_GET, note = "Request personalized ANC")
        if (config.gestures != null) send(PayloadType.GESTURES_GET, note = "Request gestures")
        if (config.hasNoiseControl) {
            send(PayloadType.ANC_CONFIGURATION_GET, note = "Request ANC configuration")
            send(PayloadType.ANC_GET, note = "Request ANC state")
        }
        if (config.bassEnhanceLevel > 0) send(PayloadType.ENHANCED_BASS_GET, note = "Request enhanced bass")
        if (config.spatialAudioSwitch) send(PayloadType.SPATIAL_AUDIO_GET, note = "Request spatial audio")
        if (config.dualConnection) {
            send(PayloadType.DUAL_CONNECTION_GET, note = "Request multipoint")
            send(
                PayloadType.DUAL_CONNECTION_DEVICES_GET,
                byteArrayOf(0),
                note = "Request multipoint devices",
            )
        }
    }

    private fun sendUtcTime() {
        val seconds = System.currentTimeMillis() / 1000
        val payload = byteArrayOf(
            ((seconds shr 24) and 0xFF).toByte(),
            ((seconds shr 16) and 0xFF).toByte(),
            ((seconds shr 8) and 0xFF).toByte(),
            (seconds and 0xFF).toByte(),
        )
        send(PayloadType.UTC_TIME_SET, payload, note = "Set UTC time")
    }

    private fun handleMultipointDevices(payload: ByteArray) {
        val page = ProtocolParsers.multipointDevices(payload) ?: return
        var added = false
        for (device in page.devices) {
            if (!multipointDevices.containsKey(device.mac)) added = true
            multipointDevices[device.mac] = device
        }
        update { it.copy(multipointDevices = multipointDevices.values.toList()) }

        if (added && multipointRequests < MAX_MULTIPOINT_REQUESTS) {
            multipointRequests++
            send(
                PayloadType.DUAL_CONNECTION_DEVICES_GET,
                byteArrayOf(multipointDevices.size.toByte()),
                note = "Request more multipoint devices",
            )
        }
    }

    private fun handleMultipointEvent(payload: ByteArray) {
        val event = ProtocolParsers.multipointEvent(payload) ?: return
        val existing = multipointDevices[event.mac]
        if (existing != null && !event.needsRefresh) {
            multipointDevices[event.mac] = existing.copy(connected = event.connected)
            update { it.copy(multipointDevices = multipointDevices.values.toList()) }
        } else {
            multipointDevices.clear()
            multipointRequests = 0
            update { it.copy(multipointDevices = emptyList()) }
            send(
                PayloadType.DUAL_CONNECTION_DEVICES_GET,
                byteArrayOf(0),
                note = "Refresh multipoint devices",
            )
        }
    }

    /* --------------------------------------------------------------- commands */

    fun refreshBattery() = send(PayloadType.BATTERY_GET, note = "Refresh battery")

    fun refreshCustomEq() = send(PayloadType.CUSTOM_EQ_GET, note = "Refresh custom EQ")

    fun refreshMultipointDevices() {
        multipointDevices.clear()
        multipointRequests = 0
        send(
            PayloadType.DUAL_CONNECTION_DEVICES_GET,
            byteArrayOf(0),
            note = "Refresh multipoint devices",
        )
    }

    fun setAnc(mode: Int) {
        val config = state.config ?: return
        if (mode !in (config.noise?.allModeBytes() ?: emptyList())) return
        send(PayloadType.ANC_SET, byteArrayOf(0x01, mode.toByte(), 0x00), note = "Set ANC")
        update { it.copy(ancRaw = mode) }
    }

    fun setPersonalizedAnc(enabled: Boolean) {
        send(
            PayloadType.PERSONALIZED_ANC_SET,
            byteArrayOf(if (enabled) 0x01 else 0x00),
            note = "Set personalized ANC",
        )
        update { it.copy(personalizedAnc = enabled) }
    }

    fun setEqPreset(mode: Int) {
        val config = state.config ?: return
        if (mode !in config.eqPresets.values) return
        send(
            if (config.eqListeningModeType) PayloadType.LISTENING_MODE_SET else PayloadType.EQ_SET,
            byteArrayOf(mode.toByte(), 0x00),
            note = "Set EQ preset",
        )
        update { it.copy(eqPresetRaw = mode) }
        if (mode == config.eqPresets["custom"]) send(PayloadType.CUSTOM_EQ_GET, note = "Request custom EQ")
    }

    fun setCustomEq(bass: Float, mid: Float, treble: Float) {
        val payload = EqCodec.buildCustomEqPayload(bass, mid, treble)
        send(PayloadType.CUSTOM_EQ_SET, payload, note = "Set custom EQ")
        update { it.copy(customEq = listOf(bass, mid, treble)) }
    }

    fun setEnhancedBass(enabled: Boolean, level: Int) {
        send(
            PayloadType.ENHANCED_BASS_SET,
            byteArrayOf(if (enabled) 0x01 else 0x00, (level * 2).toByte()),
            note = "Set enhanced bass",
        )
        update { it.copy(bassEnabled = enabled, bassLevel = level) }
    }

    fun setLowLatency(enabled: Boolean) {
        send(
            PayloadType.LATENCY_SET,
            byteArrayOf(if (enabled) 0x01 else 0x02, 0x00),
            note = "Set low latency",
        )
        update { it.copy(lowLatency = enabled) }
    }

    fun setInEarDetection(enabled: Boolean) {
        send(
            PayloadType.IN_EAR_SET,
            byteArrayOf(0x01, 0x01, if (enabled) 0x01 else 0x00),
            note = "Set in-ear detection",
        )
        update { it.copy(inEar = enabled) }
    }

    fun setSpatialAudio(enabled: Boolean) {
        send(
            PayloadType.SPATIAL_AUDIO_SET,
            byteArrayOf(if (enabled) 0x01 else 0x00),
            note = "Set spatial audio",
        )
        update { it.copy(spatial = enabled) }
    }

    fun setMultipoint(enabled: Boolean) {
        send(
            PayloadType.DUAL_CONNECTION_SET,
            byteArrayOf(if (enabled) 0x01 else 0x00),
            note = "Set multipoint",
        )
        update { it.copy(multipoint = enabled) }
    }

    fun setMultipointDevice(macHex: String, connect: Boolean) {
        val macBytes = ProtocolParsers.hexToBytes(macHex)
        if (macBytes.size != 6) return
        val payload = ByteArray(7)
        payload[0] = if (connect) 0x01 else 0x00
        macBytes.copyInto(payload, 1)
        send(PayloadType.DUAL_CONNECTION_DEVICES_SET, payload, note = "Set multipoint device")
        val current = multipointDevices[macHex.lowercase()]
        if (current != null) {
            multipointDevices[macHex.lowercase()] = current.copy(connected = connect)
            update { it.copy(multipointDevices = multipointDevices.values.toList()) }
        }
    }

    fun setGesture(slot: GestureState) {
        val payload = byteArrayOf(
            0x01,
            slot.device.toByte(),
            slot.buttonId.toByte(),
            slot.type.toByte(),
            slot.action.toByte(),
        )
        send(PayloadType.GESTURES_SET, payload, note = "Set gesture")
        val current = state.gestures.toMutableList()
        val index = current.indexOfFirst {
            it.device == slot.device && it.buttonId == slot.buttonId && it.type == slot.type
        }
        if (index >= 0) current[index] = slot else current.add(slot)
        update { it.copy(gestures = current) }
    }

    fun startFitTest() {
        send(PayloadType.EAR_FIT_TEST_SET, byteArrayOf(0x01), note = "Start fit test")
        update { it.copy(fitResult = null) }
    }

    fun ring(side: RingSide, start: Boolean) {
        val config = state.config ?: return
        val payload = if (config.ringLegacy) {
            byteArrayOf(if (start) 0x01 else 0x00)
        } else {
            val deviceByte = when {
                config.batterySingle -> 0x06
                side == RingSide.LEFT -> 0x02
                else -> 0x03
            }
            byteArrayOf(deviceByte.toByte(), if (start) 0x01 else 0x00)
        }
        send(PayloadType.RING_SET, payload, note = if (start) "Ring $side" else "Stop ringing $side")
        update { current ->
            current.copy(
                ringPlaying = if (start) current.ringPlaying + side else current.ringPlaying - side,
            )
        }
    }

    /* --------------------------------------------------------------- helpers */

    private fun publish(newState: BudsState) {
        state = newState
        listener.onState(newState)
    }

    private fun update(transform: (BudsState) -> BudsState) {
        val newState = synchronized(stateLock) {
            transform(state).also { state = it }
        }
        listener.onState(newState)
    }

    private fun log(message: String) {
        listener.onLog(message)
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 3) {
        bytes.forEach { append("%02X ".format(it.toInt() and 0xFF)) }
    }.trimEnd()

    companion object {
        private const val REQUEST_TIMEOUT_MS = 120L
        private const val MAX_MULTIPOINT_REQUESTS = 20
        private val EMPTY = ByteArray(0)
    }
}
