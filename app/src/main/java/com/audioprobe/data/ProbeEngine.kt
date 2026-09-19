package com.audioprobe.data

import android.content.Context
import android.util.Log
import com.audioprobe.audio.AppInfo
import com.audioprobe.audio.AudioSnapshot
import com.audioprobe.audio.DumpTrimmer
import com.audioprobe.audio.SnapshotBuilder
import com.audioprobe.priv.PrivilegeBackend

/**
 * Turns one round of privileged `dumpsys` calls into an [AudioSnapshot].
 *
 * All four dumps are issued as a single `sh -c` invocation with section markers rather
 * than as four binder round trips, and the privileged side trims the result before
 * replying, because the untrimmed text overflows the binder transaction buffer.
 */
class ProbeEngine(
    private val context: Context,
    private val backend: PrivilegeBackend,
) {

    private val appCache = HashMap<Int, AppInfo?>()

    data class Result(val snapshot: AudioSnapshot, val elapsedMs: Long)

    /**
     * @return the snapshot plus how long the privileged round trip took, in ms.
     */
    fun sample(): Result {
        val started = System.currentTimeMillis()
        val payload = backend.sampleDumps()
        val elapsed = System.currentTimeMillis() - started

        val sections = splitSections(payload)
        val snapshot = SnapshotBuilder.build(
            flingerText = sections.finger,
            audioText = sections.audio,
            bluetoothText = sections.bluetooth,
            now = System.currentTimeMillis(),
            resolveApp = ::resolveApp,
        )
        return Result(snapshot, elapsed)
    }

    private data class Sections(val finger: String, val audio: String, val bluetooth: String)

    private fun splitSections(raw: String): Sections {
        val fingerAt = raw.indexOf(DumpTrimmer.MARK_FINGER)
        val audioAt = raw.indexOf(DumpTrimmer.MARK_AUDIO)
        val btAt = raw.indexOf(DumpTrimmer.MARK_BT)

        fun bodyAfter(markerAt: Int, marker: String, nextAt: Int): String {
            if (markerAt < 0) return ""
            val start = markerAt + marker.length
            val end = if (nextAt < 0) raw.length else nextAt
            return if (start >= end) "" else raw.substring(start, end)
        }

        return Sections(
            finger = bodyAfter(fingerAt, DumpTrimmer.MARK_FINGER, audioAt),
            audio = bodyAfter(audioAt, DumpTrimmer.MARK_AUDIO, btAt),
            bluetooth = bodyAfter(btAt, DumpTrimmer.MARK_BT, -1),
        )
    }

    /**
     * uid -> package/label, which is the cheapest way to name the playing app: the
     * AudioFlinger track table only ever prints numeric ids, and `dumpsys audio` knows
     * the package but not for every uid (cross-user uids stay unresolved).
     */
    private fun resolveApp(uid: Int): AppInfo? {
        synchronized(appCache) {
            if (appCache.containsKey(uid)) return appCache[uid]
        }
        val resolved = try {
            val pm = context.packageManager
            val packages = pm.getPackagesForUid(uid)
            val pkg = packages?.firstOrNull()
            if (pkg == null) {
                null
            } else {
                val info = pm.getApplicationInfo(pkg, 0)
                AppInfo(pkg, pm.getApplicationLabel(info).toString())
            }
        } catch (t: Throwable) {
            Log.d(TAG, "cannot resolve uid $uid", t)
            null
        }
        synchronized(appCache) { appCache[uid] = resolved }
        return resolved
    }

    private companion object {
        private const val TAG = "AudioProbe/Engine"
    }
}
