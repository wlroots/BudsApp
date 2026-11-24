package io.github.wlroots.budsapp.model

/** Parsed gesture slot as reported by the earbuds. */
data class GestureState(
    val device: Int,
    val buttonId: Int,
    val type: Int,
    val action: Int,
)

data class GestureSlotDef(
    val group: String,
    val device: Int,
    val buttonId: Int,
    val type: String,
)

data class GestureMapping(val type: String, val actions: List<String>)

data class GestureConfig(
    val defaultHex: String?,
    val slots: List<GestureSlotDef>,
    val gestureTypes: Map<String, Int>,
    val actions: Map<String, List<Int>>,
    val gestures: Map<String, GestureMapping>,
    val noiseControlModes: List<String>,
) {
    fun typeByte(type: String): Int? = gestureTypes[type]

    fun typeName(byte: Int): String? = gestureTypes.entries.firstOrNull { it.value == byte }?.key

    fun actionName(byte: Int): String? = actions.entries.firstOrNull { byte in it.value }?.key

    fun actionByte(action: String): Int? = actions[action]?.firstOrNull()

    fun actionsForType(byte: Int): List<String> {
        val name = typeName(byte) ?: return emptyList()
        return gestures[name]?.actions ?: emptyList()
    }

    /** Current actions from the model's factory-default gesture blob. */
    fun defaultStates(): List<GestureState> {
        val hex = defaultHex ?: return emptyList()
        if (hex.length < 2) return emptyList()
        val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        if (bytes.isEmpty()) return emptyList()
        val count = bytes[0].toInt() and 0xFF
        val states = ArrayList<GestureState>(count)
        var offset = 1
        repeat(count) {
            if (offset + 3 >= bytes.size) return@repeat
            states.add(
                GestureState(
                    device = bytes[offset].toInt() and 0xFF,
                    buttonId = bytes[offset + 1].toInt() and 0xFF,
                    type = bytes[offset + 2].toInt() and 0xFF,
                    action = bytes[offset + 3].toInt() and 0xFF,
                ),
            )
            offset += 4
        }
        return states
    }
}

data class NoiseConfig(
    val offByte: Int?,
    val transparencyByte: Int?,
    val noiseCancellationByte: Int?,
    val levels: Map<String, Int>,
) {
    val hasLevels: Boolean get() = levels.isNotEmpty()

    fun allModeBytes(): List<Int> {
        val result = ArrayList<Int>()
        offByte?.let { result.add(it) }
        transparencyByte?.let { result.add(it) }
        noiseCancellationByte?.let { result.add(it) }
        result.addAll(levels.values)
        return result
    }

    /** The bytes that count as "noise cancellation" (level bytes or the single NC byte). */
    fun noiseCancellationBytes(): List<Int> {
        if (levels.isNotEmpty()) return levels.values.toList()
        return listOfNotNull(noiseCancellationByte)
    }
}

data class DeviceConfig(
    val modelId: String,
    val name: String,
    val patternSource: String?,
    val patternFlags: String,
    val batteryLR: Boolean,
    val batteryCase: Boolean,
    val batterySingle: Boolean,
    val eqPresets: Map<String, Int>,
    val eqListeningModeType: Boolean,
    val personalizeAnc: Boolean,
    val noise: NoiseConfig?,
    val bassEnhanceLevel: Int,
    val inEarDetection: Boolean,
    val lowLatencyMode: Boolean,
    val fitTest: Boolean,
    val ring: Boolean,
    val ringLegacy: Boolean,
    val dualConnection: Boolean,
    val dualConnectionReboot: Boolean,
    val spatialAudioSwitch: Boolean,
    val spatialHeadTracking: Boolean,
    val spatialImmersiveModes: Boolean,
    val autoPowerOff: List<Int>,
    val noUtcTimeUpdate: Boolean,
    val gestures: GestureConfig?,
    val icon: String,
) {
    private val pattern: Regex? by lazy {
        val source = patternSource ?: return@lazy null
        try {
            val options = HashSet<RegexOption>()
            if (patternFlags.contains("i")) options.add(RegexOption.IGNORE_CASE)
            if (patternFlags.contains("m")) options.add(RegexOption.MULTILINE)
            Regex(source, options)
        } catch (_: Exception) {
            null
        }
    }

    fun matches(deviceName: String): Boolean = pattern?.matches(deviceName.trim()) ?: false

    fun eqPresetName(value: Int): String? = eqPresets.entries.firstOrNull { it.value == value }?.key

    fun eqPresetCode(preset: String): Int? = eqPresets[preset]

    /** Presets in a sensible display order (org.json object order is not stable). */
    fun orderedEqPresets(): List<Pair<String, Int>> = eqPresets.entries.sortedBy { entry ->
        val index = EQ_ORDER.indexOf(entry.key)
        if (index >= 0) index else 100
    }.map { it.key to it.value }

    val hasEq: Boolean get() = eqPresets.isNotEmpty()

    val hasNoiseControl: Boolean get() = noise != null && noise.allModeBytes().isNotEmpty()

    fun isNoiseCancellation(mode: Int): Boolean = noise?.noiseCancellationBytes()?.contains(mode) == true

    fun noiseModeKey(mode: Int): String? {
        val nc = noise ?: return null
        if (nc.offByte == mode) return "off"
        if (nc.transparencyByte == mode) return "transparency"
        if (nc.noiseCancellationBytes().contains(mode)) return "noiseCancellation"
        return null
    }

    companion object {
        val EQ_ORDER = listOf(
            "balanced", "dirac", "pop", "rock", "electronic", "enhance_vocals",
            "classical", "voice", "more_treble", "more_bass", "advance", "custom",
        )
        val ANC_LEVEL_ORDER = listOf("low", "mid", "high", "adaptive")
    }
}
