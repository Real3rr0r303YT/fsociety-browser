package com.real3rr0r303yt.fsociety.engine

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Base64
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebRequestError

/** One in-memory tab. Nothing about it is ever written to disk. */
class BrowserTab(val id: Int, val session: GeckoSession) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var secure by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    /** Session is open on the runtime — only then may it be attached to a GeckoView. */
    var opened by mutableStateOf(false)
    /** Gecko has composited a first frame; until then the UI keeps the loading console on top. */
    var painted by mutableStateOf(false)
    /** Last blocked or failed navigation, shown under the prompt; cleared by the next page start. */
    var lastError by mutableStateOf<String?>(null)
    internal var restarts = 0
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
        private const val TAG = "fsociety/Tabs"
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
    /** Tab created by window.open / target=_blank; focused once Gecko has opened it. */
    private var pendingFocus: BrowserTab? = null

    fun newTab(url: String? = HOME): BrowserTab {
        val tab = createTab()
        tab.session.open(runtime)
        tab.opened = tab.session.isOpen
        Log.i(TAG, "tab ${tab.id}: session opened=${tab.opened}")
        tabs += tab
        activeIndex = tabs.lastIndex
        url?.let {
            Log.i(TAG, "tab ${tab.id}: initial load $it")
            tab.session.loadUri(it)
        }
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
        val target = resolveInput(raw)
        tab.lastError = null
        // Only the scheme is logged: zero-log means no browsing history in logcat either.
        Log.i(TAG, "tab ${tab.id}: navigate (${Uri.parse(target).scheme}, onion=${Uri.parse(target).host?.endsWith(".onion") == true})")
        tab.session.loadUri(target)
    }

    private fun restart(tab: BrowserTab) {
        if (++tab.restarts > 3) {
            Log.e(TAG, "tab ${tab.id}: crashed ${tab.restarts} times - giving up")
            tab.painted = true // reveal the (dead) view instead of an endless loading console
            return
        }
        tab.painted = false
        if (tab.session.isOpen) tab.session.close()
        tab.session.open(runtime)
        tab.opened = tab.session.isOpen
        tab.session.loadUri(tab.url.ifBlank { HOME })
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
                // Our own data: error page must not replace the URL the user tried to open.
                if (!url.startsWith("data:")) {
                    tab.url = url
                    tab.lastError = null
                }
                tab.loading = true
                tab.opened = true
                if (pendingFocus === tab) {
                    pendingFocus = null
                    tabs.indexOf(tab).takeIf { it >= 0 }?.let { activeIndex = it }
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.loading = false
                tab.painted = true // also covers error pages that never report a first paint
                if (!success) Log.w(TAG, "tab ${tab.id}: page load did not complete")
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

            override fun onFirstComposite(session: GeckoSession) {
                Log.i(TAG, "tab ${tab.id}: first composite")
                tab.painted = true
            }

            override fun onFirstContentfulPaint(session: GeckoSession) {
                tab.painted = true
            }

            // Content process died: reopen the session instead of leaving a dead black surface.
            override fun onCrash(session: GeckoSession) {
                Log.e(TAG, "tab ${tab.id}: content process crashed - restarting session")
                restart(tab)
            }

            override fun onKill(session: GeckoSession) {
                Log.w(TAG, "tab ${tab.id}: content process killed by the system - restarting session")
                restart(tab)
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
                val ready = torReady()
                val allowed = ready && scheme in ALLOWED_SCHEMES
                if (!allowed) {
                    val why = if (!ready) "tor circuit not ready" else "scheme '$scheme' not allowed"
                    Log.w(TAG, "tab ${tab.id}: blocked navigation - $why")
                    tab.lastError = "blocked: $why"
                }
                return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

            // Network failures (DNS at the exit, proxy refused, timeout, TLS …) get a terminal-style
            // error page and a red line under the prompt instead of a silent blank page.
            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String> {
                val name = errorName(error.code)
                Log.w(TAG, "tab ${tab.id}: load error $name (category=${error.category}, code=${error.code})")
                tab.lastError = "error: $name"
                tab.loading = false
                tab.painted = true
                return GeckoResult.fromValue(errorPage(name, uri))
            }

            // target=_blank / window.open → new in-memory tab (Gecko opens the returned session).
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // Gecko opens the returned session itself; don't attach it to the view before that.
                val tab = createTab()
                tabs += tab
                pendingFocus = tab
                return GeckoResult.fromValue(tab.session)
            }
        }

        // ETP / ad blocking: count what Gecko blocked (category only, never the URL).
        contentBlockingDelegate = object : ContentBlocking.Delegate {
            override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
                BlockStats.record(event)
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

private fun errorName(code: Int): String = when (code) {
    WebRequestError.ERROR_UNKNOWN_HOST -> "UNKNOWN_HOST (exit relay could not resolve the name)"
    WebRequestError.ERROR_PROXY_CONNECTION_REFUSED -> "PROXY_CONNECTION_REFUSED (tor SOCKS5 not reachable)"
    WebRequestError.ERROR_UNKNOWN_PROXY_HOST -> "UNKNOWN_PROXY_HOST"
    WebRequestError.ERROR_CONNECTION_REFUSED -> "CONNECTION_REFUSED"
    WebRequestError.ERROR_NET_TIMEOUT -> "NET_TIMEOUT (slow circuit - retry or [newnym])"
    WebRequestError.ERROR_NET_RESET -> "NET_RESET"
    WebRequestError.ERROR_OFFLINE -> "OFFLINE"
    WebRequestError.ERROR_SECURITY_BAD_CERT -> "BAD_CERT"
    WebRequestError.ERROR_SECURITY_SSL -> "SSL_ERROR"
    else -> "0x" + Integer.toHexString(code)
}

private fun errorPage(name: String, uri: String?): String {
    val safeUri = (uri ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val html = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{background:#000;color:#00ff66;font:14px monospace;padding:16px;word-break:break-all}
        .r{color:#ff1a3c}.d{color:#00a845}</style></head><body>
        <p>fsociety@root:~# curl --socks5-hostname 127.0.0.1 $safeUri</p>
        <p class="r">[!] load failed: $name</p>
        <p class="d">[*] traffic stayed inside tor - nothing went out directly.</p>
        <p class="d">[*] try again, or hit [newnym] for a fresh circuit.</p>
        </body></html>
    """.trimIndent()
    return "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
}
