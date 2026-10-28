<div align="center">

```
  __                _      _
 / _|___  ___   ___(_) ___| |_ _   _
| |_/ __|/ _ \ / __| |/ _ \ __| | | |
|  _\__ \ (_) | (__| |  __/ |_| |_| |
|_| |___/\___/ \___|_|\___|\__|\__, |
                               |___/   b r o w s e r
```

**`fsociety@root:~# ./browse --via tor --no-logs --no-leaks`**

A Tor-rerouted, zero-log, hardened privacy browser for Android, built on Mozilla **GeckoView** and **Jetpack Compose**.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-00FF66.svg?style=flat-square&labelColor=000000)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-00FF66?style=flat-square&labelColor=000000)
![Engine](https://img.shields.io/badge/engine-GeckoView-8B0000?style=flat-square&labelColor=000000)
![Network](https://img.shields.io/badge/network-Tor%20SOCKS5-8B0000?style=flat-square&labelColor=000000)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-FFDD00?style=flat-square&logo=buymeacoffee&logoColor=black&labelColor=000000)](https://buymeacoffee.com/real3rr0r303yt)

</div>

---

> *"Hello, friend."*
> The internet watches you. Every DNS query, every WebRTC handshake, every canvas you draw tells a story about who you are.
> fsociety-browser routes everything through the onion and forgets everything when you leave.

## `$ cat features.txt`

| | |
|---|---|
| 🧅 **Tor-only networking** | Embedded `tor` daemon (Guardian Project `tor-android`) or an external **Orbot** proxy at `127.0.0.1:9050`. All Gecko traffic is forced through SOCKS5 with `failover_direct=false`. |
| 🔒 **Fail-closed** | GeckoView isn't created until Tor reports `CIRCUIT_ESTABLISHED`, and every navigation is denied while there's no circuit. If Tor dies, requests fail instead of going out over the clearnet. |
| 🕳 **No DNS leaks** | Remote DNS through SOCKS5 (`socks5_remote_dns`), DoH/TRR off, native HTTPS-RR lookups off, DNS prefetch, predictor and speculative connects off, connectivity/captive-portal probes off. |
| 📡 **No WebRTC leaks** | `media.peerconnection.enabled=false` plus ICE `no_host` and `proxy_only`. |
| 🔐 **HTTPS-Only** | `GeckoRuntimeSettings.HTTPS_ONLY` plus `dom.security.https_only_mode`. `.onion` services are exempt from the upgrade because they're already end-to-end encrypted. |
| 🎭 **Anti-fingerprinting** | `privacy.resistFingerprinting` (canvas extraction prompts, UA/timezone/screen spoofing), fingerprinting protection, first-party isolation, WebGL/WebGPU off, no sensors, battery, gamepad or geolocation. |
| 🧹 **Zero-log** | Every tab is a private-browsing session that lives in RAM only. No disk cache, history, form data or passwords. The Gecko profile is a throw-away directory that's deleted on every cold start. `[wipe&exit]` clears all storage, stops Tor and kills the process. |
| 🛡 **OpSec** | `FLAG_SECURE` (no screenshots, blank recents thumbnail), backups disabled, every web permission denied, Safe Browsing/telemetry off, `file://`, `intent://`, `content://` and `javascript:` navigations blocked. |
| 🖥 **Terminal UI** | Pitch black `#000000`, neon green `#00FF66` and dark-red accents, an ASCII header, a `fsociety@root:~#` prompt URL bar and a live circuit HUD (state, exit node IP, SOCKS port, `NEWNYM`). |

## `$ tree`

```
fsociety-browser/
├── build.gradle.kts / settings.gradle.kts      # Mozilla maven repo + version catalog
├── gradle/libs.versions.toml                   # GeckoView, tor-android, jtorctl, Compose
└── app/src/main/java/com/real3rr0r303yt/fsociety/
    ├── FsocietyApp.kt          # process singletons: TorManager, runtime, wipe-everything
    ├── MainActivity.kt         # Compose terminal layout: header, HUD, prompt, tabs, GeckoView
    ├── tor/TorManager.kt       # TorService/Orbot launcher, bootstrap %, SOCKS5 probe, exit check, NEWNYM
    ├── engine/GeckoEngine.kt   # GeckoRuntime + hardened prefs (proxy, DNS, WebRTC, RFP, HTTPS-Only)
    ├── engine/TabManager.kt    # in-memory private tabs, load gating, permission denial
    └── ui/theme/Theme.kt       # black / neon green / dark red, monospace everything
```

## `$ cat ARCHITECTURE`

```
 ┌──────────────── MainActivity (Compose) ────────────────┐
 │ ASCII header │ HUD ● TOR::CONNECTED │ EXIT │ SOCKS5   │
 │ fsociety@root:~# ____________________________________  │
 │ [<] [>] [↻] [+tab] [newnym] [wipe&exit]   tty0 tty1 …  │
 │ ┌──────────────── GeckoView ─────────────────────────┐ │
 └─┴────────────────────────┬───────────────────────────┴─┘
                            │ GeckoSession (private mode, per tab)
               ┌────────────▼────────────┐
               │ GeckoRuntime            │  user.js + config YAML:
               │  -profile <volatile>    │  proxy.type=1, socks5_remote_dns,
               │  HTTPS_ONLY, RFP, ETP   │  failover_direct=false, webrtc off …
               └────────────┬────────────┘
                            │ SOCKS5 127.0.0.1:<port>   (remote DNS)
               ┌────────────▼────────────┐       ┌─────────────────────┐
               │ TorManager              │──────▶│ check.torproject.org│
               │  TorService (embedded)  │ verify│  IsTor / exit IP    │
               │  or Orbot :9050         │       └─────────────────────┘
               └────────────┬────────────┘
                            ▼
                     🧅 Tor network
```

**Boot sequence:** `Application.onCreate` purges any leftover profile, then `TorManager.start()` binds `TorService`. The HUD polls `GETINFO status/bootstrap-phase` until `STATUS_ON`. Then it reads the real SOCKS port: 9050, or an automatic port if Orbot already holds 9050. Next it writes the hardened prefs for that port, creates the `GeckoRuntime`, opens the first private tab on `check.torproject.org`, and confirms the exit through the proxy.

## `$ ./gradlew assembleDebug`

Requirements: JDK 17 and the Android SDK (compileSdk 36). Min device is Android 8.0 (API 26).

```bash
git clone https://github.com/Real3rr0r303YT/fsociety-browser.git
cd fsociety-browser
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

> **Pin GeckoView.** `gradle/libs.versions.toml` uses `geckoview = "145.+"` so the first build resolves. For reproducible builds, replace it with an exact build from
> <https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/>, then keep it current: GeckoView security fixes ship with every Firefox release.

**Using Orbot instead of the embedded daemon:** call `tor.start(TorMode.ORBOT)` in `MainActivity`. The browser probes `127.0.0.1:9050` with a SOCKS5 handshake and checks with `check.torproject.org` that the proxy really is Tor before the HUD turns green.

## `$ cat THREAT_MODEL`

Be honest with yourself about what this is:

- ✅ It hides your IP from websites and your browsing from your ISP or local network.
- ✅ It's designed so DNS, WebRTC and proxy-failure leaks can't happen, and it leaves nothing on disk.
- ⚠️ **It is not Tor Browser.** Tor Browser's real strength is that every user looks the same. A custom browser has a different fingerprint from that crowd, even with `resistFingerprinting` on. If your safety depends on anonymity, use the official **[Tor Browser for Android](https://www.torproject.org/download/#android)**.
- ⚠️ Tor's own state (guard nodes, cached consensus) is kept in the app's private storage on purpose. Rotating guards on every launch would make you *easier* to deanonymize.
- ⚠️ Alpha software. Audit `GeckoEngine.hardenedPrefs()` yourself, and test with <https://check.torproject.org>, <https://browserleaks.com/webrtc> and <https://dnsleaktest.com>.

Found a leak? See [SECURITY.md](SECURITY.md).

## `$ ./support.sh`

If this project helped you stay invisible, fuel the next commit:

<a href="https://buymeacoffee.com/real3rr0r303yt"><img src="https://img.shields.io/badge/☕%20Buy%20Me%20a%20Coffee-real3rr0r303yt-FFDD00?style=for-the-badge&labelColor=000000" alt="Buy Me a Coffee"></a>

## `$ cat LICENSE`

```
fsociety-browser - Tor-rerouted GeckoView privacy browser
Copyright (C) 2026  Real3rr0r303YT

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```

Full text: [LICENSE](LICENSE) (GNU GPL v3.0).
Third-party: GeckoView is under the [MPL-2.0](https://www.mozilla.org/MPL/2.0/), which is GPL-compatible. `tor-android` and `jtorctl` are under BSD-style licenses.
This project isn't affiliated with the Tor Project, Mozilla, or USA Network's *Mr. Robot*.

<div align="center">

```
fsociety@root:~# exit
[*] wiping session... done.  we were never here.
```

</div>
