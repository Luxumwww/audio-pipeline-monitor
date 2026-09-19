package com.audioprobe.audio

/**
 * One AudioTrack as AudioFlinger sees it. This is the *source* end of the chain: the
 * rate and format the owning app handed to the framework.
 */
data class FlingerTrack(
    val id: Int,
    val active: Boolean,
    val pid: Int,
    val uid: Int,
    val session: Int,
    val portId: Int,
    val state: String,
    val flags: String,
    val formatValue: Long,
    val formatName: String,
    val channelMask: Int,
    val sampleRate: Int,
    val streamType: Int,
    val usage: Int,
    val contentType: Int,
    /** vivo/Android 16+ expose this column; null when the ROM does not print it. */
    val bitPerfect: Boolean?,
    val latency: String,
)

/**
 * One AudioFlinger output thread - the mixer that owns a HAL stream. Its sample rate is
 * the rate the mixer resamples everything to, and `halFormat` is what actually crosses
 * the HAL boundary, so together they are the "final actual output" the user asked for.
 */
data class FlingerOutputThread(
    val handle: String,
    val name: String,
    val tid: Int,
    val typeName: String,
    val standby: Boolean,
    val sampleRate: Int,
    val halFrameCount: Int,
    val halFormatValue: Long,
    val halFormatName: String,
    val channelCount: Int,
    val channelMask: Int,
    val processingFormatValue: Long,
    val processingFormatName: String,
    val deviceMask: Long,
    val devices: List<String>,
    val flags: String,
    val flagNames: List<String>,
    val declaredTrackCount: Int,
    val declaredActiveTrackCount: Int,
    val tracks: List<FlingerTrack>,
) {
    val activeTracks: List<FlingerTrack> get() = tracks.filter { it.active }

    val isBluetooth: Boolean get() = devices.any { AudioFormats.isBluetooth(it) }
    val isUsb: Boolean get() = devices.any { AudioFormats.isUsb(it) }
}

/** An `AudioPlaybackConfiguration` from `dumpsys audio`. */
data class AudioPlayer(
    val piid: Int,
    val uid: Int,
    val pid: Int,
    val packageName: String?,
    val sessionId: Int,
    val state: String,
    val usage: String,
    val contentType: String,
    val flags: String,
    val type: String,
    val channelMask: Int,
    val sampleRate: Int,
)

/** A2DP codec currently negotiated with a Bluetooth sink. */
data class BtCodec(
    val name: String,
    val sampleRate: Int?,
    val bitsPerSample: Int?,
    val channelMode: String?,
)

/** One `A2dpStateMachine` from `dumpsys bluetooth_manager --print`. */
data class BtDevice(
    val address: String,
    val connected: Boolean,
    val playing: Boolean,
    val codec: BtCodec?,
)

data class BluetoothInfo(
    val devices: List<BtDevice>,
    val activeDevice: String?,
) {
    val active: BtDevice? =
        devices.firstOrNull { it.connected && it.playing }
            ?: devices.firstOrNull { it.connected }
}
