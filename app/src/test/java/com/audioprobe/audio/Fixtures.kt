package com.audioprobe.audio

import org.junit.Assume.assumeTrue

/**
 * Loads the real-device dumps that the parser tests run against.
 *
 * **These fixtures are deliberately not committed to this repository.** They are raw
 * `dumpsys` output and, even though the ROM redacts the MAC addresses inside the A2DP
 * state machines, other parts of the same dumps contain unredacted Bluetooth MACs, the
 * device model, the build id, and the full list of installed third-party packages -
 * none of which belongs in a public repo.
 *
 * To run the full suite, capture your own dumps into
 * `app/src/test/resources/fixtures/`:
 *
 * ```sh
 * adb shell dumpsys media.audio_flinger        > flinger_android17.txt
 * adb shell dumpsys audio                      > audio_android17.txt
 * adb shell dumpsys bluetooth_manager --print  > bluetooth_android17.txt
 * ```
 *
 * See the README for the complete file list - including the file whose rows carry a
 * populated `Type` column, which is what the Android 16 regression test needs. When a
 * fixture is missing the affected test is *skipped*, not failed, so a fresh clone still
 * gets a green build.
 */
object Fixtures {

    fun read(name: String): String? {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$name") ?: return null
        // PowerShell wrote the captured files with a UTF-8 BOM.
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }.removePrefix("\uFEFF")
    }

    fun require(name: String): String {
        val text = read(name)
        assumeTrue(
            "fixture '$name' is not present - see Fixtures.kt for how to capture it",
            text != null,
        )
        return text!!
    }
}
