package com.audioprobe.audio

/**
 * Parser for the A2DP codec state inside `dumpsys bluetooth_manager --print`.
 *
 * The plain `dumpsys bluetooth_manager` dump is a stub that ends with
 * "Use --print argument for dumpsys direct from AdapterService", so `--print` is
 * required to reach the codec information.
 *
 * Two subtleties matter:
 *
 *  - `mCodecConfig` keeps the *last negotiated* codec even while the link is down.
 *    It is only meaningful as "what is playing now" when the state machine is
 *    STATE_CONNECTED, so the caller must not present a stale config as live.
 *  - A2DP is a compressed link. `mBitsPerSample` describes the depth the encoder is
 *    fed, not a lossless PCM depth at the other end, so it is reported as
 *    "编码输入位深" rather than as the final output bit depth.
 */
object BluetoothParser {

    /**
     * The active device's header carries a suffix: `=== A2dpStateMachine for
     * XX:XX:XX:XX:FA:02 (Active) ===`. Requiring a bare address silently skipped
     * exactly the one device that was connected and playing.
     */
    private val STATE_MACHINE_RE =
        Regex("""===\s*A2dpStateMachine for\s+(\S+?)(?:\s*\([^)]*\))?\s*===""")
    private val A2DP_SERVICE_RE = Regex("""^Profile:\s*A2dpService\s*$""")
    private val CONNECTION_STATE_RE = Regex("""mConnectionState:\s*(STATE_\w+)""")
    private val IS_PLAYING_RE = Regex("""mIsPlaying:\s*(true|false)""")
    private val ACTIVE_DEVICE_RE = Regex("""mActiveDevice:\s*(\S+)""")

    // The codec block is delimited by braces. It is deliberately read with plain string
    // operations rather than a regex: a pattern ending in a bare "}" such as
    // `mCodecConfig:\s*\{(.*)}` compiles on the JVM but throws on Android's regex
    // engine, and because these are `val`s the failure happens in the object's
    // <clinit>, which permanently marks the whole parser as un-initialisable
    // ("Rejecting re-init on previously-failed class ... ExceptionInInitializerError").
    private val CODEC_SAMPLE_RATE_RE = Regex("""mSampleRate:0x[0-9a-fA-F]+\(([^)]*)\)""")
    private val CODEC_BITS_RE = Regex("""mBitsPerSample:0x[0-9a-fA-F]+\(([^)]*)\)""")
    private val CODEC_CHANNEL_MODE_RE = Regex("""mChannelMode:0x[0-9a-fA-F]+\(([^)]*)\)""")

    // The `A2DP <codec> State:` blocks live in the dump's Native: region and are the only
    // source for the configured bitrate. Their field names are codec-prefixed and differ
    // per codec, so they are matched by keyword rather than by exact label.
    private val CODECS_STATE_RE = Regex("""^A2DP Codecs State:\s*$""")
    private val CURRENT_CODEC_RE = Regex("""^\s*Current Codec:\s*(\S+)\s*$""")
    private val A2DP_STATE_HEADER_RE = Regex("""^A2DP (.+?) State:.*$""")
    private val BITRATE_KBPS_RE = Regex("""\(Kbps\)\s*:\s*(\d+)""")
    private val QUALITY_MODE_RE = Regex("""quality mode\s*:\s*(.+?)\s*$""")
    private val BITRATE_MODE_RE = Regex("""bitrate mode\s*:\s*(.+?)\s*$""")

    /** `A2DP <name> State:` headers that are not codec blocks. */
    private val NON_CODEC_SECTIONS = setOf("Codecs", "Peers", "Source", "Sink")

    private const val ACTIVE_DEVICE_NULL = "null"
    private const val ZERO_ADDRESS = "00:00:00:00:00:00"

    fun parse(text: String): BluetoothInfo {
        if (text.isBlank()) return BluetoothInfo(emptyList(), null)

        val lines = text.split('\n')
        val currentCodec = parseCurrentCodec(lines)
        return BluetoothInfo(
            devices = parseStateMachines(lines),
            activeDevice = parseActiveDevice(lines),
            currentCodec = currentCodec,
            codecState = parseCodecState(lines, currentCodec),
        )
    }

    /** `A2DP Codecs State:` -> `Current Codec: LDAC`. */
    private fun parseCurrentCodec(lines: List<String>): String? {
        var inCodecsState = false
        for (line in lines) {
            if (CODECS_STATE_RE.matches(line)) {
                inCodecsState = true
                continue
            }
            if (!inCodecsState) continue
            if (line.isNotEmpty() && !line[0].isWhitespace()) return null
            CURRENT_CODEC_RE.find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    /**
     * Reads the block for whichever codec the stack says is current, so a device running
     * AAC or SBC does not pick up LDAC's numbers.
     */
    private fun parseCodecState(lines: List<String>, codecName: String?): BtCodecState? {
        if (codecName.isNullOrEmpty()) return null

        var i = 0
        while (i < lines.size) {
            val header = A2DP_STATE_HEADER_RE.find(lines[i])
            if (header == null) {
                i++
                continue
            }
            val name = header.groupValues[1].trim()

            // A codec block is indented until the next non-indented line.
            var j = i + 1
            while (j < lines.size && (lines[j].isEmpty() || lines[j][0].isWhitespace())) j++

            if (name !in NON_CODEC_SECTIONS && name.equals(codecName, ignoreCase = true)) {
                return parseCodecBlock(name, lines.subList(i + 1, j))
            }
            i = j
        }
        return null
    }

    private fun parseCodecBlock(name: String, block: List<String>): BtCodecState {
        var tier: String? = null
        var bitrate: Int? = null
        var mode: String? = null

        for (line in block) {
            val trimmed = line.trim()
            BITRATE_KBPS_RE.find(trimmed)?.let { bitrate = it.groupValues[1].toIntOrNull() }
            QUALITY_MODE_RE.find(trimmed)?.let { tier = it.groupValues[1].trim() }
            BITRATE_MODE_RE.find(trimmed)?.let { mode = it.groupValues[1].trim() }
        }
        return BtCodecState(name, tier, bitrate, mode)
    }

    /**
     * `mActiveDevice` is printed by several profile services, so it is only read from
     * inside the `Profile: A2dpService` block rather than from the first match in the
     * whole dump.
     */
    private fun parseActiveDevice(lines: List<String>): String? {
        var inA2dpService = false
        for (line in lines) {
            if (A2DP_SERVICE_RE.matches(line)) {
                inA2dpService = true
                continue
            }
            if (inA2dpService) {
                // The block ends at the next non-indented line.
                if (line.isNotEmpty() && !line[0].isWhitespace()) return null
                val m = ACTIVE_DEVICE_RE.find(line) ?: continue
                val value = m.groupValues[1]
                if (value != ACTIVE_DEVICE_NULL && value != ZERO_ADDRESS) return value
            }
        }
        return null
    }

    private fun parseStateMachines(lines: List<String>): List<BtDevice> {
        val devices = ArrayList<BtDevice>()
        var i = 0
        while (i < lines.size) {
            val header = STATE_MACHINE_RE.find(lines[i])
            if (header == null) {
                i++
                continue
            }
            val address = header.groupValues[1]

            // The state machine body is indented; the next non-indented, non-empty line
            // starts a different section, and the next "===" line starts another device.
            var j = i + 1
            while (j < lines.size) {
                val line = lines[j]
                if (STATE_MACHINE_RE.find(line) != null) break
                if (line.isNotEmpty() && !line[0].isWhitespace()) break
                j++
            }

            var connected = false
            var playing = false
            var codec: BtCodec? = null
            for (k in i + 1 until j) {
                val line = lines[k]
                CONNECTION_STATE_RE.find(line)?.let {
                    connected = it.groupValues[1] == "STATE_CONNECTED"
                }
                IS_PLAYING_RE.find(line)?.let { playing = it.groupValues[1] == "true" }
                if (line.contains("mCodecConfig:")) {
                    parseCodec(line)?.let { codec = it }
                }
            }

            devices += BtDevice(address, connected, playing, codec)
            i = j
        }
        return devices
    }

    /**
     * Reads `{codecName:LDAC,mCodecType:4,...,mSampleRate:0x8(96000),mBitsPerSample:0x4(32),...}`.
     *
     * The codec name is pulled out with string operations rather than a regex so that no
     * pattern in this file contains a bare brace - see the note above the pattern list.
     */
    private fun parseCodec(line: String): BtCodec? {
        val body = line.substringAfter("mCodecConfig:", "")
        val name = body.substringAfter("codecName:", "")
            .substringBefore(',')
            .substringBefore('}')
            .trim()
        if (name.isEmpty()) return null

        return BtCodec(
            name = name,
            sampleRate = numericValue(CODEC_SAMPLE_RATE_RE.find(body)?.groupValues?.get(1)),
            bitsPerSample = numericValue(CODEC_BITS_RE.find(body)?.groupValues?.get(1)),
            channelMode = CODEC_CHANNEL_MODE_RE.find(body)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() && it != "NONE" },
        )
    }

    /** `mSampleRate:0x8(96000)` -> 96000; `0x0(NONE)` or `44100|48000` -> null. */
    private fun numericValue(raw: String?): Int? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty() || text.equals("NONE", ignoreCase = true)) return null
        return text.substringBefore('|').trim().toIntOrNull()
    }
}
