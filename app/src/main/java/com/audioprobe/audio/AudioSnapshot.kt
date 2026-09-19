package com.audioprobe.audio

/** Resolved owner of a uid, supplied by the caller (needs a PackageManager). */
data class AppInfo(val packageName: String, val label: String)

/** A single remark about one hop of the chain. */
data class ChainNote(val level: Level, val text: String) {
    enum class Level { GOOD, RESAMPLED, CONVERTED, INFO, WARN }
}

/**
 * One complete playback chain: the app's AudioTrack, the AudioFlinger mixer it landed
 * on, the device that mixer feeds, and - when that device is a Bluetooth sink - the
 * codec the samples end up in.
 */
data class ChainLink(
    val appLabel: String,
    val packageName: String?,
    val uid: Int,
    val pid: Int,
    val session: Int,
    val trackId: Int,
    val sourceSampleRate: Int,
    val sourceFormatName: String,
    val sourceChannelMask: Int,
    val usage: Int,
    val contentType: Int,
    val streamType: Int,
    val bitPerfect: Boolean?,
    val thread: FlingerOutputThread,
    val bluetooth: BtDevice?,
    val notes: List<ChainNote>,
) {
    /** Bluetooth replaces the PCM HAL stage with a codec, so it becomes the last hop. */
    val isBluetooth: Boolean get() = thread.isBluetooth && bluetooth?.codec != null

    /** The rate that actually leaves the device. */
    val finalSampleRate: Int
        get() = if (isBluetooth) bluetooth?.codec?.sampleRate ?: thread.sampleRate
        else thread.sampleRate

    /** Bit depth of the last PCM stage, or the codec's input depth for A2DP. */
    val finalBitDepth: Int?
        get() = if (isBluetooth) bluetooth?.codec?.bitsPerSample
        else AudioFormats.bitDepth(thread.halFormatName)

    val sourceBitDepth: Int? get() = AudioFormats.bitDepth(sourceFormatName)

    val isResampled: Boolean
        get() = sourceSampleRate > 0 && finalSampleRate > 0 && sourceSampleRate != finalSampleRate
}

/** Everything one sampling pass produced. */
data class AudioSnapshot(
    val timestampMs: Long,
    val links: List<ChainLink>,
    val threads: List<FlingerOutputThread>,
    val players: List<AudioPlayer>,
    val bluetooth: BluetoothInfo,
    val errors: List<String>,
    val rawFlinger: String,
    val rawAudio: String,
    val rawBluetooth: String,
) {
    val hasActivePlayback: Boolean get() = links.isNotEmpty()
    val activeThreads: List<FlingerOutputThread> get() = threads.filter { it.activeTracks.isNotEmpty() }
}

/**
 * Merges the three dumps into the chain view.
 *
 * Cross-referencing works like this:
 *
 *  - AudioFlinger gives, for every active track, the uid/pid/session and the source
 *    rate+format, nested inside the output thread it is mixed into.
 *  - `dumpsys audio` maps session -> package, used when the uid cannot be resolved
 *    (for example a uid belonging to another Android user).
 *  - `dumpsys bluetooth_manager --print` supplies the A2DP codec, which is the real
 *    final format when the sink is a Bluetooth device.
 */
object SnapshotBuilder {

    fun build(
        flingerText: String,
        audioText: String,
        bluetoothText: String,
        now: Long,
        resolveApp: (uid: Int) -> AppInfo?,
    ): AudioSnapshot {
        val errors = ArrayList<String>()

        val threads = runCatching { AudioFlingerParser.parse(flingerText) }
            .onFailure { errors += describe("解析 audio_flinger 失败", it) }
            .getOrDefault(emptyList())

        val players = runCatching { AudioServiceParser.parse(audioText) }
            .onFailure { errors += describe("解析 audio 失败", it) }
            .getOrDefault(emptyList())

        val bluetooth = runCatching { BluetoothParser.parse(bluetoothText) }
            .onFailure { errors += describe("解析 bluetooth_manager 失败", it) }
            .getOrDefault(BluetoothInfo(emptyList(), null))

        if (threads.isEmpty() && flingerText.isNotBlank()) {
            errors += "audio_flinger 中没有解析出输出线程，可能是该系统版本的输出格式不同。"
        }

        // Self-check for silent data loss: the dump states how many tracks each thread
        // has, so a mismatch means a row layout this parser does not understand.
        val declaredTracks = threads.sumOf { it.declaredTrackCount }
        val parsedTracks = threads.sumOf { it.tracks.size }
        if (declaredTracks != parsedTracks) {
            errors += "音频轨道解析不完整：系统声明 $declaredTracks 条，实际解析出 $parsedTracks 条。"
        }

        val playerBySession = players.associateBy { it.sessionId }
        val btActive = bluetooth.active

        val links = ArrayList<ChainLink>()
        for (thread in threads) {
            for (track in thread.activeTracks) {
                val player = playerBySession[track.session]
                val app = resolveApp(track.uid)
                val packageName = app?.packageName ?: player?.packageName
                val label = app?.label ?: packageName ?: "uid ${track.uid}"

                val bt = if (thread.isBluetooth) btActive else null

                links += ChainLink(
                    appLabel = label,
                    packageName = packageName,
                    uid = track.uid,
                    pid = track.pid,
                    session = track.session,
                    trackId = track.id,
                    sourceSampleRate = track.sampleRate,
                    sourceFormatName = track.formatName,
                    sourceChannelMask = track.channelMask,
                    usage = track.usage,
                    contentType = track.contentType,
                    streamType = track.streamType,
                    bitPerfect = track.bitPerfect,
                    thread = thread,
                    bluetooth = bt,
                    notes = buildNotes(track, thread, bt),
                )
            }
        }

        return AudioSnapshot(
            timestampMs = now,
            links = links,
            threads = threads,
            players = players,
            bluetooth = bluetooth,
            errors = errors,
            rawFlinger = flingerText,
            rawAudio = audioText,
            rawBluetooth = bluetoothText,
        )
    }

    /**
     * The exception *class* is part of the message on purpose: a Kotlin NPE carries no
     * message, so `${'$'}{it.message}` alone would render as "null" and say nothing.
     */
    private fun describe(prefix: String, t: Throwable): String =
        "$prefix: ${t.javaClass.simpleName}: ${t.message ?: "(无详细信息)"}"

    private fun buildNotes(
        track: FlingerTrack,
        thread: FlingerOutputThread,
        bluetooth: BtDevice?,
    ): List<ChainNote> {
        val notes = ArrayList<ChainNote>()
        val codec = bluetooth?.codec

        // Hop 1: app -> mixer. AudioFlinger resamples here when the rates differ.
        if (track.sampleRate > 0 && thread.sampleRate > 0 && track.sampleRate != thread.sampleRate) {
            notes += ChainNote(
                ChainNote.Level.RESAMPLED,
                "AudioFlinger 重采样 ${AudioFormats.rateLabel(track.sampleRate)} → " +
                    "${AudioFormats.rateLabel(thread.sampleRate)}"
            )
        }

        // Hop 2: mixer -> device. For Bluetooth this is the codec's input format.
        if (codec?.sampleRate != null && codec.sampleRate != thread.sampleRate) {
            notes += ChainNote(
                ChainNote.Level.RESAMPLED,
                "送入 ${codec.name} 编码器前再次转换 " +
                    "${AudioFormats.rateLabel(thread.sampleRate)} → " +
                    "${AudioFormats.rateLabel(codec.sampleRate)}"
            )
        }

        val sourceBits = AudioFormats.bitDepth(track.formatName)
        val halBits = AudioFormats.bitDepth(thread.halFormatName)
        if (codec != null) {
            notes += ChainNote(
                ChainNote.Level.INFO,
                "蓝牙是压缩传输，最终链路为 ${codec.name} 编码，" +
                    "不是无损 PCM；下面的位深指编码器输入位深。"
            )
        } else if (sourceBits != null && halBits != null && sourceBits != halBits) {
            notes += ChainNote(
                ChainNote.Level.CONVERTED,
                "位深转换 应用 ${sourceBits} bit → 硬件输出 ${halBits} bit"
            )
        }

        if (track.bitPerfect == true) {
            notes += ChainNote(ChainNote.Level.GOOD, "系统标记 BitPerfect：采样率与位深均未被改动")
        }

        if (track.sampleRate > 0 &&
            thread.sampleRate > 0 &&
            track.sampleRate == thread.sampleRate &&
            (codec?.sampleRate == null || codec.sampleRate == thread.sampleRate) &&
            (sourceBits == null || halBits == null || sourceBits == halBits)
        ) {
            notes += ChainNote(ChainNote.Level.GOOD, "全程采样率与位深一致，未发生转换")
        }

        if (thread.standby) {
            notes += ChainNote(ChainNote.Level.INFO, "输出线程处于 standby")
        }

        return notes
    }
}
