package com.audioprobe.audio

/**
 * Parser for the playback-related parts of `dumpsys audio`.
 *
 * `dumpsys audio` is what ties an AudioFlinger `session` to a *package*. AudioFlinger
 * only knows a pid and a uid; AudioService's `AudioPlaybackConfiguration` list carries
 * the matching session id, and its "new player" event log carries the package name.
 */
object AudioServiceParser {

    private val PLAYER_RE = Regex(
        """AudioPlaybackConfiguration piid:(\d+).*?type:(\S+)\s+u/pid:(\d+)/(\d+)\s+state:(\w+)\s+attr:AudioAttributes:\s*usage=(\w+)\s+content=(\w+)\s+flags=(\S+).*?sessionId:(\d+).*?FormatInfo\{[^}]*channelMask=(\w+),\s*sampleRate=(\d+)\}"""
    )

    private val NEW_PLAYER_RE = Regex(
        """new player piid:(\d+) uid/pid:(\d+)/(\d+) package:(\S+)"""
    )

    fun parse(text: String): List<AudioPlayer> {
        if (text.isBlank()) return emptyList()

        // piid -> package name, harvested from the event log.
        val packageByPiid = HashMap<Int, String>()
        for (line in text.lineSequence()) {
            val m = NEW_PLAYER_RE.find(line) ?: continue
            val piid = m.groupValues[1].toIntOrNull() ?: continue
            packageByPiid[piid] = m.groupValues[4]
        }

        val players = ArrayList<AudioPlayer>()
        for (line in text.lineSequence()) {
            if (!line.contains("AudioPlaybackConfiguration piid:")) continue
            val m = PLAYER_RE.find(line) ?: continue
            val g = m.groupValues
            val piid = g[1].toIntOrNull() ?: continue
            players += AudioPlayer(
                piid = piid,
                uid = g[3].toIntOrNull() ?: 0,
                pid = g[4].toIntOrNull() ?: 0,
                packageName = packageByPiid[piid],
                sessionId = g[9].toIntOrNull() ?: 0,
                state = g[5],
                usage = g[6],
                contentType = g[7],
                flags = g[8],
                type = g[2],
                channelMask = parseHexInt(g[10]) ?: 0,
                sampleRate = parseDecInt(g[11]) ?: 0,
            )
        }
        return players
    }
}
