package com.audioprobe.priv

import kotlinx.coroutines.flow.StateFlow

/** Where the privilege layer currently stands. */
sealed interface PrivilegeState {

    /** No Shizuku (or compatible backend) app installed at all. */
    data object BackendMissing : PrivilegeState

    /** Backend installed but its binder has not reached us - it is not running. */
    data object BackendNotRunning : PrivilegeState

    /** Binder is alive, but this app was not granted access yet. */
    data object PermissionRequired : PrivilegeState

    /** Access granted, user service being started / bound. */
    data object Connecting : PrivilegeState

    /** Ready: commands can be executed with the privileged identity. */
    data class Ready(val uid: Int, val version: Int) : PrivilegeState

    data class Failed(val message: String) : PrivilegeState
}

/**
 * A source of privileged command execution.
 *
 * The app is written against this interface rather than against Shizuku directly so
 * an alternative backend (for example Stellar, which ships its own `roro.stellar.shizuku`
 * API instead of the `rikka.shizuku` one) can be added later as one more implementation
 * without touching the audio parsing or the UI.
 */
interface PrivilegeBackend {

    val displayName: String

    val state: StateFlow<PrivilegeState>

    /** True when an app that can provide this backend is installed. */
    fun isInstalled(): Boolean

    /** Ask the backend to show its permission dialog. Result arrives via [state]. */
    fun requestPermission()

    /** Start listening for the binder and (re)bind the privileged user service. */
    fun connect()

    /** Drop listeners, unbind and tear the user service down. */
    fun release()

    /**
     * Run a shell command with the privileged identity.
     *
     * @throws IllegalStateException when the backend is not [PrivilegeState.Ready].
     */
    fun exec(command: String): String

    /**
     * Run the fixed audio-probe command set and return the trimmed dumps, already split
     * by the section markers in [com.audioprobe.audio.DumpTrimmer].
     */
    fun sampleDumps(): String
}
