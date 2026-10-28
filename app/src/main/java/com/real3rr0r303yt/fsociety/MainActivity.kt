package com.real3rr0r303yt.fsociety

import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.real3rr0r303yt.fsociety.engine.BlockStats
import com.real3rr0r303yt.fsociety.engine.BrowserTab
import com.real3rr0r303yt.fsociety.engine.TabManager
import com.real3rr0r303yt.fsociety.tor.TorManager
import com.real3rr0r303yt.fsociety.tor.TorMode
import com.real3rr0r303yt.fsociety.tor.TorState
import com.real3rr0r303yt.fsociety.tor.TorStatus
import com.real3rr0r303yt.fsociety.ui.theme.Fs
import com.real3rr0r303yt.fsociety.ui.theme.FsocietyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView

private const val TAG = "fsociety/UI"

class MainActivity : ComponentActivity() {

    private val app get() = application as FsocietyApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, blank thumbnail in the recents switcher.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        Log.i(TAG, "MainActivity created - starting tor")
        app.tor.start()

        setContent {
            FsocietyTheme {
                BrowserRoot(app = app, onWipeAndExit = ::wipeAndExit)
            }
        }
    }

    /** Burn everything: clear Gecko storage, stop Tor, and kill the process so RAM goes too. */
    private fun wipeAndExit() {
        lifecycleScope.launch {
            app.wipeEverything()
            finishAndRemoveTask()
            Process.killProcess(Process.myPid())
        }
    }

    override fun onDestroy() {
        // Swiped away from recents: best effort. Leftovers are purged on next cold start anyway.
        if (isFinishing && !isChangingConfigurations) app.tor.stop()
        super.onDestroy()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Root layout
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BrowserRoot(app: FsocietyApp, onWipeAndExit: () -> Unit) {
    val tor = app.tor
    val status by tor.status.collectAsStateWithLifecycle()
    val engine by app.engine.collectAsStateWithLifecycle()

    // Gecko is only instantiated after Tor has a live SOCKS port — nothing can load before.
    LaunchedEffect(status.ready, engine) {
        if (status.ready && engine is EngineState.Idle) app.startEngine(status.socksHost, status.socksPort)
    }

    val browser = (engine as? EngineState.Ready)?.tabs
    val tab = browser?.active

    BackHandler {
        if (tab?.canGoBack == true) tab.session.goBack() else onWipeAndExit()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Fs.Black)
            .systemBarsPadding()
            .imePadding(),
    ) {
        AsciiHeader()
        TorHud(status, engine as? EngineState.Ready)
        PromptBar(currentUrl = tab?.url.orEmpty(), enabled = browser != null) { browser?.load(it) }
        tab?.lastError?.let { err ->
            Text(
                "[!] $err",
                color = Fs.BloodRed, fontFamily = Fs.Mono, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        ActionRow(
            tab = tab,
            status = status,
            onNewTab = { browser?.newTab() },
            onNewIdentity = tor::newIdentity,
            onWipe = onWipeAndExit,
        )
        if (browser != null) TabStrip(browser)
        LoadBar(tab)

        Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, Fs.GhostGreen)) {
            val onRetry = { if (!status.ready) tor.start() else app.resetEngine() }
            // GeckoView is attached only once the proxy is up AND the session is open on the runtime.
            if (status.ready && tab != null && tab.opened) {
                GeckoHost(tab)
                // Gecko needs an attached surface to paint, so the console overlays it until the
                // first frame is composited instead of showing an empty black surface.
                if (!tab.painted) BootConsole(status, engine, onRetry)
            } else {
                BootConsole(status, engine, onRetry)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header + HUD
// ─────────────────────────────────────────────────────────────────────────────

private val ASCII = """
  __                _      _
 / _|___  ___   ___(_) ___| |_ _   _
| |_/ __|/ _ \ / __| |/ _ \ __| | | |
|  _\__ \ (_) | (__| |  __/ |_| |_| |
|_| |___/\___/ \___|_|\___|\__|\__, |
                               |___/
""".trimIndent()

@Composable
private fun AsciiHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(ASCII, color = Fs.NeonGreen, fontFamily = Fs.Mono, fontSize = 8.sp, lineHeight = 9.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text("// onion-routed", color = Fs.DimGreen, fontFamily = Fs.Mono, fontSize = 9.sp)
            Text("// zero-log", color = Fs.DimGreen, fontFamily = Fs.Mono, fontSize = 9.sp)
            Text("// we are fsociety", color = Fs.BloodRed, fontFamily = Fs.Mono, fontSize = 9.sp)
        }
    }
}

@Composable
private fun TorHud(s: TorStatus, engine: EngineState.Ready?) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot",
    )
    val (label, color) = when (s.state) {
        TorState.ON -> (if (s.verified) "TOR::CONNECTED" else "TOR::UNVERIFIED") to (if (s.verified) Fs.NeonGreen else Fs.Amber)
        TorState.STARTING -> "TOR::BOOTSTRAP ${s.bootstrap}%" to Fs.Amber
        TorState.STOPPING -> "TOR::STOPPING" to Fs.Amber
        TorState.OFF -> "TOR::DISCONNECTED" to Fs.BloodRed
        TorState.ERROR -> "TOR::ERROR" to Fs.BloodRed
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Fs.Panel)
            .border(1.dp, Fs.GhostGreen)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("●", color = color, modifier = Modifier.alpha(if (s.state == TorState.ON) 1f else pulse))
        Spacer(Modifier.width(6.dp))
        HudText(label, color, bold = true)
        HudSep()
        HudText("EXIT:${s.exitIp ?: "--.--.--.--"}", if (s.exitIp != null) Fs.NeonGreen else Fs.DimGreen)
        HudSep()
        HudText("SOCKS5:${s.socksHost}:${if (s.socksPort > 0) s.socksPort else "----"}", Fs.DimGreen)
        HudSep()
        HudText(
            "DNS:" + when (s.remoteDns) { true -> "REMOTE"; false -> "PROBE-FAIL"; null -> "..." },
            when (s.remoteDns) { true -> Fs.NeonGreen; false -> Fs.BloodRed; null -> Fs.DimGreen },
        )
        HudSep()
        HudText(s.mode.name, Fs.DimGreen)
    }
    if (engine != null) ProtectionHud(engine, torPort = s.socksPort)
}

/** Second HUD line: what ETP / ad blocking the runtime really runs with, plus live block counts. */
@Composable
private fun ProtectionHud(engine: EngineState.Ready, torPort: Int) {
    val p = engine.protection
    fun flag(on: Boolean) = if (on) "ON" else "OFF"
    fun tint(on: Boolean) = if (on) Fs.NeonGreen else Fs.BloodRed
    Row(
        Modifier
            .fillMaxWidth()
            .background(Fs.Panel)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudText("ETP:${p.etpLevel}", tint(p.etpLevel != "OFF"), bold = true)
        HudSep()
        HudText("ADBLOCK:${flag(p.ads)}", tint(p.ads))
        HudSep()
        HudText("TRACKERS:${flag(p.trackers)}", tint(p.trackers))
        HudSep()
        HudText("FP:${flag(p.fingerprinters)}", tint(p.fingerprinters))
        HudSep()
        HudText("BLOCKED ads:${BlockStats.ads} trk:${BlockStats.trackers} other:${BlockStats.other}", Fs.Amber)
        // The engine's proxy prefs are fixed at creation; a Tor restart on another port breaks routing.
        if (torPort > 0 && torPort != engine.socksPort) {
            HudSep()
            HudText("PORT MISMATCH ${engine.socksPort}!=$torPort - [wipe&exit] + restart", Fs.BloodRed, bold = true)
        }
    }
}

@Composable private fun HudText(t: String, c: Color, bold: Boolean = false) =
    Text(t, color = c, fontFamily = Fs.Mono, fontSize = 11.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

@Composable private fun HudSep() = Text("  │  ", color = Fs.GhostGreen, fontFamily = Fs.Mono, fontSize = 11.sp)

// ─────────────────────────────────────────────────────────────────────────────
// Bash-prompt URL bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PromptBar(currentUrl: String, enabled: Boolean, onSubmit: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var editing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(currentUrl, editing) { if (!editing) field = TextFieldValue(currentUrl) }

    fun submit() {
        val input = field.text.trim()
        editing = false
        focus.clearFocus()
        if (input.isNotEmpty()) onSubmit(input)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("fsociety", color = Fs.BloodRed, fontFamily = Fs.Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("@", color = Fs.DimGreen, fontFamily = Fs.Mono, fontSize = 14.sp)
        Text("root", color = Fs.BloodRed, fontFamily = Fs.Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(":~# ", color = Fs.NeonGreen, fontFamily = Fs.Mono, fontSize = 14.sp)
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Fs.NeonGreen),
            cursorBrush = SolidColor(Fs.NeonGreen),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { submit() }, onDone = { submit() }, onSearch = { submit() }),
            decorationBox = { inner ->
                Box {
                    if (field.text.isEmpty()) {
                        Text(
                            when {
                                !enabled -> "waiting for circuit_"
                                editing && currentUrl.isNotEmpty() -> currentUrl
                                else -> "enter url or search (.onion ok)"
                            },
                            color = Fs.GhostGreen, fontFamily = Fs.Mono, fontSize = 15.sp, maxLines = 1,
                        )
                    }
                    inner()
                }
            },
            // Tapping the prompt starts a fresh command line (current URL stays visible as a hint),
            // so typed input never gets glued onto the old URL.
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { state ->
                    if (state.isFocused && !editing) {
                        editing = true
                        field = TextFieldValue("")
                    } else if (!state.isFocused && editing) {
                        editing = false
                    }
                },
        )
        Text(
            "[go]",
            color = if (enabled) Fs.NeonGreen else Fs.GhostGreen,
            fontFamily = Fs.Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable(enabled = enabled) { submit() }
                .padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Actions, tabs, progress
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActionRow(
    tab: BrowserTab?,
    status: TorStatus,
    onNewTab: () -> Unit,
    onNewIdentity: () -> Unit,
    onWipe: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TermButton("<", enabled = tab?.canGoBack == true) { tab?.session?.goBack() }
        TermButton(">", enabled = tab?.canGoForward == true) { tab?.session?.goForward() }
        TermButton(if (tab?.loading == true) "x" else "↻", enabled = tab != null) {
            if (tab?.loading == true) tab.session.stop() else tab?.session?.reload()
        }
        TermButton("+tab", enabled = tab != null, onClick = onNewTab)
        TermButton("newnym", enabled = status.ready && status.mode == TorMode.EMBEDDED, onClick = onNewIdentity)
        TermButton("wipe&exit", color = Fs.BloodRed, onClick = onWipe)
    }
}

@Composable
private fun TermButton(label: String, enabled: Boolean = true, color: Color = Fs.NeonGreen, onClick: () -> Unit) {
    val c = if (enabled) color else Fs.GhostGreen
    Text(
        "[$label]",
        color = c,
        fontFamily = Fs.Mono,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
    )
}

@Composable
private fun TabStrip(browser: TabManager) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        browser.tabs.forEachIndexed { i, t ->
            val active = i == browser.activeIndex
            val name = t.title.ifBlank { t.url.substringAfter("://").substringBefore('/') }.ifBlank { "new" }.take(18)
            Row(
                Modifier
                    .border(1.dp, if (active) Fs.NeonGreen else Fs.GhostGreen)
                    .clickable { browser.select(i) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("tty$i:$name", color = if (active) Fs.NeonGreen else Fs.DimGreen, fontFamily = Fs.Mono, fontSize = 11.sp)
                Text(" ×", color = Fs.BloodRed, fontFamily = Fs.Mono, fontSize = 11.sp, modifier = Modifier.clickable { browser.close(i) })
            }
        }
    }
}

@Composable
private fun LoadBar(tab: BrowserTab?) {
    if (tab?.loading == true) {
        LinearProgressIndicator(
            progress = { tab.progress / 100f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Fs.NeonGreen,
            trackColor = Fs.Black,
        )
    } else {
        Spacer(Modifier.height(2.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Content: GeckoView or boot console
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GeckoHost(tab: BrowserTab) {
    AndroidView(
        factory = { ctx ->
            Log.i(TAG, "GeckoView created")
            GeckoView(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        },
        update = { view ->
            if (view.session !== tab.session && tab.session.isOpen) {
                try {
                    view.releaseSession()
                    view.setSession(tab.session)
                    Log.i(TAG, "GeckoView bound to tab ${tab.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "binding tab ${tab.id} to GeckoView failed", e)
                }
            }
        },
        onRelease = { view -> view.releaseSession() },
        modifier = Modifier.fillMaxSize(),
    )
}

private val SPINNER = charArrayOf('|', '/', '-', '\\')

@Composable
private fun BootConsole(s: TorStatus, engine: EngineState, onRetry: () -> Unit) {
    var frame by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(120)
            frame++
        }
    }
    val spin = SPINNER[frame % SPINNER.size]
    val dots = ".".repeat(frame / 4 % 4)
    val bar = (s.bootstrap / 5).coerceIn(0, 20).let { "#".repeat(it) + ".".repeat(20 - it) }
    val torDown = s.state == TorState.ERROR || s.state == TorState.OFF

    Column(
        Modifier.fillMaxSize().background(Fs.Black).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BootLine("[*] fsociety-browser :: boot sequence")
        BootLine("[*] mode: ${s.mode.name.lowercase()}  socks5://${s.socksHost}:${if (s.socksPort > 0) s.socksPort else "auto"}")
        BootLine("[*] tor  [$bar] ${s.bootstrap}%")
        BootLine("[>] ${s.summary}", Fs.DimGreen)
        Spacer(Modifier.height(8.dp))
        when {
            torDown -> {
                BootLine("[!] no circuit. all traffic is blocked. nothing leaks.", Fs.BloodRed)
                RetryButton(onRetry)
            }
            !s.ready -> PhaseLine("$spin Initializing Tor Circuit$dots")
            engine is EngineState.Failed -> {
                BootLine("[!] GeckoView failed to start: ${engine.reason}", Fs.BloodRed)
                BootLine("[!] adb logcat -s fsociety/App fsociety/Gecko GeckoView", Fs.DimGreen)
                RetryButton(onRetry)
            }
            engine !is EngineState.Ready -> PhaseLine("$spin Booting GeckoView engine$dots")
            else -> PhaseLine("$spin Opening session -> ${TabManager.HOME}$dots")
        }
    }
}

@Composable
private fun PhaseLine(t: String) =
    Text(t, color = Fs.NeonGreen, fontFamily = Fs.Mono, fontSize = 15.sp, fontWeight = FontWeight.Bold)

@Composable
private fun RetryButton(onRetry: () -> Unit) =
    Text(
        "[retry]",
        color = Fs.NeonGreen, fontFamily = Fs.Mono, fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(onClick = onRetry).padding(top = 8.dp),
    )

@Composable
private fun BootLine(t: String, c: Color = Fs.NeonGreen) =
    Text(t, color = c, fontFamily = Fs.Mono, fontSize = 12.sp)
