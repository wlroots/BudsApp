package io.github.wlroots.budsapp.protocol

/**
 * Command / notification identifiers from the Nothing/CMF SPP protocol.
 * Values mirror BudsLink's nothingBudsConfig.js and kalm's nothing.hpp.
 */
object PayloadType {
    const val PROTOCOL_GET = 0xC001
    const val PROTOCOL_RET = 0x4001
    const val PROTOCOL_SET = 0xF001

    const val SERIAL_GET = 0xC006
    const val SERIAL_RET = 0x4006

    const val FIRMWARE_GET = 0xC042
    const val FIRMWARE_NTFY = 0x4042

    const val DEVICE_MODEL_GET = 0xC01C
    const val DEVICE_MODEL_RET = 0x401C

    const val UTC_TIME_SET = 0xF00A

    const val BATTERY_GET = 0xC007
    const val BATTERY_PRIMARY_NTFY = 0xE001
    const val BATTERY_SECONDARY_NTFY = 0x4007

    const val GESTURES_GET = 0xC018
    const val GESTURES_SET = 0xF003
    const val GESTURES_NTFY = 0x4018

    const val ANC_CONFIGURATION_GET = 0xC01D
    const val ANC_GET = 0xC01E
    const val ANC_SET = 0xF00F
    const val ANC_PRIMARY_NTFY = 0xE003
    const val ANC_SECONDARY_NTFY = 0x401E

    const val PERSONALIZED_ANC_GET = 0xC020
    const val PERSONALIZED_ANC_SET = 0xF011
    const val PERSONALIZED_ANC_NTFY = 0x4020

    const val EQ_GET = 0xC01F
    const val EQ_SET = 0xF010
    const val EQ_NTFY = 0x401F

    const val CUSTOM_EQ_GET = 0xC044
    const val CUSTOM_EQ_SET = 0xF041
    const val CUSTOM_EQ_NTFY = 0x4044

    const val ENHANCED_BASS_GET = 0xC04E
    const val ENHANCED_BASS_SET = 0xF051
    const val ENHANCED_BASS_RET = 0x404E
    const val ENHANCED_BASS_NTFY = 0x7051

    const val LISTENING_MODE_GET = 0xC050
    const val LISTENING_MODE_SET = 0xF01D
    const val LISTENING_MODE_NTFY = 0x4050

    const val SPATIAL_AUDIO_GET = 0xC04F
    const val SPATIAL_AUDIO_SET = 0xF052
    const val SPATIAL_AUDIO_NTFY = 0x404F

    const val IN_EAR_GET = 0xC00E
    const val IN_EAR_SET = 0xF004
    const val IN_EAR_NTFY = 0x400E

    const val LATENCY_GET = 0xC041
    const val LATENCY_SET = 0xF040
    const val LATENCY_RET = 0x4041
    const val LATENCY_NTFY = 0x7040

    const val RING_SET = 0xF002
    const val EAR_FIT_TEST_SET = 0xF014
    const val EAR_FIT_TEST_NTFY = 0xE00D

    const val DUAL_CONNECTION_GET = 0xC027
    const val DUAL_CONNECTION_SET = 0xF01A
    const val DUAL_CONNECTION_RET = 0x4027
    const val DUAL_CONNECTION_NTFY = 0xE006

    const val DUAL_CONNECTION_DEVICES_GET = 0xC028
    const val DUAL_CONNECTION_DEVICES_SET = 0xF01B
    const val DUAL_CONNECTION_DEVICES_RET = 0x4028
    const val DUAL_CONNECTION_DEVICES_NTFY = 0xE00E
}
