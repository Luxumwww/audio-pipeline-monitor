package com.audioprobe.audio

/**
 * Translation tables between the symbolic names AudioFlinger prints in `dumpsys` and
 * the labels shown in the UI.
 *
 * Everything here keys off the *name* the dump already gives us (for example
 * `AUDIO_FORMAT_PCM_32_BIT` or `AUDIO_DEVICE_OUT_USB_DEVICE`) rather than off a
 * hard-coded numeric mask. A device's dump is the authoritative source, and several
 * masks changed meaning across Android releases, so guessing from bits is how parsers
 * silently start lying.
 */
object AudioFormats {

    /** Bit depth of a PCM sample format, or null for compressed / unknown formats. */
    fun bitDepth(formatName: String): Int? = when {
        formatName.contains("PCM_16_BIT") -> 16
        formatName.contains("PCM_8_24_BIT") -> 24
        formatName.contains("PCM_24_BIT_PACKED") -> 24
        formatName.contains("PCM_32_BIT") -> 32
        formatName.contains("PCM_FLOAT") -> 32
        formatName.contains("PCM_8_BIT") -> 8
        else -> null
    }

    /** True when the format carries uncompressed linear PCM. */
    fun isPcm(formatName: String): Boolean = formatName.startsWith("AUDIO_FORMAT_PCM")

    /** Human label, e.g. "32 bit 整数 PCM". */
    fun label(formatName: String, rawHex: String? = null): String {
        val known = when {
            formatName.contains("PCM_16_BIT") -> "16 bit PCM"
            formatName.contains("PCM_8_24_BIT") -> "24 bit PCM (装在 32 bit 容器)"
            formatName.contains("PCM_24_BIT_PACKED") -> "24 bit PCM (紧凑)"
            formatName.contains("PCM_32_BIT") -> "32 bit 整数 PCM"
            formatName.contains("PCM_FLOAT") -> "32 bit 浮点 PCM"
            formatName.contains("PCM_8_BIT") -> "8 bit PCM"
            formatName.contains("AUDIO_FORMAT_DSD") -> "DSD"
            formatName.contains("AUDIO_FORMAT_MP3") -> "MP3"
            formatName.contains("AUDIO_FORMAT_AAC") -> "AAC"
            formatName.contains("AUDIO_FORMAT_FLAC") -> "FLAC"
            formatName.contains("AUDIO_FORMAT_ALAC") -> "ALAC"
            formatName.contains("AUDIO_FORMAT_APE") -> "APE"
            formatName.contains("AUDIO_FORMAT_VORBIS") -> "Vorbis"
            formatName.contains("AUDIO_FORMAT_OPUS") -> "Opus"
            formatName.contains("AUDIO_FORMAT_AC3") -> "AC-3"
            formatName.contains("AUDIO_FORMAT_E_AC3") -> "E-AC-3"
            formatName.contains("AUDIO_FORMAT_IEC61937") -> "IEC61937 透传"
            formatName.contains("AUDIO_FORMAT_LC3") -> "LC3"
            else -> null
        }
        if (known != null) return known

        val cleaned = formatName
            .removePrefix("AUDIO_FORMAT_")
            .takeIf { it.isNotEmpty() && it != formatName }
        return cleaned ?: rawHex ?: "未知"
    }

    /** `AUDIO_DEVICE_OUT_*` -> Chinese label. */
    fun deviceLabel(deviceName: String): String = when {
        deviceName.contains("EARPIECE") -> "听筒"
        deviceName.contains("SPEAKER_SAFE") -> "扬声器 (安全音量)"
        deviceName.contains("SPEAKER") -> "扬声器"
        deviceName.contains("WIRED_HEADSET") -> "有线耳麦"
        deviceName.contains("WIRED_HEADPHONE") -> "有线耳机"
        deviceName.contains("BLUETOOTH_SCO") -> "蓝牙通话 (SCO)"
        deviceName.contains("BLUETOOTH_A2DP_HEADPHONES") -> "蓝牙耳机 (A2DP)"
        deviceName.contains("BLUETOOTH_A2DP_SPEAKER") -> "蓝牙音箱 (A2DP)"
        deviceName.contains("BLUETOOTH_A2DP") -> "蓝牙音频 (A2DP)"
        deviceName.contains("BLE_HEADSET") -> "蓝牙 LE 耳机"
        deviceName.contains("BLE_SPEAKER") -> "蓝牙 LE 音箱"
        deviceName.contains("BLE_BROADCAST") -> "蓝牙 LE 广播"
        deviceName.contains("USB_HEADSET") -> "USB 耳麦"
        deviceName.contains("USB_DEVICE") -> "USB 音频设备"
        deviceName.contains("USB_ACCESSORY") -> "USB 配件音频"
        deviceName.contains("HEARING_AID") -> "助听器"
        deviceName.contains("HDMI_ARC") -> "HDMI ARC"
        deviceName.contains("HDMI") -> "HDMI"
        deviceName.contains("SPDIF") -> "SPDIF"
        deviceName.contains("TELEPHONY_TX") -> "通话上行"
        deviceName.contains("REMOTE_SUBMIX") -> "内录 (Remote Submix)"
        deviceName.contains("AUX_LINE") || deviceName.contains("LINE") -> "线路输入/输出"
        deviceName.contains("BUS") -> "虚拟总线输出"
        deviceName.contains("FM") -> "FM"
        else -> deviceName.removePrefix("AUDIO_DEVICE_OUT_")
    }

    /** True when the device hands samples to an external, clocked sink. */
    fun isExternal(deviceName: String): Boolean =
        deviceName.contains("USB") ||
            deviceName.contains("BLUETOOTH") ||
            deviceName.contains("BLE_") ||
            deviceName.contains("HDMI") ||
            deviceName.contains("SPDIF")

    fun isBluetooth(deviceName: String): Boolean =
        deviceName.contains("BLUETOOTH") || deviceName.contains("BLE_")

    fun isUsb(deviceName: String): Boolean = deviceName.contains("USB")

    /** `AUDIO_OUTPUT_FLAG_*` -> short label. */
    fun outputFlagLabel(flagName: String): String = when {
        flagName.contains("DEEP_BUFFER") -> "深度缓冲"
        flagName.contains("FAST") -> "快速通道"
        flagName.contains("PRIMARY") -> "主输出"
        flagName.contains("DIRECT") -> "直通"
        flagName.contains("MMAP_NOIRQ") -> "MMAP"
        flagName.contains("OFFLOAD") -> "硬解卸载"
        flagName.contains("NON_BLOCKING") -> "非阻塞"
        flagName.contains("COMPRESS_OFFLOAD") -> "压缩卸载"
        else -> flagName.removePrefix("AUDIO_OUTPUT_FLAG_")
    }

    /** `USAGE_*` from the AudioFlinger track table (printed as bare hex). */
    fun usageLabel(usage: Int): String = when (usage) {
        0 -> "UNKNOWN"
        1 -> "MEDIA 媒体"
        2 -> "VOICE_COMMUNICATION 通话"
        3 -> "VOICE_COMMUNICATION_SIGNALLING"
        4 -> "ALARM 闹钟"
        5 -> "NOTIFICATION 通知"
        6 -> "NOTIFICATION_RINGTONE 铃声"
        7 -> "NOTIFICATION_COMMUNICATION_REQUEST"
        8 -> "NOTIFICATION_COMMUNICATION_INSTANT"
        9 -> "NOTIFICATION_COMMUNICATION_DELAYED"
        10 -> "NOTIFICATION_EVENT"
        11 -> "ASSISTANCE_ACCESSIBILITY 无障碍"
        12 -> "ASSISTANCE_NAVIGATION_GUIDANCE 导航"
        13 -> "ASSISTANCE_SONIFICATION 提示音"
        14 -> "GAME 游戏"
        15 -> "ASSISTANT 语音助手"
        16 -> "EMERGENCY 紧急"
        17 -> "SAFETY 安全"
        18 -> "VEHICLE_STATUS"
        19 -> "ANNOUNCEMENT 播报"
        else -> "USAGE_$usage"
    }

    /** `STREAM_*` legacy stream types. */
    fun streamTypeLabel(streamType: Int): String = when (streamType) {
        0 -> "STREAM_VOICE_CALL"
        1 -> "STREAM_SYSTEM"
        2 -> "STREAM_RING"
        3 -> "STREAM_MUSIC 音乐"
        4 -> "STREAM_ALARM"
        5 -> "STREAM_NOTIFICATION"
        6 -> "STREAM_BLUETOOTH_SCO"
        7 -> "STREAM_SYSTEM_ENFORCED"
        8 -> "STREAM_DTMF"
        9 -> "STREAM_TTS"
        10 -> "STREAM_ACCESSIBILITY"
        11 -> "STREAM_ASSISTANT"
        else -> "STREAM_$streamType"
    }

    /** `CONTENT_TYPE_*` */
    fun contentTypeLabel(contentType: Int): String = when (contentType) {
        0 -> "UNKNOWN"
        1 -> "SPEECH 语音"
        2 -> "MUSIC 音乐"
        3 -> "MOVIE 影视"
        4 -> "SONIFICATION 音效"
        else -> "CONTENT_TYPE_$contentType"
    }

    /** Sample rates worth naming when a stream is resampled. */
    fun isFamily(rate: Int): Boolean = rate == 44100 || rate == 48000

    /** "44.1 kHz" / "48 kHz" / "96 kHz" */
    fun rateLabel(rate: Int): String {
        if (rate <= 0) return "未知"
        return if (rate % 1000 == 0) "${rate / 1000} kHz" else "%.1f kHz".format(rate / 1000.0)
    }

    /** Human readable channel count from a channel mask. */
    fun channelLabel(mask: Int): String = when (Integer.bitCount(mask)) {
        0 -> "—"
        1 -> "单声道"
        2 -> "立体声"
        else -> "${Integer.bitCount(mask)} 声道"
    }
}
