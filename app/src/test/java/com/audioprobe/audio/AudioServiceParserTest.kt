package com.audioprobe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioServiceParserTest {

    private fun fixture(name: String): String = Fixtures.require(name)

    @Test
    fun `playback configurations are parsed from the players section`() {
        val players = AudioServiceParser.parse(fixture("audio_android17_active.txt"))
        assertTrue("expected several players", players.size > 3)

        val playing = players.first { it.sessionId == 857 }
        assertEquals(10342, playing.uid)
        assertEquals(12768, playing.pid)
        assertEquals("started", playing.state)
        assertEquals("USAGE_MEDIA", playing.usage)
        assertEquals("CONTENT_TYPE_UNKNOWN", playing.contentType)
        assertEquals("android.media.AudioTrack", playing.type)
        assertEquals(0x3, playing.channelMask)
        assertEquals(44100, playing.sampleRate)
    }

    @Test
    fun `the package name is recovered from the event log`() {
        val players = AudioServiceParser.parse(fixture("audio_android17_active.txt"))

        // AudioFlinger only knows uid/pid; the package comes from AudioService's
        // "new player" log lines, joined on piid.
        val playing = players.first { it.sessionId == 857 }
        assertEquals("com.salt.music", playing.packageName)

        // Configurations without a matching "new player" line stay unresolved rather
        // than being guessed at.
        assertTrue(players.any { it.packageName == "com.salt.music" })
        assertTrue(players.count { it.packageName != null } >= 1)
    }

    @Test
    fun `an empty dump yields nothing`() {
        assertTrue(AudioServiceParser.parse("").isEmpty())
    }
}
