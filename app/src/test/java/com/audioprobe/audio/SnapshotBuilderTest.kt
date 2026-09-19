package com.audioprobe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * USB output cannot be exercised against the capture devices (no DAC was attached), so
 * the USB branch is covered with a synthetic dump that uses the real column layout.
 *
 * USB and Bluetooth differ in an important way: a USB DAC receives uncompressed PCM, so
 * the AudioFlinger HAL stream *is* the final output and there is no codec stage. Getting
 * this wrong would report a phantom codec for a wired DAC.
 */
class SnapshotBuilderTest {

    private val usbFlingerDump = listOf(
        "Output thread 0xb4000072e3948410, name AudioOut_15, tid 1798, type 0 (MIXER):",
        "  I/O handle: 15",
        "  Standby: no",
        "  Sample rate: 96000 Hz",
        "  HAL frame count: 1024",
        "  HAL format: 0x3 (AUDIO_FORMAT_PCM_32_BIT)",
        "  Channel count: 2",
        "  Channel mask: 0x00000003 (front-left, front-right)",
        "  Processing format: 0x3 (AUDIO_FORMAT_PCM_32_BIT)",
        "  Output devices: 0x4000 (AUDIO_DEVICE_OUT_USB_DEVICE)",
        "  AudioStreamOut: 0xb40000705c820360 flags 0x8 (AUDIO_OUTPUT_FLAG_DEEP_BUFFER)",
        "  Normal mixer raw underrun counters: partial=0 empty=0",
        "  1 Tracks of which 1 are active",
        "    Type     Id Active Client(pid/uid) Session Port Id S  Flags   Format Chn mask  SRate ST Usg CT  G db  L dB  R dB  VS dB  PortVol dB  PortMuted   Server FrmCnt  FrmRdy F Underruns  Flushed BitPerfect InternalMute   Latency",
        "           1270    yes   12768/  10342     857    1367 A  0x000 00000005 00000003 192000  3   1  0    -2     0     0     0           -2      false 0000C000  74880   74880 A         0        0      false        false  638.89 t",
        "  0 Effect Chains",
        "  Local log:",
    ).joinToString("\n")

    @Test
    fun `a usb chain reports the HAL format and has no codec stage`() {
        val snapshot = SnapshotBuilder.build(
            flingerText = usbFlingerDump,
            audioText = "",
            bluetoothText = "",
            now = 0L,
            resolveApp = { AppInfo("com.salt.music", "Salt Player") },
        )

        assertTrue("unexpected errors: ${snapshot.errors}", snapshot.errors.isEmpty())
        assertEquals(1, snapshot.links.size)

        val link = snapshot.links.single()
        assertEquals(listOf("AUDIO_DEVICE_OUT_USB_DEVICE"), link.thread.devices)
        assertFalse(link.isBluetooth)
        assertNull(link.bluetooth)

        // The final output is the USB DAC's PCM stream.
        assertEquals(96000, link.thread.sampleRate)
        assertEquals(96000, link.finalSampleRate)
        assertEquals("AUDIO_FORMAT_PCM_32_BIT", link.thread.halFormatName)
        assertEquals(32, link.finalBitDepth)

        // 192 kHz from the app down to the 96 kHz the DAC is running at.
        assertEquals(192000, link.sourceSampleRate)
        assertTrue(link.isResampled)
        assertTrue(
            "expected a 192 kHz -> 96 kHz note, got ${link.notes.map { it.text }}",
            link.notes.any { it.text.contains("192") && it.text.contains("96") },
        )
        assertTrue(
            "a USB chain must not mention a codec",
            link.notes.none { it.text.contains("编码") },
        )
    }

    @Test
    fun `device names are mapped to user-facing labels`() {
        assertEquals("USB 音频设备", AudioFormats.deviceLabel("AUDIO_DEVICE_OUT_USB_DEVICE"))
        assertEquals("USB 耳麦", AudioFormats.deviceLabel("AUDIO_DEVICE_OUT_USB_HEADSET"))
        assertEquals("蓝牙音频 (A2DP)", AudioFormats.deviceLabel("AUDIO_DEVICE_OUT_BLUETOOTH_A2DP"))
        assertEquals("蓝牙耳机 (A2DP)", AudioFormats.deviceLabel("AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES"))
        assertEquals("扬声器", AudioFormats.deviceLabel("AUDIO_DEVICE_OUT_SPEAKER"))
        assertEquals("听筒", AudioFormats.deviceLabel("AUDIO_DEVICE_OUT_EARPIECE"))

        assertTrue(AudioFormats.isUsb("AUDIO_DEVICE_OUT_USB_DEVICE"))
        assertTrue(AudioFormats.isBluetooth("AUDIO_DEVICE_OUT_BLUETOOTH_A2DP"))
        assertFalse(AudioFormats.isUsb("AUDIO_DEVICE_OUT_SPEAKER"))
    }

    @Test
    fun `the parser self-check flags a track row it cannot read`() {
        // The thread claims two tracks but only one row is present; the mismatch must be
        // surfaced instead of silently under-reporting.
        val damaged = usbFlingerDump.replace("1 Tracks of which 1 are active", "2 Tracks of which 1 are active")

        val snapshot = SnapshotBuilder.build(
            flingerText = damaged,
            audioText = "",
            bluetoothText = "",
            now = 0L,
            resolveApp = { null },
        )

        assertTrue(
            "expected an incomplete-parse warning, got ${snapshot.errors}",
            snapshot.errors.any { it.contains("解析不完整") },
        )
    }
}
