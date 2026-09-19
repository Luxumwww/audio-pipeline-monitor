package com.audioprobe.priv

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.audioprobe.BuildConfig
import com.audioprobe.IProbeService
import com.audioprobe.ProbeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Privilege backend built on Shizuku (https://github.com/RikkaApps/Shizuku).
 *
 * Shizuku runs a process with the identity of the ADB shell (uid 2000) when it is
 * started via wireless debugging / a computer, or uid 0 when started with root.
 * It exposes that identity to apps in two ways:
 *
 *  - binder wrapping, to call hidden system-service methods;
 *  - "user services", a service of ours started inside Shizuku's own `app_process`.
 *
 * This app needs to *run shell commands* (`dumpsys`), so it uses the user service.
 * `Shizuku.newProcess` would have been the direct route but it was made private in
 * Shizuku API 13 and is slated for removal in 14; `bindUserService` is the supported
 * replacement.
 */
class ShizukuBackend(private val context: Context) : PrivilegeBackend {

    override val displayName: String = "Shizuku"

    private val _state = MutableStateFlow<PrivilegeState>(PrivilegeState.BackendNotRunning)
    override val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    private var listening = false
    private var bindRequested = false
    private var remoteUid = -1

    @Volatile
    private var service: IProbeService? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ProbeService::class.java.name)
    )
        // Non-daemon: the user service is killed together with this app process, so a
        // finished session never leaves a privileged process behind.
        .daemon(false)
        .processNameSuffix("probe")
        .tag(SERVICE_TAG)
        .version(SERVICE_VERSION)
        .debuggable(BuildConfig.DEBUG)

    private val onBinderReceived = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        bindRequested = false
        refreshState()
    }

    private val onBinderDead = Shizuku.OnBinderDeadListener {
        Log.i(TAG, "Shizuku binder died")
        service = null
        bindRequested = false
        remoteUid = -1
        _state.value = PrivilegeState.BackendNotRunning
    }

    private val onPermissionResult =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            Log.i(TAG, "permission result code=$requestCode grant=$grantResult")
            if (requestCode == PERMISSION_REQUEST_CODE) {
                bindRequested = false
                refreshState()
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val stub = IProbeService.Stub.asInterface(binder)
            service = stub
            remoteUid = try {
                stub?.uid ?: -1
            } catch (t: Throwable) {
                Log.w(TAG, "getUid failed", t)
                -1
            }
            Log.i(TAG, "user service connected, uid=$remoteUid")
            _state.value = PrivilegeState.Ready(remoteUid, safeVersion())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "user service disconnected")
            service = null
            bindRequested = false
            refreshState()
        }
    }

    override fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (t: Throwable) {
        false
    }

    override fun connect() {
        if (!listening) {
            listening = true
            try {
                // Sticky: fires immediately when the binder is already available.
                Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
                Shizuku.addBinderDeadListener(onBinderDead)
                Shizuku.addRequestPermissionResultListener(onPermissionResult)
            } catch (t: Throwable) {
                _state.value = PrivilegeState.Failed(t.message ?: "无法监听 Shizuku")
                return
            }
        }
        refreshState()
    }

    override fun requestPermission() {
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            _state.value = PrivilegeState.Failed(t.message ?: "requestPermission 失败")
        }
    }

    override fun exec(command: String): String {
        val stub = service ?: throw IllegalStateException("特权服务尚未连接")
        return stub.exec(command)
    }

    override fun sampleDumps(): String {
        val stub = service ?: throw IllegalStateException("特权服务尚未连接")

        // A user service left over from an older install of this app does not know the
        // current transaction codes. Instead of failing the transaction it answers with
        // an empty reply, which arrives here as null. Kill it so Shizuku respawns the
        // current version on the next poll.
        val result: String? = try {
            stub.sampleDumps()
        } catch (t: Throwable) {
            Log.w(TAG, "sampleDumps failed", t)
            null
        }
        if (result != null) return result

        resetService()
        throw IllegalStateException("特权服务版本已过期，已请求重启，请稍候重试")
    }

    /** Drops the bound user service so the next [refreshState] starts a fresh one. */
    private fun resetService() {
        service = null
        bindRequested = false
        runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
        refreshState()
    }

    override fun release() {
        if (listening) {
            listening = false
            runCatching { Shizuku.removeBinderReceivedListener(onBinderReceived) }
            runCatching { Shizuku.removeBinderDeadListener(onBinderDead) }
            runCatching { Shizuku.removeRequestPermissionResultListener(onPermissionResult) }
            // remove=true asks the Shizuku server to kill the user service as well.
            runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
        }
        service = null
        bindRequested = false
        remoteUid = -1
    }

    private fun refreshState() {
        if (!safePing()) {
            _state.value = if (isInstalled()) {
                PrivilegeState.BackendNotRunning
            } else {
                PrivilegeState.BackendMissing
            }
            return
        }

        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
        if (!granted) {
            _state.value = PrivilegeState.PermissionRequired
            return
        }

        if (service != null) {
            _state.value = PrivilegeState.Ready(remoteUid, safeVersion())
            return
        }

        _state.value = PrivilegeState.Connecting
        if (!bindRequested) {
            bindRequested = true
            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            } catch (t: Throwable) {
                bindRequested = false
                _state.value = PrivilegeState.Failed(
                    "启动特权服务失败: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
    }

    private fun safePing(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    private fun safeVersion(): Int = try {
        Shizuku.getVersion()
    } catch (t: Throwable) {
        -1
    }

    companion object {
        private const val TAG = "AudioProbe/Shizuku"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_REQUEST_CODE = 4210
        private const val SERVICE_TAG = "audio-probe"

        /**
         * Bump whenever ProbeService or IProbeService changes, so Shizuku respawns the
         * user service instead of handing back one that speaks an older AIDL.
         */
        private const val SERVICE_VERSION = 3
    }
}
