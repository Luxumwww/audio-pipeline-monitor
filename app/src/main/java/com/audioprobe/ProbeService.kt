package com.audioprobe

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.audioprobe.audio.DumpTrimmer

/**
 * Build version of the privileged half of the app. Bump it whenever ProbeService - or
 * anything it uses, such as [DumpTrimmer] - changes.
 *
 * It is reported over AIDL and compared against its own copy by
 * [com.audioprobe.priv.ShizukuBackend]. That check exists because a Shizuku user service
 * outlives an app update: after `adb install -r` the server keeps handing back the process
 * it started from the previous APK. Bumping `UserServiceArgs.version()` did not force a
 * respawn, and neither did changing the service tag nor calling
 * `unbindUserService(remove = true)` - the pid stayed the same in all three cases
 * (Shizuku 13 / Android 17). Without this check an upgraded app would silently keep
 * executing the previous build's dump parsing, with no visible symptom.
 */
internal const val PROBE_SERVICE_VERSION = 5

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
        onCreated("no-arg")
    }

    @Keep
    constructor(context: Context) : super() {
        onCreated("with context")
    }

    private fun onCreated(which: String) {
        Log.i(TAG, "created ($which); pid=${Process.myPid()} uid=${Process.myUid()}")
        reapStaleSiblings()
    }

    /**
     * Kills user-service processes left behind by earlier builds.
     *
     * Because the tag carries [PROBE_SERVICE_VERSION], each upgrade starts a new process
     * and leaves the old one running - Shizuku does not reap it. This one runs at startup
     * and sweeps the others, so a privileged process is never orphaned.
     */
    private fun reapStaleSiblings() {
        try {
            val me = Process.myPid()
            val listing = runShell(
                "/system/bin/ps -A -o PID,NAME | /system/bin/grep 'com.audioprobe:probe'"
            )
            val stale = listing.lineSequence()
                .mapNotNull { line ->
                    line.trim().split(' ').firstOrNull()?.toIntOrNull()
                }
                .filter { it != me }
                .toList()

            if (stale.isNotEmpty()) {
                Log.i(TAG, "reaping stale sibling process(es): $stale")
                runShell("/system/bin/kill -9 " + stale.joinToString(" "))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not reap stale siblings", t)
        }
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

    override fun getServiceVersion(): Int = PROBE_SERVICE_VERSION

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
