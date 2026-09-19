package com.audioprobe.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audioprobe.audio.AudioSnapshot
import com.audioprobe.data.ProbeEngine
import com.audioprobe.priv.PrivilegeState
import com.audioprobe.priv.ShizukuBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProbeViewModel(application: Application) : AndroidViewModel(application) {

    private val backend = ShizukuBackend(application)
    private val engine = ProbeEngine(application, backend)

    val privilege: StateFlow<PrivilegeState> = backend.state

    private val _snapshot = MutableStateFlow<AudioSnapshot?>(null)
    val snapshot: StateFlow<AudioSnapshot?> = _snapshot.asStateFlow()

    private val _running = MutableStateFlow(true)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    init {
        backend.connect()
        viewModelScope.launch {
            while (isActive) {
                if (_running.value) pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollOnce() {
        if (backend.state.value !is PrivilegeState.Ready) return
        try {
            val result = withContext(Dispatchers.IO) { engine.sample() }
            _snapshot.value = result.snapshot
            _latencyMs.value = result.elapsedMs
            _error.value = null
        } catch (t: Throwable) {
            _error.value = t.message ?: t.javaClass.simpleName
        }
    }

    fun toggleRunning() {
        _running.value = !_running.value
        if (_running.value) {
            viewModelScope.launch { pollOnce() }
        }
    }

    fun refreshNow() {
        viewModelScope.launch { pollOnce() }
    }

    fun requestPermission() = backend.requestPermission()

    fun reconnect() = backend.connect()

    override fun onCleared() {
        super.onCleared()
        backend.release()
    }

    private companion object {
        /** The four dumps cost ~330 ms, so 1 s keeps the UI live without spinning. */
        const val POLL_INTERVAL_MS = 1000L
    }
}
