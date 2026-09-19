package com.audioprobe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothParserTest {

    private fun fixture(name: String): String = Fixtures.require(name)

    @Test
    fun `every A2DP state machine in the dump is found`() {
        val info = BluetoothParser.parse(fixture("bluetooth_android17.txt"))

        // The dump redacts MAC addresses to XX:XX:XX:XX:xx:xx, which the parser must
        // accept as just another token.
        assertEquals(
            listOf("XX:XX:XX:XX:01:21", "XX:XX:XX:XX:8E:35", "XX:XX:XX:XX:FA:02"),
            info.devices.map { it.address },
        )
    }

    @Test
    fun `the negotiated codec is read from mCodecConfig`() {
        val info = BluetoothParser.parse(fixture("bluetooth_android17.txt"))
        val device = info.devices.first { it.address == "XX:XX:XX:XX:01:21" }

        val codec = device.codec
        assertNotNull("mCodecConfig must be parsed", codec)
        assertEquals("LDAC", codec!!.name)
        assertEquals(96000, codec.sampleRate)
        assertEquals(32, codec.bitsPerSample)
        assertEquals("STEREO", codec.channelMode)
    }

    @Test
    fun `a stale codec config is not reported as a connected link`() {
        // At capture time the headset was disconnected, but mCodecConfig still held the
        // last negotiation - it must not be presented as live output.
        val info = BluetoothParser.parse(fixture("bluetooth_android17.txt"))
        val device = info.devices.first { it.address == "XX:XX:XX:XX:01:21" }

        assertEquals(false, device.connected)
        assertEquals(false, device.playing)
    }

    @Test
    fun `an empty dump is handled`() {
        val info = BluetoothParser.parse("")
        assertTrue(info.devices.isEmpty())
        assertEquals(null, info.active)
        assertEquals(null, info.activeDevice)
    }

    @Test
    fun `the active marker suffix does not hide the connected device`() {
        // The connected device's header reads
        //   === A2dpStateMachine for XX:XX:XX:XX:FA:02 (Active) ===
        // A parser that insists on a bare address there skips exactly the one device
        // that is actually streaming.
        val info = BluetoothParser.parse(fixture("bluetooth_connected_android17.txt"))

        assertEquals(
            listOf("XX:XX:XX:XX:01:21", "XX:XX:XX:XX:8E:35", "XX:XX:XX:XX:FA:02"),
            info.devices.map { it.address },
        )

        val connected = info.devices.first { it.address == "XX:XX:XX:XX:FA:02" }
        assertEquals(true, connected.connected)
        assertEquals("LDAC", connected.codec?.name)
        assertEquals(96000, connected.codec?.sampleRate)
        assertEquals(32, connected.codec?.bitsPerSample)
        assertEquals("STEREO", connected.codec?.channelMode)

        assertEquals("XX:XX:XX:XX:FA:02", info.active?.address)
    }

    @Test
    fun `the configured bitrate and quality tier come from the codec state block`() {
        // `A2DP Codecs State:` / `A2DP LDAC State:` live in the dump's Native: region,
        // nowhere near Profile: A2dpService, and are the only place a bitrate appears.
        val info = BluetoothParser.parse(fixture("bluetooth_connected_android17.txt"))

        assertEquals("LDAC", info.currentCodec)

        val state = info.codecState
        assertNotNull("A2DP LDAC State must be parsed", state)
        assertEquals("LDAC", state!!.codecName)
        assertEquals("LOW", state.qualityTier)
        assertEquals(330, state.bitrateKbps)
        assertEquals(null, state.bitrateMode)
    }

    @Test
    fun `a dump without the codec state block reports nothing instead of guessing`() {
        // This capture predates the A2DP <codec> State sections entirely.
        val info = BluetoothParser.parse(fixture("bluetooth_android17.txt"))

        assertEquals(null, info.currentCodec)
        assertEquals(null, info.codecState)
    }
}
