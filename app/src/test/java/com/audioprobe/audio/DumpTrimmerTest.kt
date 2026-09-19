package com.audioprobe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privileged side trims the dumps before replying, because the untrimmed text
 * (~660 000 characters, marshalled as UTF-16) overflows the binder transaction buffer.
 *
 * Trimming is therefore load-bearing: if it drops a line the parsers need, the app
 * silently loses data. These tests pin the trimmed dumps to the *same* parse result as
 * the originals.
 */
class DumpTrimmerTest {

    private fun fixture(name: String): String = Fixtures.require(name)

    @Test
    fun `trimmed flinger dump parses identically`() {
        val raw = fixture("flinger_android17.txt")
        val trimmed = DumpTrimmer.trimFlinger(raw)

        assertEquals(AudioFlingerParser.parse(raw), AudioFlingerParser.parse(trimmed))
        assertTrue(
            "expected a large reduction, got ${trimmed.length} of ${raw.length}",
            trimmed.length < raw.length / 5,
        )
    }

    @Test
    fun `trimmed android 16 flinger dump parses identically`() {
        val raw = fixture("flinger_android16.txt")
        val trimmed = DumpTrimmer.trimFlinger(raw)

        assertEquals(AudioFlingerParser.parse(raw), AudioFlingerParser.parse(trimmed))
    }

    @Test
    fun `trimmed live flinger dump keeps the playing track`() {
        val raw = fixture("flinger_android17_active.txt")
        val trimmed = DumpTrimmer.trimFlinger(raw)

        assertEquals(AudioFlingerParser.parse(raw), AudioFlingerParser.parse(trimmed))
        assertEquals(1, AudioFlingerParser.parse(trimmed).sumOf { it.activeTracks.size })
    }

    @Test
    fun `trimmed audio dump parses identically`() {
        val raw = fixture("audio_android17_active.txt")
        val trimmed = DumpTrimmer.trimAudio(raw)

        assertEquals(AudioServiceParser.parse(raw), AudioServiceParser.parse(trimmed))
        assertTrue(trimmed.length < raw.length / 5)
    }

    @Test
    fun `trimmed bluetooth dump parses identically`() {
        val raw = fixture("bluetooth_android17.txt")
        val trimmed = DumpTrimmer.trimBluetooth(raw)

        assertEquals(BluetoothParser.parse(raw), BluetoothParser.parse(trimmed))
        assertTrue(
            "the bluetooth dump is the largest of the three; got ${trimmed.length} of ${raw.length}",
            trimmed.length < raw.length / 20,
        )
    }

    @Test
    fun `trimmed connected bluetooth dump parses identically`() {
        val raw = fixture("bluetooth_connected_android17.txt")
        val trimmed = DumpTrimmer.trimBluetooth(raw)

        assertEquals(BluetoothParser.parse(raw), BluetoothParser.parse(trimmed))
        assertTrue(trimmed.length < raw.length / 20)

        // The bitrate block sits in the dump's Native: region, far outside
        // Profile: A2dpService, so the trimmer needs its own rule to keep it.
        val state = BluetoothParser.parse(trimmed).codecState
        assertEquals("LOW", state?.qualityTier)
        assertEquals(330, state?.bitrateKbps)
    }

    @Test
    fun `the combined payload survives the round trip and stays small`() {
        val snapshot = snapshotOf(
            flinger = "flinger_android17_active.txt",
            audio = "audio_android17_active.txt",
            bluetooth = "bluetooth_android17.txt",
        )

        assertTrue(snapshot.errors.isEmpty())
        assertEquals(1, snapshot.links.size)

        val link = snapshot.links.single()
        assertEquals("Salt Player", link.appLabel)
        assertEquals("com.salt.music", link.packageName)
        assertEquals(44100, link.sourceSampleRate)
        assertEquals(48000, link.thread.sampleRate)
        assertEquals(48000, link.finalSampleRate)
        assertEquals("AUDIO_FORMAT_PCM_FLOAT", link.sourceFormatName)
        assertEquals("AUDIO_FORMAT_PCM_32_BIT", link.thread.halFormatName)

        // The player hands the framework *float* samples (AudioTrack float mode), so the
        // source depth is 32-bit float even when the decoded file is 16-bit PCM. The
        // HAL format is the one that answers "what actually leaves the device".
        assertEquals(32, link.sourceBitDepth)
        assertEquals(32, link.finalBitDepth)
        assertTrue(link.isResampled)
    }

    @Test
    fun `a bluetooth chain ends at the codec rather than the HAL format`() {
        val snapshot = snapshotOf(
            flinger = "flinger_bt_active_android17.txt",
            audio = "audio_android17_active.txt",
            bluetooth = "bluetooth_connected_android17.txt",
        )

        assertTrue(snapshot.errors.isEmpty())
        assertEquals(1, snapshot.links.size)

        val link = snapshot.links.single()

        // Salt Player upsamples to 192 kHz, the framework resamples down to the 96 kHz
        // the LDAC link negotiated, and the codec is the real final format.
        assertEquals(192000, link.sourceSampleRate)
        assertEquals("AUDIO_FORMAT_PCM_FLOAT", link.sourceFormatName)
        assertEquals(96000, link.thread.sampleRate)
        assertEquals("AUDIO_FORMAT_PCM_32_BIT", link.thread.halFormatName)
        assertEquals(listOf("AUDIO_DEVICE_OUT_BLUETOOTH_A2DP"), link.thread.devices)

        assertTrue(link.isBluetooth)
        assertEquals("LDAC", link.bluetooth?.codec?.name)
        assertEquals(96000, link.finalSampleRate)
        assertEquals(32, link.finalBitDepth)
        assertTrue(link.isResampled)

        assertTrue(
            "expected a note naming the codec, got ${link.notes.map { it.text }}",
            link.notes.any { it.text.contains("LDAC") },
        )
        assertTrue(
            "expected a 192 kHz -> 96 kHz resample note",
            link.notes.any { it.text.contains("192") && it.text.contains("96") },
        )
    }

    @Test
    fun `the trimmed combined payload is far below the binder budget`() {
        val raw = buildString {
            append(DumpTrimmer.MARK_FINGER).append('\n')
            append(fixture("flinger_bt_active_android17.txt"))
            append('\n').append(DumpTrimmer.MARK_AUDIO).append('\n')
            append(fixture("audio_android17_active.txt"))
            append('\n').append(DumpTrimmer.MARK_BT).append('\n')
            append(fixture("bluetooth_connected_android17.txt"))
        }

        val trimmed = DumpTrimmer.trim(raw)

        // The untrimmed payload is ~710 000 characters, which as UTF-16 overflows the
        // binder transaction buffer and fails the remote call outright.
        assertTrue("untrimmed was ${raw.length}", raw.length > 600_000)
        assertTrue("trimmed payload was ${trimmed.length} chars", trimmed.length < 60_000)
    }

    private fun snapshotOf(flinger: String, audio: String, bluetooth: String): AudioSnapshot {
        val payload = buildString {
            append(DumpTrimmer.MARK_FINGER).append('\n').append(fixture(flinger))
            append('\n').append(DumpTrimmer.MARK_AUDIO).append('\n').append(fixture(audio))
            append('\n').append(DumpTrimmer.MARK_BT).append('\n').append(fixture(bluetooth))
        }
        val trimmed = DumpTrimmer.trim(payload)

        return SnapshotBuilder.build(
            flingerText = section(trimmed, DumpTrimmer.MARK_FINGER, DumpTrimmer.MARK_AUDIO),
            audioText = section(trimmed, DumpTrimmer.MARK_AUDIO, DumpTrimmer.MARK_BT),
            bluetoothText = section(trimmed, DumpTrimmer.MARK_BT, null),
            now = 0L,
            resolveApp = { uid -> if (uid == 10342) AppInfo("com.salt.music", "Salt Player") else null },
        )
    }

    private fun section(text: String, from: String, to: String?): String {
        val start = text.indexOf(from)
        if (start < 0) return ""
        val body = start + from.length
        val end = if (to == null) text.length else text.indexOf(to).let { if (it < 0) text.length else it }
        return if (body >= end) "" else text.substring(body, end)
    }
}
