package com.real3rr0r303yt.fsociety.tor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.torproject.jni.TorService
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

enum class TorState { OFF, STARTING, ON, STOPPING, ERROR }

/**
 * Where the SOCKS5 proxy comes from.
 *  - [EMBEDDED]: tor daemon runs inside this app (Guardian Project tor-android / TorService).
 *  - [ORBOT]:    an external Orbot instance already listening on 127.0.0.1:9050.
 */
enum class TorMode { EMBEDDED, ORBOT }

data class TorStatus(
    val mode: TorMode = TorMode.EMBEDDED,
    val state: TorState = TorState.OFF,
    val bootstrap: Int = 0,
    val summary: String = "idle",
    val socksHost: String = TorManager.SOCKS_HOST,
    val socksPort: Int = -1,
    /** Exit relay IP as seen by check.torproject.org, fetched through the proxy. */
    val exitIp: String? = null,
    /** True only after check.torproject.org confirmed the request arrived via Tor. */
    val verified: Boolean = false,
) {
    val ready: Boolean get() = state == TorState.ON && socksPort > 0
}

/**
 * Launches / supervises the local Tor SOCKS5 proxy and exposes its state as a [StateFlow].
 *
 * The browser engine is never created before [TorStatus.ready] is true, and the engine is
 * configured with `network.proxy.failover_direct=false`, so if Tor dies later every request
 * fails closed instead of leaking out over the clearnet.
 */
class TorManager(context: Context) {

    companion object {
        private const val TAG = "fsociety/Tor"
        const val SOCKS_HOST = "127.0.0.1"
        const val ORBOT_SOCKS_PORT = 9050
        private const val BOOTSTRAP_TIMEOUT_MS = 120_000L
        private const val CHECK_URL = "https://check.torproject.org/api/ip"
        private val PROGRESS = Regex("""PROGRESS=(\d+)""")
        private val SUMMARY = Regex("""SUMMARY="([^"]*)"""")
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(TorStatus())
    val status: StateFlow<TorStatus> = _status.asStateFlow()

    @Volatile private var service: TorService? = null
    private var bound = false
    private var receiverRegistered = false
    private var bootstrapJob: Job? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val torStatus = intent.getStringExtra(TorService.EXTRA_STATUS)
            Log.i(TAG, "TorService status: $torStatus")
            when (torStatus) {
                TorService.STATUS_STARTING -> _status.update { it.copy(state = TorState.STARTING) }
                TorService.STATUS_ON -> onEmbeddedTorOn()
                TorService.STATUS_STOPPING -> _status.update { it.copy(state = TorState.STOPPING, verified = false) }
                TorService.STATUS_OFF -> _status.update {
                    it.copy(state = TorState.OFF, bootstrap = 0, summary = "tor stopped", exitIp = null, verified = false)
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TorService.LocalBinder).service
            Log.i(TAG, "bound to TorService")
            startBootstrapPolling()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            Log.e(TAG, "TorService disconnected")
            _status.update { it.copy(state = TorState.ERROR, summary = "tor service died", verified = false) }
        }
    }

    /** Idempotent: safe to call from every Activity.onCreate. */
    fun start(mode: TorMode = TorMode.EMBEDDED) {
        if (_status.value.state == TorState.ON || _status.value.state == TorState.STARTING) return
        // Retry after ERROR/OFF: drop the old binding so TorService is really started again.
        if (bound || receiverRegistered) stop()
        Log.i(TAG, "starting tor (mode=$mode)")
        _status.value = TorStatus(mode = mode, state = TorState.STARTING, summary = "starting")
        when (mode) {
            TorMode.EMBEDDED -> startEmbedded()
            TorMode.ORBOT -> attachToOrbot()
        }
    }

    private fun startEmbedded() {
        if (!receiverRegistered) {
            LocalBroadcastManager.getInstance(app)
                .registerReceiver(statusReceiver, IntentFilter(TorService.ACTION_STATUS))
            receiverRegistered = true
        }
        if (!bound) {
            bound = app.bindService(Intent(app, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                Log.e(TAG, "bindService(TorService) returned false")
                _status.update { it.copy(state = TorState.ERROR, summary = "unable to bind TorService") }
            }
        }
    }

    private fun attachToOrbot() = scope.launch {
        _status.update { it.copy(summary = "probing orbot socks5 @ $SOCKS_HOST:$ORBOT_SOCKS_PORT") }
        if (!probeSocks5(SOCKS_HOST, ORBOT_SOCKS_PORT)) {
            Log.e(TAG, "no SOCKS5 proxy answering on $SOCKS_HOST:$ORBOT_SOCKS_PORT")
            _status.update { it.copy(state = TorState.ERROR, summary = "no SOCKS5 proxy on :$ORBOT_SOCKS_PORT - start Orbot") }
            return@launch
        }
        _status.update { it.copy(state = TorState.ON, bootstrap = 100, summary = "orbot proxy up", socksPort = ORBOT_SOCKS_PORT) }
        verifyExit()
    }

    private fun onEmbeddedTorOn() {
        if (_status.value.state == TorState.ON) return
        bootstrapJob?.cancel()
        scope.launch {
            // STATUS_ON can race the service binding; give the port a few seconds to show up.
            var port = -1
            for (attempt in 1..10) {
                port = TorService.socksPort.takeIf { it > 0 } ?: socksPortFromControl() ?: -1
                if (port > 0) break
                delay(500)
            }
            if (port <= 0) {
                Log.e(TAG, "tor is ON but the SOCKS port is unknown")
                _status.update { it.copy(state = TorState.ERROR, summary = "tor is up but SOCKS port is unknown") }
                return@launch
            }
            Log.i(TAG, "circuit established - SOCKS5 proxy at $SOCKS_HOST:$port")
            _status.update {
                it.copy(state = TorState.ON, bootstrap = 100, summary = "circuit established", socksPort = port)
            }
            verifyExit()
        }
    }

    /**
     * Polls `GETINFO status/bootstrap-phase` until the first circuit is built. Also the fallback
     * for a missed STATUS_ON broadcast (100% for 5 s ⇒ treat as ON) and the bootstrap timeout.
     */
    private fun startBootstrapPolling() {
        bootstrapJob?.cancel()
        bootstrapJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var doneSince = 0L
            var lastLogged = -1
            while (isActive && _status.value.state == TorState.STARTING) {
                service?.getInfo("status/bootstrap-phase")?.let { phase ->
                    val pct = PROGRESS.find(phase)?.groupValues?.get(1)?.toIntOrNull()
                    val summary = SUMMARY.find(phase)?.groupValues?.get(1)
                    if (pct != null && pct != lastLogged) {
                        Log.i(TAG, "bootstrap $pct% - $summary")
                        lastLogged = pct
                    }
                    _status.update {
                        it.copy(bootstrap = pct ?: it.bootstrap, summary = summary ?: it.summary)
                    }
                    if (pct == 100) {
                        if (doneSince == 0L) doneSince = System.currentTimeMillis()
                        if (System.currentTimeMillis() - doneSince > 5_000) {
                            Log.w(TAG, "bootstrap at 100% but no STATUS_ON broadcast - continuing anyway")
                            onEmbeddedTorOn()
                            return@launch
                        }
                    }
                }
                if (System.currentTimeMillis() - startedAt > BOOTSTRAP_TIMEOUT_MS) {
                    Log.e(TAG, "bootstrap timed out at ${_status.value.bootstrap}%")
                    _status.update {
                        it.copy(state = TorState.ERROR, summary = "bootstrap timeout at ${it.bootstrap}% - check network")
                    }
                    return@launch
                }
                delay(500)
            }
        }
    }

    /** `net/listeners/socks` → `"127.0.0.1:9050"` */
    private fun socksPortFromControl(): Int? =
        service?.getInfo("net/listeners/socks")
            ?.trim('"', ' ')
            ?.substringAfterLast(':')
            ?.trim('"')
            ?.toIntOrNull()

    /**
     * Asks check.torproject.org (through the proxy, with remote DNS) which IP it sees.
     * Android's HttpURLConnection hands SOCKS proxies an *unresolved* host, so the hostname
     * lookup itself happens on the Tor exit — no local DNS query is made.
     */
    fun verifyExit() = scope.launch {
        val port = _status.value.socksPort
        if (port <= 0) return@launch
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getByName(SOCKS_HOST), port))
        try {
            val conn = URL(CHECK_URL).openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 10; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val ip = json.optString("IP", "?")
            val isTor = json.optBoolean("IsTor", false)
            Log.i(TAG, "exit check: IsTor=$isTor")
            _status.update { it.copy(exitIp = ip, verified = isTor) }
        } catch (e: Exception) { // IOException or a malformed JSON reply - never crash the app
            Log.w(TAG, "exit verification failed: ${e.message}")
            _status.update { it.copy(exitIp = null, verified = false) }
        }
    }

    /** SIGNAL NEWNYM: new circuits for new connections, then re-check the exit IP. */
    fun newIdentity() = scope.launch {
        val control = service?.torControlConnection ?: return@launch
        try {
            control.signal("NEWNYM")
            _status.update { it.copy(exitIp = null, verified = false, summary = "NEWNYM sent - rotating circuit") }
            delay(3_000) // tor rate-limits NEWNYM; give it a moment to build fresh circuits
            verifyExit()
        } catch (e: IOException) {
            Log.w(TAG, "NEWNYM failed: ${e.message}")
        }
    }

    fun stop() {
        Log.i(TAG, "stopping tor")
        bootstrapJob?.cancel()
        if (bound) {
            runCatching { app.unbindService(connection) }
            bound = false
        }
        if (receiverRegistered) {
            LocalBroadcastManager.getInstance(app).unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        service = null
        _status.value = TorStatus(mode = _status.value.mode)
    }
}

/**
 * Minimal RFC 1928 greeting: offer "no auth" and expect `05 00` back.
 * Confirms *something* speaks SOCKS5 on the port; [TorManager.verifyExit] confirms it is Tor.
 */
fun probeSocks5(host: String, port: Int, timeoutMs: Int = 3_000): Boolean = try {
    Socket().use { s ->
        s.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs)
        s.soTimeout = timeoutMs
        s.getOutputStream().apply { write(byteArrayOf(0x05, 0x01, 0x00)); flush() }
        val reply = ByteArray(2)
        s.getInputStream().read(reply) == 2 && reply[0] == 0x05.toByte() && reply[1] == 0x00.toByte()
    }
} catch (e: IOException) {
    false
}
