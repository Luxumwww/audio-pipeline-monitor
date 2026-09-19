package com.audioprobe.audio

/**
 * Shrinks the raw dumps before they cross the binder boundary.
 *
 * This is not an optimisation, it is required: `dumpsys media.audio_flinger` +
 * `dumpsys audio` + `dumpsys bluetooth_manager --print` is about 660 000 characters,
 * and an AIDL `String` reply is marshalled as UTF-16. That is well past the ~1 MB
 * binder transaction buffer, and the reply fails with
 * "Transaction failed on small parcel; remote process probably died, but this could
 * also be caused by running out of binder buffer space".
 *
 * Trimming happens inside the privileged process, where the full text never has to
 * leave, and keeps only the lines the parsers actually read:
 *
 *  - AudioFlinger: thread headers, the config fields, the track counts and the live
 *    track rows. The `Local log:`, `Hal stream dump:` and effect dumps are the bulk of
 *    the dump and are dropped.
 *  - AudioService: the `players:` list and the `new player` event lines.
 *  - Bluetooth: only `Profile: A2dpService` and its A2DP state machines.
 *
 * [MAX_CHARS] is a final ceiling so that an unexpected ROM cannot fail the transaction
 * again.
 */
object DumpTrimmer {

    const val MARK_FINGER = "__AUDIOPROBE_SECTION_FINGER__"
    const val MARK_AUDIO = "__AUDIOPROBE_SECTION_AUDIO__"
    const val MARK_BT = "__AUDIOPROBE_SECTION_BT__"

    /**
     * Binder transactions are capped around 1 MB and a Java String costs 2 bytes per
     * character on the wire, so keep the reply around 360 KB.
     */
    const val MAX_CHARS = 180_000

    private const val TRUNCATED = "\n__AUDIOPROBE_TRUNCATED__"

    /**
     * The markers deliberately contain no shell metacharacters. An earlier version used
     * `<<<...>>>` and `sh` parsed the trailing angle brackets as redirections, so the
     * entire command failed with "syntax error: unexpected '>'".
     */
    val SAMPLE_COMMAND: String = buildString {
        append("echo ").append(MARK_FINGER).append("; ")
        append("/system/bin/dumpsys media.audio_flinger 2>&1; ")
        append("echo ").append(MARK_AUDIO).append("; ")
        append("/system/bin/dumpsys audio 2>&1; ")
        append("echo ").append(MARK_BT).append("; ")
        append("/system/bin/dumpsys bluetooth_manager --print 2>&1")
    }

    private val TRACKS_SUMMARY = Regex("""^\s*\d+\s+Tracks?\b""")
    private val EFFECT_CHAINS = Regex("""^\d+\s+Effect Chains?$""")

    private val FLINGER_KEEP_PREFIXES = listOf(
        "Sample rate:",
        "HAL frame count:",
        "HAL format:",
        "Processing format:",
        "Channel count:",
        "Channel mask:",
        "Output devices:",
        "Input device:",
        "Audio source:",
        "AudioStreamOut:",
        "Standby:",
        "I/O handle:",
        "Local log:",
    )

    private val BT_KEEP_PREFIXES = listOf(
        "mActiveDevice:",
        "mConnectionState:",
        "mIsPlaying:",
        "mCodecConfig:",
        "getSupportsOptionalCodecs:",
    )

    /**
     * The `A2DP <codec> State:` blocks sit in the dump's `Native:` region - a long way
     * from `Profile: A2dpService` - and are the only source for the configured bitrate,
     * so they need their own keep rules.
     */
    private val A2DP_STATE_SECTION_RE = Regex("""^A2DP .*State:.*$""")

    private val CODEC_STATE_KEEP = listOf(
        "Current Codec:",
        "Config:",
        "quality mode",
        "bitrate mode",
        "(Kbps)",
    )

    fun trim(raw: String): String {
        val fingerAt = raw.indexOf(MARK_FINGER)
        val audioAt = raw.indexOf(MARK_AUDIO)
        val btAt = raw.indexOf(MARK_BT)

        val trimmed = buildString {
            append(MARK_FINGER).append('\n')
            append(trimFlinger(section(raw, fingerAt, MARK_FINGER, audioAt)))
            append(MARK_AUDIO).append('\n')
            append(trimAudio(section(raw, audioAt, MARK_AUDIO, btAt)))
            append(MARK_BT).append('\n')
            append(trimBluetooth(section(raw, btAt, MARK_BT, -1)))
        }

        return if (trimmed.length <= MAX_CHARS) trimmed else trimmed.take(MAX_CHARS) + TRUNCATED
    }

    fun trimFlinger(text: String): String = text.lineSequence()
        .filter { line ->
            val t = line.trim()
            line.startsWith("Output thread ") ||
                t.startsWith("Input thread ") ||
                FLINGER_KEEP_PREFIXES.any(t::startsWith) ||
                TRACKS_SUMMARY.containsMatchIn(line) ||
                EFFECT_CHAINS.matches(t) ||
                t.startsWith("Type ") ||
                AudioFlingerParser.looksLikeTrackRow(line)
        }
        .joinToString("\n")

    fun trimAudio(text: String): String = text.lineSequence()
        .filter { line ->
            val t = line.trim()
            t == "players:" ||
                line.contains("AudioPlaybackConfiguration piid:") ||
                line.contains("new player piid:")
        }
        .joinToString("\n")

    fun trimBluetooth(text: String): String {
        val kept = ArrayList<String>()
        var inA2dpService = false
        var inCodecState = false

        for (line in text.lineSequence()) {
            if (line.startsWith("Profile:")) {
                inA2dpService = line.trim() == "Profile: A2dpService"
                inCodecState = false
                if (inA2dpService) kept += line
                continue
            }

            if (A2DP_STATE_SECTION_RE.matches(line.trim())) {
                // Keep every codec section header, including the ones we do not read, so
                // the parser's block boundaries stay intact.
                inCodecState = true
                kept += line
                continue
            }

            if (inCodecState) {
                val t = line.trim()
                if (t.isEmpty()) {
                    inCodecState = false
                    continue
                }
                if (line[0].isWhitespace()) {
                    if (CODEC_STATE_KEEP.any(t::contains)) kept += line
                    continue
                }
                inCodecState = false
                // falls through to the A2dpService rules below
            }

            if (!inA2dpService) continue
            val t = line.trim()
            if (t.startsWith("===") || BT_KEEP_PREFIXES.any(t::startsWith)) kept += line
        }
        return kept.joinToString("\n")
    }

    private fun section(raw: String, markerAt: Int, marker: String, nextAt: Int): String {
        if (markerAt < 0) return ""
        val start = markerAt + marker.length
        val end = if (nextAt < 0) raw.length else nextAt
        return if (start >= end) "" else raw.substring(start, end)
    }
}
