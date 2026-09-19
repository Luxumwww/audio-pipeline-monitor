// The privileged half of the app.
//
// This interface is implemented by ProbeService, which Shizuku starts inside its own
// app_process running with the shell UID (2000). Everything declared here executes
// with that identity, which is what grants access to `dumpsys`.
//
// Transaction id 16777114 is reserved by the Shizuku server for destroy(); the
// server sends 16777115 for it, the AIDL compiler maps the value written below.
package com.audioprobe;

interface IProbeService {

    /** Reserved destroy method defined by the Shizuku server. */
    void destroy() = 16777114;

    /** Ask the user service to terminate itself. */
    void exit() = 1;

    /**
     * Run a command through `sh -c` as the privileged identity and return its
     * combined stdout/stderr.
     */
    String exec(String command) = 2;

    /**
     * Run the fixed audio-probe command set and return the *trimmed* dumps.
     *
     * Trimming happens here rather than in the app because the untrimmed output is
     * ~660 000 characters, which overflows the binder transaction buffer on the way
     * back.
     */
    String sampleDumps() = 4;

    /** UID the user service actually runs as (2000 for Shizuku-over-adb). */
    int getUid() = 3;
}
