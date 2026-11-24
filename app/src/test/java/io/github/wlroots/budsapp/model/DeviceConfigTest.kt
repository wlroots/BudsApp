package io.github.wlroots.budsapp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceConfigTest {

    private fun config(
        noise: NoiseConfig? = null,
        eq: Map<String, Int> = emptyMap(),
        gestures: GestureConfig? = null,
    ) = DeviceConfig(
        modelId = "B155",
        name = "Nothing Ear (2)",
        patternSource = "Nothing Ear \\(2\\)",
        patternFlags = "i",
        batteryLR = true,
        batteryCase = true,
        batterySingle = false,
        eqPresets = eq,
        eqListeningModeType = false,
        personalizeAnc = true,
        noise = noise,
        bassEnhanceLevel = 3,
        inEarDetection = true,
        lowLatencyMode = true,
        fitTest = true,
        ring = true,
        ringLegacy = false,
        dualConnection = true,
        dualConnectionReboot = false,
        spatialAudioSwitch = true,
        spatialHeadTracking = false,
        spatialImmersiveModes = false,
        autoPowerOff = emptyList(),
        noUtcTimeUpdate = false,
        gestures = gestures,
        icon = "earbuds-stem",
    )

    @Test
    fun matchesDeviceNameUsingRegexFlags() {
        val config = config()
        assertTrue(config.matches("nothing ear (2)"))
        assertTrue(config.matches("  Nothing Ear (2)  "))
        assertFalse(config.matches("Nothing Ear (a)"))
    }

    @Test
    fun ordersEqPresetsForDisplay() {
        val config = config(
            eq = linkedMapOf(
                "custom" to 0x05,
                "rock" to 0x03,
                "balanced" to 0x00,
                "voice" to 0x01,
            ),
        )

        assertEquals(
            listOf("balanced", "rock", "voice", "custom"),
            config.orderedEqPresets().map { it.first },
        )
        assertEquals(0x05, config.eqPresetCode("custom"))
        assertNull(config.eqPresetCode("missing"))
    }

    @Test
    fun mapsNoiseModesIncludingLevels() {
        val noise = NoiseConfig(
            offByte = 0x05,
            transparencyByte = 0x07,
            noiseCancellationByte = null,
            levels = linkedMapOf("low" to 0x03, "mid" to 0x02, "high" to 0x01),
        )
        val config = config(noise = noise)

        assertTrue(config.hasNoiseControl)
        assertEquals("off", config.noiseModeKey(0x05))
        assertEquals("transparency", config.noiseModeKey(0x07))
        assertEquals("noiseCancellation", config.noiseModeKey(0x02))
        assertTrue(config.isNoiseCancellation(0x01))
        assertFalse(config.isNoiseCancellation(0x05))
        assertNull(config.noiseModeKey(0x99))
        assertEquals(listOf(0x05, 0x07, 0x03, 0x02, 0x01), noise.allModeBytes())
    }

    @Test
    fun parsesDefaultGestureBlob() {
        val gestures = GestureConfig(
            defaultHex = "080201020902010308020107160201090103010209030103080301071603010901",
            slots = emptyList(),
            gestureTypes = mapOf("double" to 2, "triple" to 3, "action-hold" to 7, "double-action-hold" to 9),
            actions = mapOf(
                "play-pause" to listOf(2),
                "noise-control" to listOf(10, 20, 21, 22),
                "volume-up" to listOf(18),
            ),
            gestures = mapOf(
                "double" to GestureMapping("tap", listOf("play-pause")),
                "action-hold" to GestureMapping("tap", listOf("noise-control")),
            ),
            noiseControlModes = listOf("off", "transparency", "noise-cancellation"),
        )

        val states = gestures.defaultStates()

        assertEquals(8, states.size)
        assertEquals(GestureState(2, 1, 2, 9), states.first())
        assertEquals(GestureState(3, 1, 9, 1), states.last())
        assertEquals("noise-control", gestures.actionName(21))
        assertEquals(10, gestures.actionByte("noise-control"))
        assertEquals(listOf("noise-control"), gestures.actionsForType(7))
    }

    @Test
    fun unknownGestureTypeReturnsEmptyActions() {
        val gestures = GestureConfig(
            defaultHex = null,
            slots = emptyList(),
            gestureTypes = mapOf("double" to 2),
            actions = emptyMap(),
            gestures = emptyMap(),
            noiseControlModes = emptyList(),
        )

        assertTrue(gestures.defaultStates().isEmpty())
        assertTrue(gestures.actionsForType(99).isEmpty())
        assertNull(gestures.typeByte("nope"))
    }
}
