package com.real3rr0r303yt.fsociety

import android.app.Application
import com.real3rr0r303yt.fsociety.engine.GeckoEngine
import com.real3rr0r303yt.fsociety.engine.TabManager
import com.real3rr0r303yt.fsociety.tor.TorManager

/** Process-scoped singletons: Tor and the Gecko runtime outlive Activity recreation. */
class FsocietyApp : Application() {

    lateinit var tor: TorManager
        private set

    private var tabManager: TabManager? = null

    override fun onCreate() {
        super.onCreate()
        GeckoEngine.purgeLeftovers(this)
        tor = TorManager(this)
    }

    /** Lazily boots Gecko — only ever called once Tor reports a live SOCKS port. */
    fun browser(socksHost: String, socksPort: Int): TabManager =
        tabManager ?: TabManager(GeckoEngine.obtain(this, socksHost, socksPort)) { tor.status.value.ready }
            .also { it.newTab(); tabManager = it }

    /** Zero-log teardown: close tabs, clear all Gecko storage, stop Tor, delete the profile. */
    suspend fun wipeEverything() {
        tabManager?.closeAll()
        GeckoEngine.wipe(this)
        tor.stop()
        cacheDir.deleteRecursively()
    }
}
