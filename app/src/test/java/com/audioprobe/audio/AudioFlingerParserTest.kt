package com.audioprobe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parsers are validated against dumps captured from two real devices:
 *
 *  - `flinger_android17*.txt` - vivo V2548A, Android 17 (SDK 37)
 *  - `flinger_android16.txt`  - vivo V2232A, Android 16 (SDK 36)
 *
 * The two ROMs differ in one column (Android 16 fills the leading `Type` column), which
 * is exactly the kind of drift that silently drops rows, so both are pinned here.
 */
class AudioFlingerParserTest {

    private fun fixture(name: String): String = Fixtures.require(name)

    private fun threadNamed(threads: List<FlingerOutputThread>, name: String) =
        threads.firstOrNull { it.name == name } ?: error("no thread named $name in ${threads.map { it.name }}")

    @Test
    fun `android 17 dump yields every output thread`() {
        val threads = AudioFlingerParser.parse(fixture("flinger_android17.txt"))

        assertEquals(
            listOf("AudioOut_D", "AudioOut_15", "AudioOut_1D", "AudioOut_2D", "AudioOut_35"),
            threads.map { it.name },
        )
    }

    @Test
    fun `android 17 output thread exposes the final output format`() {
        // In this capture the single track lives on AudioOut_D; the mixer a track lands
        // on is chosen by stream/depth at open time and does move between sessions.
        val thread = threadNamed(
            AudioFlingerParser.parse(fixture("flinger_android17.txt")),
            "AudioOut_D",
        )

        assertEquals(48000, thread.sampleRate)
        assertEquals("AUDIO_FORMAT_PCM_32_BIT", thread.halFormatName)
        assertEquals("AUDIO_FORMAT_PCM_32_BIT", thread.processingFormatName)
        assertEquals(1024, thread.halFrameCount)
        assertEquals(listOf("AUDIO_DEVICE_OUT_SPEAKER"), thread.devices)
        assertTrue(thread.flagNames.contains("AUDIO_OUTPUT_FLAG_PRIMARY"))
        assertEquals(1, thread.declaredTrackCount)
    }

    @Test
    fun `android 17 track row keeps its source rate and format`() {
        val thread = threadNamed(
            AudioFlingerParser.parse(fixture("flinger_android17.txt")),
            "AudioOut_D",
        )
        assertEquals(1, thread.tracks.size)

        val track = thread.tracks.single()
        assertEquals(1231, track.id)
        assertEquals(false, track.active)
        assertEquals(2848, track.pid)
        assertEquals(10073, track.uid)
        assertEquals(6625, track.session)
        assertEquals(44100, track.sampleRate)
        assertEquals("AUDIO_FORMAT_PCM_16_BIT", track.formatName)
        assertEquals(0x3, track.channelMask)
        assertEquals(false, track.bitPerfect)
    }

    @Test
    fun `local log rows are never mistaken for live tracks`() {
        val threads = AudioFlingerParser.parse(fixture("flinger_android17.txt"))

        // The dump's Local log replays hundreds of rows in the same column layout; the
        // per-thread "N Tracks" header is the only trustworthy count.
        val declared = threads.sumOf { it.declaredTrackCount }
        val parsed = threads.sumOf { it.tracks.size }
        assertEquals(declared, parsed)
        assertEquals(5, parsed)
    }

    @Test
    fun `android 16 rows with a populated Type column are still parsed`() {
        val threads = AudioFlingerParser.parse(fixture("flinger_android16.txt"))

        val declared = threads.sumOf { it.declaredTrackCount }
        val parsed = threads.sumOf { it.tracks.size }
        assertEquals("Android 16 fills the leading Type column; rows must not be dropped", declared, parsed)

        // This row is the one that carries "S" in the Type column.
        val track = threads.flatMap { it.tracks }.first { it.id == 56 }
        assertEquals(false, track.active)
        assertEquals(4666, track.pid)
        assertEquals(1000, track.uid)
        assertEquals(73, track.session)
        assertEquals(44100, track.sampleRate)
        assertEquals("AUDIO_FORMAT_PCM_16_BIT", track.formatName)
    }

    @Test
    fun `a playing app is reported with its own rate and its mixer rate`() {
        val threads = AudioFlingerParser.parse(fixture("flinger_android17_active.txt"))

        val active = threads.flatMap { t -> t.activeTracks.map { t to it } }
        assertEquals(1, active.size)

        val (thread, track) = active.single()
        assertEquals("AudioOut_15", thread.name)
        assertEquals(1261, track.id)
        assertEquals(10342, track.uid)
        assertEquals(12768, track.pid)
        assertEquals(857, track.session)
        assertEquals("AUDIO_FORMAT_PCM_FLOAT", track.formatName)
        assertEquals(3, track.streamType)
        assertEquals(1, track.usage)

        // The whole point of the app: 44.1 kHz in, 48 kHz out.
        assertEquals(44100, track.sampleRate)
        assertEquals(48000, thread.sampleRate)
        assertEquals("AUDIO_FORMAT_PCM_32_BIT", thread.halFormatName)
        assertEquals(false, track.bitPerfect)
    }

    @Test
    fun `format values are mapped back to enum names`() {
        assertEquals("AUDIO_FORMAT_PCM_16_BIT", AudioFlingerParser.formatNameFromValue("00000001", null))
        assertEquals("AUDIO_FORMAT_PCM_32_BIT", AudioFlingerParser.formatNameFromValue("00000003", null))
        assertEquals("AUDIO_FORMAT_PCM_FLOAT", AudioFlingerParser.formatNameFromValue("00000005", null))
        assertEquals("AUDIO_FORMAT_FLAC", AudioFlingerParser.formatNameFromValue("13000000", null))
    }

    @Test
    fun `bit depth is derived from the format name`() {
        assertEquals(16, AudioFormats.bitDepth("AUDIO_FORMAT_PCM_16_BIT"))
        assertEquals(32, AudioFormats.bitDepth("AUDIO_FORMAT_PCM_32_BIT"))
        assertEquals(32, AudioFormats.bitDepth("AUDIO_FORMAT_PCM_FLOAT"))
        assertEquals(24, AudioFormats.bitDepth("AUDIO_FORMAT_PCM_24_BIT_PACKED"))
        assertEquals(null, AudioFormats.bitDepth("AUDIO_FORMAT_FLAC"))
    }
}
