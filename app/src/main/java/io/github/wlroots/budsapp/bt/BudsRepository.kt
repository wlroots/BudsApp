package io.github.wlroots.budsapp.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import io.github.wlroots.budsapp.model.BatteryReading
import io.github.wlroots.budsapp.model.BatteryState
import io.github.wlroots.budsapp.model.BudsState
import io.github.wlroots.budsapp.model.ConnectionStatus
import io.github.wlroots.budsapp.model.DeviceCatalog
import io.github.wlroots.budsapp.model.DeviceConfig
import io.github.wlroots.budsapp.model.FitResult
import io.github.wlroots.budsapp.model.GestureState
import io.github.wlroots.budsapp.model.RemoteDevice
import io.github.wlroots.budsapp.model.RingSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide connection state. The foreground [BudsService] keeps the process
 * alive while a session is active; UI layers only observe this repository.
 */
object BudsRepository {
    private const val PREFS = "budsapp"
    private const val KEY_LAST_MAC = "last_mac"
    private const val KEY_LAST_NAME = "last_name"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect"
    private const val KEY_SHOW_ALL = "show_all"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(BudsState())
    val state: StateFlow<BudsState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var appContext: Context? = null
    private var connection: BudsConnection? = null
    private var currentMac = ""
    private var currentName = ""
    private var userDisconnected = false
    private var demoMode = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        scope.launch(Dispatchers.IO) { DeviceCatalog.all(app) }
        val prefs = prefs(app)
        currentMac = prefs.getString(KEY_LAST_MAC, "") ?: ""
        currentName = prefs.getString(KEY_LAST_NAME, "") ?: ""
    }

    /* --------------------------------------------------------------- connection */

    @SuppressLint("MissingPermission")
    fun connect(context: Context, device: BluetoothDevice) {
        val app = context.applicationContext
        appContext = app
        userDisconnected = false
        demoMode = false
        reconnectAttempts = 0
        reconnectJob?.cancel()

        currentMac = device.address
        currentName = runCatching { device.name }.getOrNull().orEmpty()
        prefs(app).edit()
            .putString(KEY_LAST_MAC, currentMac)
            .putString(KEY_LAST_NAME, currentName)
            .apply()

        connectDevice(app, currentMac, currentName)
    }

    @SuppressLint("MissingPermission")
    fun connectLastKnown(context: Context): Boolean {
        val app = context.applicationContext
        if (currentMac.isEmpty()) return false
        if (_state.value.status != ConnectionStatus.IDLE &&
            _state.value.status != ConnectionStatus.DISCONNECTED &&
            _state.value.status != ConnectionStatus.ERROR
        ) {
            return true
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val device = try {
            adapter.getRemoteDevice(currentMac)
        } catch (_: IllegalArgumentException) {
            return false
        }
        connect(app, device)
        return true
    }

    private fun connectDevice(context: Context, mac: String, name: String) {
        connection?.close()
        connection = null

        _state.value = BudsState(
            status = ConnectionStatus.CONNECTING,
            statusMessage = "Connecting…",
            deviceName = name,
            mac = mac,
            config = DeviceCatalog.byName(context, name),
        )

        val device = try {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac)
        } catch (_: Exception) {
            null
        }
        if (device == null) {
            _state.value = _state.value.copy(
                status = ConnectionStatus.ERROR,
                statusMessage = "Bluetooth device is no longer available",
            )
            return
        }

        val transport = RfcommTransport(device, ::appendLog)
        val newConnection = BudsConnection(context, name, mac, transport, connectionListener)
        connection = newConnection
        newConnection.start()
    }

    fun disconnect() {
        userDisconnected = true
        demoMode = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        connection?.close()
        connection = null
        if (_state.value.demo) {
            _state.value = _state.value.copy(
                status = ConnectionStatus.DISCONNECTED,
                statusMessage = "Disconnected",
                demo = false,
            )
        }
    }

    private val connectionListener = object : BudsConnection.Listener {
        override fun onState(state: BudsState) {
            if (state.status == ConnectionStatus.CONNECTED) reconnectAttempts = 0
            _state.value = state
        }

        override fun onLog(line: String) {
            appendLog(line)
        }

        override fun onClosed(unexpected: Boolean, reason: String) {
            if (unexpected) {
                appendLog("Connection closed: $reason")
                maybeReconnect()
            }
        }
    }

    private fun maybeReconnect() {
        val context = appContext ?: return
        if (userDisconnected || demoMode) return
        if (currentMac.isEmpty()) return
        if (!autoReconnect(context)) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            appendLog("Auto-reconnect gave up after $MAX_RECONNECT_ATTEMPTS attempts")
            return
        }
        reconnectAttempts++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (userDisconnected || demoMode) return@launch
            appendLog("Reconnecting (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)…")
            connectDevice(context, currentMac, currentName)
        }
    }

    /* --------------------------------------------------------------- commands */

    fun refreshBattery() = withConnection { it.refreshBattery() }

    fun refreshCustomEq() = withConnection { it.refreshCustomEq() }

    fun refreshMultipointDevices() = withConnection { it.refreshMultipointDevices() }

    fun setAnc(mode: Int) {
        if (demoMode) demoSetAnc(mode) else withConnection { it.setAnc(mode) }
    }

    fun setPersonalizedAnc(enabled: Boolean) {
        if (demoMode) demoSetPersonalizedAnc(enabled) else withConnection { it.setPersonalizedAnc(enabled) }
    }

    fun setEqPreset(mode: Int) {
        if (demoMode) demoSetEqPreset(mode) else withConnection { it.setEqPreset(mode) }
    }

    fun setCustomEq(bass: Float, mid: Float, treble: Float) {
        if (demoMode) demoSetCustomEq(bass, mid, treble) else withConnection {
            it.setCustomEq(bass, mid, treble)
        }
    }

    fun setEnhancedBass(enabled: Boolean, level: Int) {
        if (demoMode) demoSetEnhancedBass(enabled, level) else withConnection {
            it.setEnhancedBass(enabled, level)
        }
    }

    fun setLowLatency(enabled: Boolean) {
        if (demoMode) demoSetLowLatency(enabled) else withConnection { it.setLowLatency(enabled) }
    }

    fun setInEarDetection(enabled: Boolean) {
        if (demoMode) demoSetInEar(enabled) else withConnection { it.setInEarDetection(enabled) }
    }

    fun setSpatialAudio(enabled: Boolean) {
        if (demoMode) demoSetSpatial(enabled) else withConnection { it.setSpatialAudio(enabled) }
    }

    fun setMultipoint(enabled: Boolean) {
        if (demoMode) demoSetMultipoint(enabled) else withConnection { it.setMultipoint(enabled) }
    }

    fun setMultipointDevice(mac: String, connect: Boolean) {
        if (demoMode) demoSetMultipointDevice(mac, connect) else withConnection {
            it.setMultipointDevice(mac, connect)
        }
    }

    fun setGesture(slot: GestureState) {
        if (demoMode) demoSetGesture(slot) else withConnection { it.setGesture(slot) }
    }

    fun startFitTest() {
        if (demoMode) {
            demoUpdate { it.copy(fitResult = null) }
            scope.launch {
                delay(2000)
                if (demoMode) demoUpdate { it.copy(fitResult = FitResult(0, 1)) }
            }
        } else {
            withConnection { it.startFitTest() }
        }
    }

    fun ring(side: RingSide, start: Boolean) {
        if (demoMode) demoRing(side, start) else withConnection { it.ring(side, start) }
    }

    fun stopAllRings() {
        val current = _state.value
        current.ringPlaying.toList().forEach { side -> ring(side, false) }
    }

    private inline fun withConnection(block: (BudsConnection) -> Unit) {
        if (demoMode) return
        connection?.let(block)
    }

    /* --------------------------------------------------------------- demo mode */

    fun startDemo(context: Context) {
        disconnect()
        demoMode = true
        userDisconnected = false
        val config = DeviceCatalog.byName(context, "Nothing Ear (2)")
            ?: DeviceCatalog.generic("BudsApp Demo")
        val demoState = BudsState(
            status = ConnectionStatus.CONNECTED,
            statusMessage = "Demo mode",
            deviceName = "BudsApp Demo",
            mac = "AA:BB:CC:DD:EE:FF",
            config = config,
            firmware = "1.0.0-demo",
            serial = "DEMO-0001",
            battery = BatteryState(
                left = BatteryReading(82, false),
                right = BatteryReading(79, false),
                case = BatteryReading(64, false),
            ),
            ancRaw = config.noise?.offByte,
            personalizedAnc = false,
            eqPresetRaw = config.eqPresets["balanced"] ?: config.eqPresets.values.firstOrNull(),
            customEq = listOf(0f, 0f, 0f),
            bassEnabled = false,
            bassLevel = 1,
            lowLatency = false,
            inEar = true,
            spatial = false,
            multipoint = true,
            multipointDevices = listOf(
                RemoteDevice("aabbccddeeff", "This phone", true),
                RemoteDevice("112233445566", "Tablet", false),
            ),
            gestures = config.gestures?.defaultStates().orEmpty(),
            demo = true,
        )
        _state.value = demoState
        appendLog("Demo mode enabled (${config.name})")
    }

    private fun demoUpdate(transform: (BudsState) -> BudsState) {
        _state.value = transform(_state.value)
    }

    fun demoSetAnc(mode: Int) = demoUpdate { it.copy(ancRaw = mode) }

    fun demoSetPersonalizedAnc(enabled: Boolean) = demoUpdate { it.copy(personalizedAnc = enabled) }

    fun demoSetEqPreset(mode: Int) = demoUpdate { it.copy(eqPresetRaw = mode) }

    fun demoSetCustomEq(bass: Float, mid: Float, treble: Float) = demoUpdate {
        it.copy(customEq = listOf(bass, mid, treble))
    }

    fun demoSetEnhancedBass(enabled: Boolean, level: Int) = demoUpdate {
        it.copy(bassEnabled = enabled, bassLevel = level)
    }

    fun demoSetLowLatency(enabled: Boolean) = demoUpdate { it.copy(lowLatency = enabled) }

    fun demoSetInEar(enabled: Boolean) = demoUpdate { it.copy(inEar = enabled) }

    fun demoSetSpatial(enabled: Boolean) = demoUpdate { it.copy(spatial = enabled) }

    fun demoSetMultipoint(enabled: Boolean) = demoUpdate { it.copy(multipoint = enabled) }

    fun demoSetMultipointDevice(mac: String, connect: Boolean) = demoUpdate { current ->
        current.copy(
            multipointDevices = current.multipointDevices.map {
                if (it.mac.equals(mac, ignoreCase = true)) it.copy(connected = connect) else it
            },
        )
    }

    fun demoSetGesture(slot: GestureState) = demoUpdate { current ->
        val list = current.gestures.toMutableList()
        val index = list.indexOfFirst {
            it.device == slot.device && it.buttonId == slot.buttonId && it.type == slot.type
        }
        if (index >= 0) list[index] = slot else list.add(slot)
        current.copy(gestures = list)
    }

    fun demoStartFitTest() = demoUpdate { it.copy(fitResult = null) }

    fun demoFinishFitTest() = demoUpdate { it.copy(fitResult = FitResult(0, 1)) }

    fun demoRing(side: RingSide, start: Boolean) = demoUpdate { current ->
        current.copy(
            ringPlaying = if (start) current.ringPlaying + side else current.ringPlaying - side,
        )
    }

    /* --------------------------------------------------------------- settings */

    fun autoReconnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_RECONNECT, true)

    fun setAutoReconnect(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
    }

    fun showAllDevices(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_ALL, false)

    fun setShowAllDevices(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_ALL, enabled).apply()
    }

    fun lastDeviceMac(): String = currentMac

    fun lastDeviceName(): String = currentName

    /* --------------------------------------------------------------- logs */

    @Synchronized
    fun appendLog(line: String) {
        val stamped = "${timeFormat.format(Date())}  $line"
        _logs.value = (_logs.value + stamped).takeLast(MAX_LOG_LINES)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val MAX_LOG_LINES = 600
    private const val MAX_RECONNECT_ATTEMPTS = 3
    private const val RECONNECT_DELAY_MS = 3000L
}
