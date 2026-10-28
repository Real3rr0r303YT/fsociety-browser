package com.real3rr0r303yt.fsociety.engine

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSessionSettings

/** One in-memory tab. Nothing about it is ever written to disk. */
class BrowserTab(val id: Int, val session: GeckoSession) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var secure by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
}

/**
 * In-memory tab list. Every session is a private-browsing session (cookies / storage live in
 * RAM only) and is closed + wiped on exit.
 *
 * @param torReady checked on every navigation; while it returns false all loads are denied.
 */
class TabManager(
    private val runtime: GeckoRuntime,
    private val torReady: () -> Boolean,
) {
    companion object {
        const val HOME = "https://check.torproject.org/"
        // DuckDuckGo's onion service — search queries never leave the Tor network.
        private const val SEARCH = "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/?q="
        private val ALLOWED_SCHEMES = setOf("http", "https", "about", "blob", "data")

        /** Turns bash-prompt input into a URL: bare words become an onion search. */
        fun resolveInput(raw: String): String {
            val input = raw.trim()
            if (input.isEmpty()) return HOME
            val scheme = Uri.parse(input).scheme?.lowercase()
            return when {
                scheme == "http" || scheme == "https" || input.startsWith("about:") -> input
                ' ' !in input && '.' in input -> "https://$input"
                else -> SEARCH + Uri.encode(input)
            }
        }
    }

    val tabs = mutableStateListOf<BrowserTab>()
    var activeIndex by mutableIntStateOf(0)
        private set
    val active: BrowserTab? get() = tabs.getOrNull(activeIndex)

    private var nextId = 0

    fun newTab(url: String? = HOME): BrowserTab {
        val tab = createTab()
        tab.session.open(runtime)
        tabs += tab
        activeIndex = tabs.lastIndex
        url?.let { tab.session.loadUri(it) }
        return tab
    }

    fun select(index: Int) {
        if (index in tabs.indices) activeIndex = index
    }

    fun close(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        tab.session.close()
        tabs.removeAt(index)
        if (tabs.isEmpty()) newTab() else activeIndex = activeIndex.coerceAtMost(tabs.lastIndex)
    }

    fun closeAll() {
        tabs.forEach { it.session.close() }
        tabs.clear()
        activeIndex = 0
    }

    fun load(raw: String) {
        val tab = active ?: newTab(null)
        tab.session.loadUri(resolveInput(raw))
    }

    private fun createTab(): BrowserTab {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(true)
            .useTrackingProtection(true)
            .allowJavascript(true)
            .suspendMediaWhenInactive(true)
            .build()
        val tab = BrowserTab(nextId++, GeckoSession(settings))
        wireDelegates(tab)
        return tab
    }

    private fun wireDelegates(tab: BrowserTab) = with(tab.session) {
        progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.url = url
                tab.loading = true
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.loading = false
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                tab.progress = progress
            }

            override fun onSecurityChange(session: GeckoSession, securityInfo: GeckoSession.ProgressDelegate.SecurityInformation) {
                tab.secure = securityInfo.isSecure
            }
        }

        contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tab.title = title.orEmpty()
            }
        }

        navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
            }

            // Fail closed: no circuit ⇒ no load. Also block file://, intent://, content://, javascript: etc.
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                val scheme = Uri.parse(request.uri).scheme?.lowercase()
                val allowed = torReady() && scheme in ALLOWED_SCHEMES
                return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

            // target=_blank / window.open → new in-memory tab (Gecko opens the returned session).
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                val tab = createTab()
                tabs += tab
                activeIndex = tabs.lastIndex
                return GeckoResult.fromValue(tab.session)
            }
        }

        // Deny geolocation, camera, mic, notifications, storage-access, DRM … everything.
        permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback,
            ) = callback.reject()

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: ContentPermission,
            ): GeckoResult<Int> = GeckoResult.fromValue(ContentPermission.VALUE_DENY)

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) = callback.reject()
        }
    }
}
