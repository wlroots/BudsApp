package io.github.wlroots.budsapp.model

enum class ConnectionStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
}

data class BatteryReading(val level: Int, val charging: Boolean)

data class BatteryState(
    val left: BatteryReading? = null,
    val right: BatteryReading? = null,
    val case: BatteryReading? = null,
    val single: BatteryReading? = null,
) {
    val hasAny: Boolean get() = left != null || right != null || case != null || single != null

    fun summary(): String {
        single?.let { return "${it.level}%" }
        val parts = ArrayList<String>(3)
        left?.let { parts.add("L ${it.level}%") }
        right?.let { parts.add("R ${it.level}%") }
        case?.let { parts.add("Case ${it.level}%") }
        return parts.joinToString(" · ")
    }
}

data class RemoteDevice(
    val mac: String,
    val name: String,
    val connected: Boolean,
)

data class FitResult(val left: Int, val right: Int) {
    fun label(value: Int): String = when (value) {
        0 -> "Good seal"
        1 -> "Bad seal"
        else -> "Error"
    }
}

data class BudsState(
    val status: ConnectionStatus = ConnectionStatus.IDLE,
    val statusMessage: String = "",
    val deviceName: String = "",
    val mac: String = "",
    val config: DeviceConfig? = null,
    val firmware: String = "",
    val serial: String = "",
    val battery: BatteryState = BatteryState(),
    val ancRaw: Int? = null,
    val personalizedAnc: Boolean? = null,
    val eqPresetRaw: Int? = null,
    val customEq: List<Float>? = null,
    val bassEnabled: Boolean? = null,
    val bassLevel: Int? = null,
    val lowLatency: Boolean? = null,
    val inEar: Boolean? = null,
    val spatial: Boolean? = null,
    val multipoint: Boolean? = null,
    val multipointDevices: List<RemoteDevice> = emptyList(),
    val fitResult: FitResult? = null,
    val gestures: List<GestureState> = emptyList(),
    val ringPlaying: Set<RingSide> = emptySet(),
    val demo: Boolean = false,
) {
    val isConnected: Boolean get() = status == ConnectionStatus.CONNECTED
    val isBusy: Boolean get() = status == ConnectionStatus.CONNECTING
}

enum class RingSide { LEFT, RIGHT, SINGLE }
