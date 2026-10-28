package com.real3rr0r303yt.fsociety

import android.app.Application
import android.os.Build
import android.util.Log
import com.real3rr0r303yt.fsociety.engine.BlockStats
import com.real3rr0r303yt.fsociety.engine.GeckoEngine
import com.real3rr0r303yt.fsociety.engine.ProtectionStatus
import com.real3rr0r303yt.fsociety.engine.TabManager
import com.real3rr0r303yt.fsociety.tor.TorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Lifecycle of the Gecko engine, rendered by the UI. */
sealed interface EngineState {
    data object Idle : EngineState
    data object Starting : EngineState
    data class Ready(val tabs: TabManager, val protection: ProtectionStatus, val socksPort: Int) : EngineState
    data class Failed(val reason: String) : EngineState
}

/** Process-scoped singletons: Tor and the Gecko runtime outlive Activity recreation. */
class FsocietyApp : Application() {

    companion object {
        private const val TAG = "fsociety/App"
    }

    lateinit var tor: TorManager
        private set

    private val _engine = MutableStateFlow<EngineState>(EngineState.Idle)
    val engine: StateFlow<EngineState> = _engine.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        // GeckoView runs content/GPU work in child processes (":tab0", ":gpu", …) and Android
        // calls Application.onCreate in each of them. Only the main process may touch the
        // profile or start Tor — purging from a child process deletes the live Gecko profile
        // out from under the engine and leaves a black GeckoView.
        if (!isMainProcess()) {
            Log.i(TAG, "child process ${currentProcessName()} - skipping app init")
            return
        }
        GeckoEngine.purgeLeftovers(this)
        tor = TorManager(this)
    }

    /**
     * Boots Gecko and opens the first tab. Only called (on the main thread) once Tor reports a
     * live SOCKS port, so nothing can load before the proxy exists. Idempotent.
     */
    fun startEngine(socksHost: String, socksPort: Int) {
        if (_engine.value is EngineState.Ready || _engine.value is EngineState.Starting) return
        _engine.value = EngineState.Starting
        Log.i(TAG, "starting GeckoView engine via socks5://$socksHost:$socksPort")
        _engine.value = try {
            val runtime = GeckoEngine.obtain(this, socksHost, socksPort)
            val protection = ProtectionStatus.of(runtime)
            Log.i(TAG, "content blocking: $protection")
            val tabs = TabManager(runtime) { tor.status.value.ready }
            tabs.newTab(TabManager.HOME)
            EngineState.Ready(tabs, protection, socksPort)
        } catch (e: Exception) {
            Log.e(TAG, "GeckoView initialization failed", e)
            EngineState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Lets the UI retry after [EngineState.Failed]. A created runtime is reused. */
    fun resetEngine() {
        if (_engine.value is EngineState.Failed) _engine.value = EngineState.Idle
    }

    /** Zero-log teardown: close tabs, clear all Gecko storage, stop Tor, delete the profile. */
    suspend fun wipeEverything() {
        (_engine.value as? EngineState.Ready)?.tabs?.closeAll()
        BlockStats.reset()
        GeckoEngine.wipe(this)
        tor.stop()
        cacheDir.deleteRecursively()
    }

    private fun isMainProcess() = currentProcessName() == packageName

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000').trim() }.getOrDefault(packageName)
        }
}
