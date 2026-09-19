package com.audioprobe.audio

/**
 * Parser for `dumpsys media.audio_flinger`.
 *
 * The dump is a sequence of output-thread blocks:
 *
 * ```
 * Output thread 0xb400..., name AudioOut_15, tid 1798, type 0 (MIXER):
 *   Sample rate: 48000 Hz
 *   HAL frame count: 2048
 *   HAL format: 0x3 (AUDIO_FORMAT_PCM_32_BIT)
 *   Output devices: 0x2 (AUDIO_DEVICE_OUT_SPEAKER)
 *   AudioStreamOut: 0xb400... flags 0x8 (AUDIO_OUTPUT_FLAG_DEEP_BUFFER)
 *   Normal mixer raw underrun counters: partial=0 empty=0
 *   1 Tracks of which 1 are active
 *     Type     Id Active Client(pid/uid) Session Port Id S  Flags   Format Chn mask  SRate ST Usg CT ...
 *            1261    yes   12768/  10342     857    1349 A  0x000 00000005 00000003  44100  3   1  0 ...
 *   0 Effect Chains
 *   Local log:
 *    09-19 10:13:32.336 AT::remove    (0xb400...)  1146  no  26595/ 10357  ...
 * ```
 *
 * Two things make this format easy to get wrong:
 *
 *  - the `Local log:` that follows every track table replays rows in the *same* column
 *    layout, so a naive row regex happily parses history as live state. Rows are only
 *    accepted between the "N Tracks" line and the next section header.
 *  - `Usg` (usage) is printed as bare hex without an `0x`, so `d` is 13, not a
 *    placeholder. Decoding it as decimal silently mislabels every track.
 *
 * The numeric columns have changed between Android releases and ROMs. When the strict
 * column layout does not match, a loose pattern still recovers the source rate and
 * format, and the caller can show the raw dump.
 */
object AudioFlingerParser {

    private val THREAD_RE = Regex(
        """^Output thread (0x[0-9a-fA-F]+),\s*name\s+(\S+),\s*tid\s+(\d+),\s*type\s+(\d+)\s*\(([^)]+)\):"""
    )

    private val TRACKS_SUMMARY_RE = Regex(
        """^\s*(\d+)\s+Tracks?(?:\s+of which\s+(\d+)\s+are active)?\s*$"""
    )

    private val TABLE_END_RE = Regex("""^\s*(?:\d+\s+Effect Chains?|Local log:)\s*$""")

    private val FIELD_SAMPLE_RATE = Regex("""^\s*Sample rate:\s*(\d+)\s*Hz""")
    private val FIELD_HAL_FRAMES = Regex("""^\s*HAL frame count:\s*(\d+)""")
    private val FIELD_HAL_FORMAT = Regex("""^\s*HAL format:\s*(0x[0-9a-fA-F]+)\s*\(([^)]*)\)""")
    private val FIELD_PROC_FORMAT =
        Regex("""^\s*Processing format:\s*(0x[0-9a-fA-F]+)\s*\(([^)]*)\)""")
    private val FIELD_CHANNEL_COUNT = Regex("""^\s*Channel count:\s*(\d+)""")
    private val FIELD_CHANNEL_MASK = Regex("""^\s*Channel mask:\s*0x([0-9a-fA-F]+)""")
    private val FIELD_DEVICES = Regex("""^\s*Output devices:\s*(0x[0-9a-fA-F]+)\s*\(([^)]*)\)""")
    private val FIELD_FLAGS = Regex("""^\s*AudioStreamOut:\s*\S+\s+flags\s*(0x[0-9a-fA-F]+)\s*\(([^)]*)\)""")
    private val FIELD_STANDBY = Regex("""^\s*Standby:\s*(yes|no)""")

    /**
     * Full column layout as printed by vivo's Android 16/17 build. Group order:
     * id, active, pid, uid, session, portId, state, flags, format, chnMask, srate,
     * ST, Usg, CT, G, L, R, VS, PortVol, PortMuted, Server, FrmCnt, FrmRdy, F,
     * Underruns, Flushed, BitPerfect, InternalMute, Latency.
     *
     * The leading `Type` column is **sometimes** populated (Android 16 prints `S`
     * there, Android 17 prints nothing), so it is matched as an optional
     * non-capturing prefix. Backtracking makes an absent Type fall through to the id.
     */
    private val TRACK_ROW_STRICT = Regex(
        """^\s*(?:\S+\s+)?(\d+)\s+(yes|no)\s+(\d+)\s*/\s*(\d+)\s+(\d+)\s+(\d+)\s+([A-Za-z])\s+(0x[0-9a-fA-F]+)\s+([0-9a-fA-F]{1,8})\s+([0-9a-fA-F]{1,8})\s+(\d+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(-?[\d.]+|-inf)\s+(-?[\d.]+|-inf)\s+(-?[\d.]+|-inf)\s+(-?[\d.]+|-inf)\s+(-?[\d.]+|-inf)\s+(true|false)\s+([0-9a-fA-F]+)\s+(\d+)\s+(\d+)\s+(\S+)\s+(\d+)\s+(\d+)\s+(true|false)\s+(true|false)\s+(.+)$"""
    )

    /**
     * Layout-agnostic fallback: everything up to and including SRate. Enough to show
     * the source rate/format even on a ROM that reshuffles the trailing columns.
     */
    private val TRACK_ROW_LOOSE = Regex(
        """^\s*(?:\S+\s+)?(\d+)\s+(yes|no)\s+(\d+)\s*/\s*(\d+)\s+(\d+)\s+(\d+)\s+([A-Za-z])\s+(\S+)\s+([0-9a-fA-F]{1,8})\s+([0-9a-fA-F]{1,8})\s+(\d+)"""
    )

    /**
     * True when [line] is a live track row rather than one of the hundreds of replayed
     * rows in the `Local log:` (those carry a leading timestamp and never match).
     * Used by [DumpTrimmer] to decide what to keep.
     */
    fun looksLikeTrackRow(line: String): Boolean =
        TRACK_ROW_STRICT.containsMatchIn(line) || TRACK_ROW_LOOSE.containsMatchIn(line)

    fun parse(text: String): List<FlingerOutputThread> {
        if (text.isBlank()) return emptyList()
        val lines = text.split('\n')
        val threads = ArrayList<FlingerOutputThread>()

        var index = 0
        while (index < lines.size) {
            if (THREAD_RE.find(lines[index]) == null) {
                index++
                continue
            }
            var end = index + 1
            while (end < lines.size && THREAD_RE.find(lines[end]) == null) end++
            // A malformed block is allowed to propagate: SnapshotBuilder reports the
            // exception with its class name, which is far easier to act on than a
            // silently shorter list of threads.
            threads += parseThread(lines, index, end)
            index = end
        }
        return threads
    }

    private fun parseThread(lines: List<String>, start: Int, end: Int): FlingerOutputThread {
        val header = THREAD_RE.find(lines[start])!!
        val handle = header.groupValues[1]
        val name = header.groupValues[2]
        val tid = header.groupValues[3].toIntOrNull() ?: -1
        val typeName = header.groupValues[5]

        var standby = false
        var sampleRate = 0
        var halFrameCount = 0
        var halFormatValue = 0L
        var halFormatName = ""
        var channelCount = 0
        var channelMask = 0
        var procFormatValue = 0L
        var procFormatName = ""
        var deviceMask = 0L
        val devices = ArrayList<String>()
        var flags = ""
        var flagNames = ""
        var declaredTracks = 0
        var declaredActive = 0
        val tracks = ArrayList<FlingerTrack>()

        var inTrackTable = false
        var pastHeaderRow = false

        for (i in start + 1 until end) {
            val line = lines[i]

            if (TABLE_END_RE.matches(line)) {
                inTrackTable = false
                pastHeaderRow = false
                continue
            }

            val summary = TRACKS_SUMMARY_RE.find(line)
            if (summary != null) {
                declaredTracks = summary.groupValues[1].toIntOrNull() ?: 0
                declaredActive = summary.groupValues[2].toIntOrNull() ?: 0
                inTrackTable = true
                pastHeaderRow = false
                continue
            }

            if (inTrackTable) {
                // Skip the "Type Id Active Client(pid/uid) ..." column header.
                if (!pastHeaderRow && line.trimStart().startsWith("Type")) {
                    pastHeaderRow = true
                    continue
                }
                parseTrackRow(line)?.let {
                    tracks += it
                    continue
                }
                // A blank or unrecognised line ends the table.
                if (line.isNotBlank()) {
                    inTrackTable = false
                    pastHeaderRow = false
                }
                continue
            }

            FIELD_STANDBY.find(line)?.let { standby = it.groupValues[1] == "yes" }
            FIELD_SAMPLE_RATE.find(line)?.let { sampleRate = parseDecInt(it.groupValues[1]) ?: 0 }
            FIELD_HAL_FRAMES.find(line)?.let { halFrameCount = parseDecInt(it.groupValues[1]) ?: 0 }
            FIELD_HAL_FORMAT.find(line)?.let {
                halFormatValue = parseHexLong(it.groupValues[1]) ?: 0L
                halFormatName = it.groupValues[2].trim()
            }
            FIELD_PROC_FORMAT.find(line)?.let {
                procFormatValue = parseHexLong(it.groupValues[1]) ?: 0L
                procFormatName = it.groupValues[2].trim()
            }
            FIELD_CHANNEL_COUNT.find(line)?.let { channelCount = parseDecInt(it.groupValues[1]) ?: 0 }
            FIELD_CHANNEL_MASK.find(line)?.let { channelMask = parseHexInt(it.groupValues[1]) ?: 0 }
            FIELD_DEVICES.find(line)?.let {
                deviceMask = parseHexLong(it.groupValues[1]) ?: 0L
                devices.clear()
                devices += it.groupValues[2].split('|').map(String::trim).filter(String::isNotEmpty)
            }
            FIELD_FLAGS.find(line)?.let {
                flags = it.groupValues[1]
                flagNames = it.groupValues[2].trim()
            }
        }

        return FlingerOutputThread(
            handle = handle,
            name = name,
            tid = tid,
            typeName = typeName,
            standby = standby,
            sampleRate = sampleRate,
            halFrameCount = halFrameCount,
            halFormatValue = halFormatValue,
            halFormatName = halFormatName,
            channelCount = channelCount,
            channelMask = channelMask,
            processingFormatValue = procFormatValue,
            processingFormatName = procFormatName,
            deviceMask = deviceMask,
            devices = devices,
            flags = flags,
            flagNames = flagNames.split('|').map(String::trim).filter(String::isNotEmpty),
            declaredTrackCount = declaredTracks,
            declaredActiveTrackCount = declaredActive,
            tracks = tracks,
        )
    }

    private fun parseTrackRow(line: String): FlingerTrack? {
        TRACK_ROW_STRICT.find(line)?.let { m ->
            val g = m.groupValues
            // Usg is printed as bare hex: "d" means 13, "f" means 15.
            return FlingerTrack(
                id = parseDecInt(g[1]) ?: return@let,
                active = g[2] == "yes",
                pid = parseDecInt(g[3]) ?: 0,
                uid = parseDecInt(g[4]) ?: 0,
                session = parseDecInt(g[5]) ?: 0,
                portId = parseDecInt(g[6]) ?: 0,
                state = g[7],
                flags = g[8],
                formatValue = parseHexLong(g[9]) ?: 0L,
                formatName = formatNameFromValue(g[9], null),
                channelMask = parseHexInt(g[10]) ?: 0,
                sampleRate = parseDecInt(g[11]) ?: 0,
                streamType = parseDecInt(g[12]) ?: 0,
                usage = parseHexInt(g[13]) ?: 0,
                contentType = parseHexInt(g[14]) ?: 0,
                bitPerfect = g[27].toBooleanStrictOrNull(),
                latency = g[29].trim(),
            )
        }

        TRACK_ROW_LOOSE.find(line)?.let { m ->
            val g = m.groupValues
            return FlingerTrack(
                id = parseDecInt(g[1]) ?: return@let,
                active = g[2] == "yes",
                pid = parseDecInt(g[3]) ?: 0,
                uid = parseDecInt(g[4]) ?: 0,
                session = parseDecInt(g[5]) ?: 0,
                portId = parseDecInt(g[6]) ?: 0,
                state = g[7],
                flags = g[8],
                formatValue = parseHexLong(g[9]) ?: 0L,
                formatName = formatNameFromValue(g[9], null),
                channelMask = parseHexInt(g[10]) ?: 0,
                sampleRate = parseDecInt(g[11]) ?: 0,
                streamType = 0,
                usage = 0,
                contentType = 0,
                bitPerfect = null,
                latency = "",
            )
        }
        return null
    }

    /**
     * The track table prints the format as a bare hex value with no symbolic name, so
     * the name has to be reconstructed from the enum value.
     */
    fun formatNameFromValue(hex: String, fallbackName: String?): String =
        formatNameFromValue(parseHexLong(hex) ?: 0L, fallbackName)

    fun formatNameFromValue(value: Long, fallbackName: String?): String {
        if (fallbackName != null && fallbackName.startsWith("AUDIO_FORMAT")) return fallbackName
        return when (value) {
            0L -> "AUDIO_FORMAT_DEFAULT"
            0x1L -> "AUDIO_FORMAT_PCM_16_BIT"
            0x2L -> "AUDIO_FORMAT_PCM_8_BIT"
            0x3L -> "AUDIO_FORMAT_PCM_32_BIT"
            0x4L -> "AUDIO_FORMAT_PCM_8_24_BIT"
            0x5L -> "AUDIO_FORMAT_PCM_FLOAT"
            0x6L -> "AUDIO_FORMAT_PCM_24_BIT_PACKED"
            0x01000000L -> "AUDIO_FORMAT_MP3"
            0x04000000L -> "AUDIO_FORMAT_AAC"
            0x08000000L -> "AUDIO_FORMAT_OPUS"
            0x12000000L -> "AUDIO_FORMAT_DSD"
            0x13000000L -> "AUDIO_FORMAT_FLAC"
            0x14000000L -> "AUDIO_FORMAT_ALAC"
            0x15000000L -> "AUDIO_FORMAT_APE"
            0x07000000L -> "AUDIO_FORMAT_VORBIS"
            0x09000000L -> "AUDIO_FORMAT_AC3"
            0x0A000000L -> "AUDIO_FORMAT_E_AC3"
            0x0D000000L -> "AUDIO_FORMAT_IEC61937"
            0x1D000000L -> "AUDIO_FORMAT_LC3"
            else -> fallbackName ?: "AUDIO_FORMAT_0x${value.toString(16)}"
        }
    }
}
