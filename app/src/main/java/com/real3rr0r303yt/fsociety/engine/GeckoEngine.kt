package com.real3rr0r303yt.fsociety.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import java.io.File
import kotlin.coroutines.resume

/**
 * Owns the single process-wide [GeckoRuntime] (GeckoView allows only one per process).
 *
 * Proxy + hardening prefs are applied twice, belt and braces:
 *  1. `user.js` inside a throw-away profile directory passed with `-profile`.
 *  2. A GeckoView config YAML passed via [GeckoRuntimeSettings.Builder.configFilePath].
 * The profile directory is wiped on every cold start and on exit.
 */
object GeckoEngine {

    private const val TAG = "fsociety/Gecko"

    @Volatile private var runtime: GeckoRuntime? = null

    private fun profileDir(ctx: Context) = File(ctx.noBackupFilesDir, "gecko-volatile-profile")
    private fun configFile(ctx: Context) = File(ctx.noBackupFilesDir, "geckoview-config.yaml")

    /**
     * Every pref here exists to keep traffic inside Tor or to shrink the fingerprint.
     * Grouped so a reviewer can audit each leak vector independently.
     */
    fun hardenedPrefs(socksHost: String, socksPort: Int): Map<String, Any> = linkedMapOf(
        // ── Route EVERYTHING through the local Tor SOCKS5 proxy ─────────────────────
        "network.proxy.type" to 1,                       // manual proxy config
        "network.proxy.socks" to socksHost,
        "network.proxy.socks_port" to socksPort,
        "network.proxy.socks_version" to 5,
        "network.proxy.http" to "",                      // no HTTP/SSL proxies → only SOCKS is used
        "network.proxy.ssl" to "",
        "network.proxy.no_proxies_on" to "",             // never bypass, not even localhost
        "network.proxy.allow_hijacking_localhost" to true,
        "network.proxy.failover_direct" to false,        // proxy down ⇒ fail closed, never go direct
        "network.proxy.allow_bypass" to false,

        // ── DNS leak prevention ─────────────────────────────────────────────────────
        "network.proxy.socks_remote_dns" to true,        // resolve hostnames at the exit
        "network.proxy.socks5_remote_dns" to true,       // Gecko ≥128 split this pref per version
        "network.trr.mode" to 5,                         // DoH off: DNS only via SOCKS remote resolution
        "network.dns.native_https_query" to false,       // Android native HTTPS-RR lookups bypass the proxy
        "network.dns.disablePrefetch" to true,
        "network.dns.disablePrefetchFromHTTPS" to true,
        "network.dns.blockDotOnion" to false,            // allow .onion (resolved by Tor, never locally)
        "network.predictor.enabled" to false,
        "network.prefetch-next" to false,
        "network.http.speculative-parallel-limit" to 0,
        "browser.places.speculativeConnect.enabled" to false,
        "network.connectivity-service.enabled" to false, // does its own DNS lookups
        "network.captive-portal-service.enabled" to false,
        "network.http.http3.enable" to false,            // QUIC/UDP cannot traverse SOCKS
        "network.http.altsvc.enabled" to false,

        // ── WebRTC / media leaks ────────────────────────────────────────────────────
        "media.peerconnection.enabled" to false,         // kills STUN/ICE local + public IP leaks
        "media.peerconnection.ice.no_host" to true,
        "media.peerconnection.ice.default_address_only" to true,
        "media.peerconnection.ice.proxy_only" to true,
        "media.navigator.enabled" to false,
        "media.webspeech.synth.enabled" to false,

        // ── HTTPS-Only ──────────────────────────────────────────────────────────────
        "dom.security.https_only_mode" to true,
        "dom.security.https_only_mode_pbm" to true,
        "dom.security.https_only_mode.upgrade_onion" to false, // onion services are already E2E encrypted
        "dom.security.https_only_mode_send_http_background_request" to false,

        // ── Fingerprinting / canvas ─────────────────────────────────────────────────
        "privacy.resistFingerprinting" to true,          // canvas prompts, UA/timezone/screen spoofing
        "privacy.resistFingerprinting.block_mozAddonManager" to true,
        "privacy.fingerprintingProtection" to true,      // no Builder API in GeckoView; set via pref
        "privacy.fingerprintingProtection.pbmode" to true,
        "privacy.firstparty.isolate" to true,
        "privacy.partition.network_state" to true,
        "webgl.disabled" to true,
        "dom.webgpu.enabled" to false,
        "dom.battery.enabled" to false,
        "dom.gamepad.enabled" to false,
        "dom.vr.enabled" to false,
        "device.sensors.enabled" to false,
        "geo.enabled" to false,
        "beacon.enabled" to false,
        "network.http.referer.XOriginTrimmingPolicy" to 2,

        // ── Zero-log: nothing persisted ─────────────────────────────────────────────
        "browser.privatebrowsing.autostart" to true,
        "browser.cache.disk.enable" to false,
        "browser.cache.offline.enable" to false,
        "browser.sessionstore.resume_from_crash" to false,
        "places.history.enabled" to false,
        "browser.formfill.enable" to false,
        "signon.rememberSignons" to false,

        // ── No phoning home ─────────────────────────────────────────────────────────
        "toolkit.telemetry.enabled" to false,
        "datareporting.healthreport.uploadEnabled" to false,
        "datareporting.policy.dataSubmissionEnabled" to false,
        "browser.safebrowsing.malware.enabled" to false,
        "browser.safebrowsing.phishing.enabled" to false,
        "browser.safebrowsing.downloads.enabled" to false,
        "app.normandy.enabled" to false,
        "extensions.update.enabled" to false,
    )

    /** Returns the runtime, creating it on first call. Must only be called once Tor is ready. */
    @Synchronized
    fun obtain(ctx: Context, socksHost: String, socksPort: Int): GeckoRuntime {
        runtime?.let { return it }
        val app = ctx.applicationContext

        val prefs = hardenedPrefs(socksHost, socksPort)
        val profile = profileDir(app).apply { deleteRecursively(); mkdirs() }
        File(profile, "user.js").writeText(prefs.toUserJs())
        val config = configFile(app).apply { writeText(prefs.toGeckoViewYaml()) }
        Log.i(TAG, "wrote ${prefs.size} hardened prefs (socks5://$socksHost:$socksPort) to ${profile.absolutePath}")

        val contentBlocking = ContentBlocking.Settings.Builder()
            .antiTracking(ContentBlocking.AntiTracking.STRICT)
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
            .strictSocialTrackingProtection(true)
            .safeBrowsing(ContentBlocking.SafeBrowsing.NONE) // Safe Browsing would ping Google
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .queryParameterStrippingEnabled(true)
            .queryParameterStrippingPrivateBrowsingEnabled(true)
            .build()

        val settings = GeckoRuntimeSettings.Builder()
            .arguments(arrayOf("-profile", profile.absolutePath))
            .configFilePath(config.absolutePath)
            .allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY)
            .globalPrivacyControlEnabled(true)
            .contentBlocking(contentBlocking)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .aboutConfigEnabled(false)
            .build()

        Log.i(TAG, "creating GeckoRuntime")
        return GeckoRuntime.create(app, settings).also { rt ->
            rt.delegate = GeckoRuntime.Delegate { Log.e(TAG, "GeckoRuntime shut down unexpectedly") }
            runtime = rt
            Log.i(TAG, "GeckoRuntime created")
        }
    }

    /** Clears every cookie, cache, storage bucket, permission and site setting Gecko holds. */
    suspend fun wipe(ctx: Context) {
        runtime?.let { rt ->
            withTimeoutOrNull(5_000) { rt.storageController.clearData(StorageController.ClearFlags.ALL).await() }
        }
        profileDir(ctx.applicationContext).deleteRecursively()
        configFile(ctx.applicationContext).delete()
    }

    /** Cold-start hygiene: remove anything a crash or force-stop may have left behind. */
    fun purgeLeftovers(ctx: Context) {
        if (runtime != null) return
        profileDir(ctx.applicationContext).deleteRecursively()
        configFile(ctx.applicationContext).delete()
        ctx.applicationContext.cacheDir.deleteRecursively()
    }
}

private suspend fun <T> GeckoResult<T>.await(): T? = suspendCancellableCoroutine { cont ->
    accept({ cont.resume(it) }, { cont.resume(null) })
}

private fun Map<String, Any>.toUserJs(): String =
    entries.joinToString("\n", postfix = "\n") { (k, v) -> "user_pref(\"$k\", ${v.jsLiteral()});" }

private fun Map<String, Any>.toGeckoViewYaml(): String =
    entries.joinToString("\n", prefix = "prefs:\n", postfix = "\n") { (k, v) -> "  $k: ${v.jsLiteral()}" }

private fun Any.jsLiteral(): String = when (this) {
    is String -> "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    else -> toString()
}
