package com.audioprobe

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.audioprobe.audio.DumpTrimmer

/**
 * The privileged half of the app.
 *
 * Shizuku starts this class itself, inside an `app_process` it spawns with the shell
 * UID (2000 when Shizuku was started over adb). That identity is what makes
 * `dumpsys media.audio_flinger` readable: the dump is guarded by
 * android.permission.DUMP, which only shell/root hold.
 *
 * Two constructors are offered because Shizuku looks for `(Context)` first and falls
 * back to the no-arg form. Both must survive R8, hence [Keep].
 */
@Keep
class ProbeService : IProbeService.Stub {

    @Keep
    constructor() : super() {
        Log.i(TAG, "created; uid=${Process.myUid()}")
    }

    @Keep
    constructor(context: Context) : super() {
        Log.i(TAG, "created with context; uid=${Process.myUid()}")
    }

    /** Reserved by the Shizuku server; it is how unbind+remove actually kills us. */
    override fun destroy() {
        Log.i(TAG, "destroy")
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun getUid(): Int = Process.myUid()

    /**
     * Runs the probe command set and returns the dumps after [DumpTrimmer] has removed
     * everything the parsers do not read.
     *
     * The trimming is mandatory, not cosmetic: the raw output is ~660 000 characters
     * and an AIDL String reply is marshalled as UTF-16, which exceeds the binder
     * transaction buffer and fails the call outright.
     */
    override fun sampleDumps(): String = DumpTrimmer.trim(runShell(DumpTrimmer.SAMPLE_COMMAND))

    /**
     * Runs [command] through `/system/bin/sh -c` and returns stdout+stderr merged,
     * capped so an arbitrary command cannot overflow the binder reply either.
     */
    override fun exec(command: String): String {
        val output = runShell(command)
        return if (output.length <= DumpTrimmer.MAX_CHARS) {
            output
        } else {
            output.take(DumpTrimmer.MAX_CHARS) + TRUNCATED
        }
    }

    /**
     * The stream is drained to EOF *before* [Process.waitFor]: a dump like
     * `dumpsys media.audio_flinger` is ~150 KB, far more than a pipe buffer, so
     * waiting first would deadlock the child on a full pipe.
     */
    private fun runShell(command: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            if (code == 0) output else "$output\n[exit=$code]"
        } catch (t: Throwable) {
            Log.w(TAG, "exec failed: $command", t)
            "ERROR: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private companion object {
        const val TAG = "AudioProbe/UserService"
        const val TRUNCATED = "\n__AUDIOPROBE_TRUNCATED__"
    }
}
