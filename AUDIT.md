# Outshake — Audit & Review Log

A living, append-only record of code audits, findings, fixes and deferred risks for
Outshake. It exists so that future sessions (human or AI agent) can resume work
without re-deriving context. Read this file first before proposing changes.

## Conventions

- **Entries are dated (UTC+10, Asia/Vladivostok) and listed newest-first.** Never rewrite
  history; append a new entry and, if a deferred item is resolved, mark it resolved
  in the new entry (leave the original as-is).
- **Finding IDs are stable.** `F<n>` = fixed in that entry; `D<n>` = deferred/open.
  Reference them by ID in later entries (e.g. "resolves D5").
- **Source paths and line numbers refer to the commit named in the entry.** Lines drift;
  the commit SHA is the anchor.
- **Test counts are JUnit methods**, not assertions. Always state skips separately.
- **Be honest about verification scope.** Distinguish: pure JVM unit tests → local
  `ss-server` transport tests → on-device (Honor MagicOS 10 / Android 16) checks.
  The sandbox has no KVM, so emulator runs are not possible; device checks are done
  by the owner manually.
- **Never paste keys, passwords, `ssconf://` URLs or unredacted logs into this file.**

## Standing project facts

- Native Kotlin Android client, package `com.outshake`, minSdk 24, compile/targetSdk 34,
  Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24, JDK 17. No native `.so` libraries.
- Transport: own Shadowsocks AEAD implementation (BouncyCastle) for TCP + UDP (SIP007),
  own userspace `Tun2SocksEngine` (IPv4 only). Not the Outline SDK Go engine.
- Ciphers: chacha20-ietf-poly1305, aes-256-gcm, aes-128-gcm. Anything else is rejected.
- Prefix: overwrites the head of the TCP salt (Outline `PrefixSaltGenerator` semantics).
- Owner's daily-driver device: Honor, MagicOS 10 (Android 16 base). Debug-signed builds
  installed via `adb install -r`; the debug certificate must stay the same to preserve
  profiles (SHA-256 `6d79a3d0…f969d6`, see 2026-09-13 entry).
- Decision policy (from the original spec): prefer a small, correct client for
  `ss://` + `ssconf://` + prefix + shake over broad transport compatibility. No tunnel
  rewrites without a packet-level test harness first (see July 10 regression).

---

## 2026-09-14 — Character-led courtyard redesign

**Base:** beautified `6df4171`. UI/mascot/audio scope only; source and local build
verified. Remote publication and GitHub CI must be confirmed separately.

### Changes

- Replaced the power-ring/two-frame-sprite home screen with a scrollable
  character-led courtyard, original articulated native Canvas pigeon,
  six explicit service-state presentations, warm day/night resources, and
  descriptive connect/disconnect/import actions.
- Added independent head/neck, eyes/lids, wings, feet and tail choreography:
  hungry strut, peck/chew/dance, transition anticipation, error droop, and a
  bounded success hop/scatter. No copied game assets or external asset loading.
- Added original reproducible synthesized coos/seed ticks, shared async sample
  playback with expiry/mute/lifecycle gating, sound preference and reduced motion.
  Existing haptic patterns and shake detector/cooldown remain unchanged.
- Rebuilt onboarding with the same original character, a VPN-free demo,
  scrollable content, restored step state and notification-denial history.
  Updated the launcher icon; removed superseded raster mascot and MP3 assets.
- Changed “You're protected” to “VPN connected.” No tunnel-health or security
  guarantee is implied. Profile edits are blocked during active/transitional
  connections. MainActivity state collection is lifecycle-bound; offscreen,
  unfocused and paused scenes stop animating and stop pending/active screen audio.
- Fixed pre-existing theme API27 navigation-bar attributes by moving them to
  version-qualified resources. VersionCode is now 2 / `1.1.0-courtyard`.
- Added lint and native preview/test-report artifact uploads to Android CI.

### Verification performed

- JDK 17.0.20.1, Gradle 8.7, Android platform/build tools 34.
- `testDebugUnitTest`: **131 methods, 125 passed, 6 explicitly skipped, 0
  failures/errors**. The six skipped tests require opt-in local `ss-server`.
- `assembleDebug`: success, approximately 9.3 MiB, local debug signing only.
- `lintDebug`: **0 errors, 53 warnings**. Remaining warnings include inherited
  wake-lock/battery-policy and dependency/unused-resource/style warnings; no
  blanket lint baseline or error suppression was added.
- Eight pure rig tests, eighteen audio-policy/queue tests, four state-map tests,
  five Robolectric activity tests, and three native graphics/layout tests.
  Real MainActivity/OnboardingActivity create/recreate and state/import/demo
  behavior were exercised at API28, without a physical VPN/sensor connection.
- Native Skia renders reviewed for all six day/night states, actual AppCompat
  home/onboarding/settings, small-screen large-text scrolling, and sampled
  animation poses. Deterministic GIF exported from the native rig.
- Audio reproducibility, duration, zero-ended envelopes and no-clipping checks
  pass. No claim of physical-device listening or haptic QA.
- `git diff` confirms **no changes to `vpn/`, `transport/`, `config/`, manifest,
  backup rules or Haptics.kt** from the beautified base.

### Remaining release checks

Honor/MagicOS device smoothness, battery usage, quiet modes, physical shake,
actual VPN consent/traffic and all existing D1–D14 transport risks remain
unverified/unchanged. A sandbox debug APK is not proven compatible with the
installed signing certificate. Do not uninstall or clear profiles to bypass a
signature mismatch. See `COURTYARD.md` for the device checklist.

---

## 2026-09-13 — Source audit, config hardening, documentation overhaul

**Commit:** `6cdd504` (pushed to `main`, fast-forward from `5db54ed`).
**Trigger:** owner reported ~2 months of stable daily use on the July build (`a34ecbd`)
and asked for (a) upstream Outline changes worth adopting, (b) an independent audit,
(c) documentation polish. Audit performed by GPT-6 Astra subagent; orchestration and
upstream research by the main agent.

### Upstream Outline review (July 10 → Sept 13, 2026)

Nothing that requires adoption in Outshake.

- `outline-apps`: 12 commits, mostly packaging/toolchain — API 36 / cordova-android 15.1 /
  AGP 8.10.1 / Gradle 8.14.2 (Aug 31, [358321a](https://github.com/OutlineFoundation/outline-apps/commit/358321a635de24e0955506e3fa2239bff37d6ec3));
  16 KB native-lib alignment + R8 (Aug 31, [a8ef172](https://github.com/OutlineFoundation/outline-apps/commit/a8ef172c90ad94dc703bca975f3baf46ed7dbfda));
  Capacitor Android shell (Jul 29). These are Cordova/Capacitor app concerns, not
  applicable to a native Kotlin client. No new Android client *release* tag was cut.
- `outline-sdk`: Scorecard workflow (Jul 30); `x/v0.2.1` adds QUIC v1/v2 selection to the
  `fetch` CLI tool (Sep 9, [release](https://github.com/OutlineFoundation/outline-sdk/releases/tag/x/v0.2.1)).
  Not a Shadowsocks wire-protocol change. `v0.1.0-rc1` (Jun 23, pre-baseline) has
  packet-relay idle deadlines and IPv4-mapped-IPv6 DNS fixes — useful *reference* for
  D5/D6 below, not a drop-in patch.
- **Documentation correction to our earlier claims:** Outline's YAML config documents
  independent `udp.prefix` and separate TCP/UDP endpoints
  ([config docs](https://developer.getoutline.org/vpn/management/config/)). Only *static
  URL* prefixes are TCP-only. Prefix guidance: ≤16 bytes, shortest possible
  ([prefixing docs](https://developer.getoutline.org/vpn/advanced/prefixing)). Dynamic
  key format minimums: `ss://` line ≥1.8.1, JSON ≥1.8.0, YAML ≥1.15.0
  ([dynamic keys](https://developer.getoutline.org/vpn/management/dynamic-access-keys/)).
  Our July report's "prefix is TCP-only in Outline" was wrong as a general statement.

### Fixed findings

| ID | Severity | What was wrong | Fix | Where |
|---|---|---|---|---|
| F1 | High | Prefix could be as long as the salt → constant salt per password with nonces restarting at zero; AEAD key/nonce reuse. | New `PrefixPolicy`: keep ≥16 random salt bytes. Max 16-byte prefix for ChaCha20/AES-256; **non-empty prefix rejected for AES-128** (16-byte salt). Enforced at import, saved-profile client construction and encryptor. | `config/PrefixPolicy.kt`, `transport/ShadowsocksStream.kt` |
| F2 | High/Med | Parser silently flattened `tcpudp` graphs to the TCP branch (dropping UDP endpoint/secret/prefix), coerced non-string types, truncated ports, ignored unknown fields, accepted duplicate keys. Static URL prefix `%C2%A8` decoded to 2 bytes instead of Outline's 1 byte (`A8`). | Strict documented-subset parsing: TCP/UDP must match on host/port/cipher/secret; UDP prefix, wrappers, `first-supported`, unknown fields, alias ambiguity, duplicate keys, wrong types all **fail loudly**. Canonical URL-prefix decode = UTF-8 → Latin-1 bytes, with raw-byte fallback only when input is not valid UTF-8. SIP008 still uses index 0. | `config/ConfigParser.kt` |
| F3 | Medium | `DynamicFetcher` read unbounded bodies, followed platform-default redirects, echoed raw exception text (could include password-bearing YAML lines). | HTTPS-only incl. redirects, no userinfo, ≤3 redirects, 256 KiB body cap, caching disabled, 15 s connect/read + 30 s elapsed budget, `finally` disconnect, SafeConstructor YAML with alias/depth/codepoint limits, redacted error messages. | `config/DynamicFetcher.kt` |
| F4 | Medium | `allowBackup=true` while prefs hold raw `ss://`/`ssconf://` keys and passwords. | `allowBackup=false` + explicit `backup_rules.xml` / `data_extraction_rules.xml` exclusions; keystore patterns in `.gitignore`. Does not erase pre-existing backups; not encryption. | `AndroidManifest.xml`, `res/xml/*` |
| F5 | Med/Low | Shake said "VPN activated" on *enqueue*, not success; VPN notification said "connected" before probe/TUN; shake toggle didn't check VPN consent; `VIBRATE` permission missing. | Request-worded feedback ("VPN connection requested"), neutral service notification, consent check in `ConnectionManager.toggle`, shake failure caught, `VIBRATE` declared. | `shake/ShakeService.kt`, `vpn/ConnectionManager.kt`, `vpn/OutshakeVpnService.kt` |
| F6 | Medium | Lint: `java.util.Base64` (API 26) used with minSdk 24; unguarded API-27 nav-bar theme attr. | Use bundled BouncyCastle Base64; move nav-bar attr to `values-v27`. Lint 0 errors. | `config/ConfigParser.kt`, `res/values*/themes.xml` |

### Deferred findings (open as of this entry)

These have credible source-level failure paths but were **not** changed because fixing
them properly means substantive tunnel/lifecycle work, and the July 10 regression showed
what happens when that is done without a packet-level harness. Paths refer to `6cdd504`.

| ID | Severity | Summary | Location | Recommended approach |
|---|---|---|---|---|
| D1 | High | `startTunnel` spawns an unmanaged Thread; `stopTunnel`/`onDestroy`/`onRevoke` don't invalidate/join it; `reconnect` starts another sharing `engine`/`tun`. A late worker can re-establish or report Connected after stop/revoke. | `vpn/OutshakeVpnService.kt` ~71–103, 119–173, 240–266 | Generation-scoped resource ownership + serialized lifecycle mutations; tests for delayed-probe / revoke / new-connect interleavings. A Boolean flag is not enough. |
| D2 | High | `registerNetworkCallback` (NOT_VPN filter is correct and must stay) observes *all* matching networks; no default-path selection or `Network.bindSocket`. Losing the tracked network clears its ID so the next one is "first" and doesn't reconnect; rapid changes are ignored rather than coalesced. | `vpn/OutshakeVpnService.kt` ~175–225, `vpn/ReconnectPolicy.kt` ~45–81 | Deterministic candidate selection, real socket binding, coalesced pending events. Keep the self-VPN exclusion from `a34ecbd`. |
| D3 | High | Custom TCP: ACK arg unused; no receive-window/MSS handling, no retransmit queue; SYN retry replaces session; out-of-order data just re-ACKed; FIN closes whole upstream socket (no half-close). | `vpn/Tun2SocksEngine.kt` ~111–141, 149–162, 183–258 | Build a packet-level harness (loss/reorder/dup/wrap/FIN/zero-window) *first*, then incremental fixes or evaluate a mature stack. |
| D4 | High | `pending` buffers unbounded; blocking upstream writes while holding the session lock on the single TUN reader; socket leaks on connect failure; `close` removes by key not identity; no session cap. | `vpn/Tun2SocksEngine.kt` ~48–53, 165–179, 219–258 | Bounded queues/limits, identity-safe close, injectable sockets + stress tests. |
| D5 | High | DNS-over-TCP fallback runs *on* the single UDP-eviction scheduler with no read timeout → a server that accepts but never replies hangs eviction forever. | `vpn/Tun2SocksEngine.kt` ~52, 69–75, 335–350, 370–402 | Separate bounded fallback workers; read deadlines; close failed-handshake sockets. |
| D6 | Med/High | `UdpNatTable.getOrCreate` can allocate a losing factory result; expiry then `UdpSession.close` double-remove by key can delete a newer replacement; DNS fallback keyed by txn ID only, armed after send. | `vpn/UdpNatTable.kt` ~19–44, `vpn/Tun2SocksEngine.kt` ~273–307, 321–363 | Identity-aware removal, close-loser semantics, destination-aware DNS pending keys. |
| D7 | Medium | Header reads precede min-length checks; no fragment reassembly/checksum/ICMP; IPv6-origin UDP replies blindly copied as 4 address bytes; `PacketBuilder` has no MTU bound. | `vpn/IpPacket.kt`, `vpn/PacketBuilder.kt` ~50–76, engine ~87–121 | Validate frame boundary first; reject unsupported families/fragments explicitly; MTU boundary tests. |
| D8 | Medium | No AEAD replay cache (UDP accepts identical authenticated packets); `readChunk` returns null on *any* EOF while reading the encrypted length (partial frame looks like clean EOF); no explicit nonce-wrap fail-closed. | `transport/ShadowsocksStream.kt` ~59–106, `transport/ShadowsocksUdpCodec.kt` ~46–57 | Bounded key-scoped replay state; distinguish clean EOF from truncation. |
| D9 | Medium | `protect()` failures logged and ignored (should fail closed); TUN reader exits silently on error → stale "Connected". | `vpn/OutshakeVpnService.kt` ~106–115, engine ~87–108 | Fail-closed protection; reader death must notify service. Reproduce on device before changing I/O model. |
| D10 | High (limitation, not a demonstrated leak) | IPv4-only routes/DNS; own package excluded from VPN; no app-managed kill switch. Android blocks unconfigured families by default, so missing `::/0` is *not* by itself an IPv6 leak — but unverified on device. | `vpn/OutshakeVpnService.kt` ~126–135 | Packet-capture verification on Honor (IPv6, no-network, disconnect). Do **not** add `allowFamily(AF_INET6)` casually. Evaluate Android always-on/lockdown separately. |
| D11 | Medium | TCP port probe doesn't authenticate SS → wrong/rotated password can show Connected. Start-failure path calls `onError` then immediately `onDisconnected` (error vanishes). Dynamic retry saves the refreshed profile *before* the retry probe succeeds. Selecting/refreshing a profile doesn't replace the running tunnel. | service ~83–115, 240–247; `vpn/ConnectRetry.kt`; `ui/MainActivity.kt` ~182–206 | Keep service state distinct from transport health; preserve errors in UI; define explicit apply/refresh semantics. |
| D12 | Medium | Shake: listener-registration failure still shows "active" notification; sensitivity read once; wall-clock not monotonic time; `ShakeService.start` can throw outside BootReceiver's try; consent-denial doesn't re-render the switch; manager state is process-local. | `shake/ShakeService.kt` ~52–67, `shake/ShakeDetector.kt` ~25–58, `shake/BootReceiver.kt`, `ui/MainActivity.kt` ~34–40 | Validate OEM background starts, no-sensor, force-stop, doze, sticky restart on device before changing. |
| D13 | Medium | Plaintext SharedPreferences; unguarded JSON parse; unsynchronized read-modify-write in `ProfileStore`; data classes have secret-bearing `toString`; import field not excluded from autofill/screenshots; engine logs include destinations. | `store/ProfileStore.kt` ~14–38, 77–106; `config/Model.kt`; `res/layout/activity_import.xml` | Safe schema parsing, serialized mutations, redacted `toString`, intentional Keystore migration with rollback tests. |
| D14 | Low/Med | versionCode 1 / 1.0 never bumped; no release signing/shrinking configured; Gradle wrapper lacks distribution checksum; `notify.yml` uses floating action tag + dispatch PAT. | `app/build.gradle.kts`, `gradle/wrapper/`, `.github/workflows/notify.yml` | Version intentionally; pin supply-chain inputs; audit PAT scope. Don't bump deps just to silence warnings. |

### Compatibility changes introduced (user-visible)

- Prefix > 16 bytes now rejected; any non-empty AES-128 prefix rejected.
- UDP prefixes, distinct TCP/UDP endpoints/secrets, `first-supported`, WebSocket/TLS
  wrappers, `dial`, plugins, unknown fields, YAML collection aliases, duplicate keys →
  explicit `ConfigException` instead of silent flattening.
- Static-key prefixes: **re-import** to get corrected decoding (saved profiles are not
  migrated). Saved unsafe prefixes fail at transport creation, not at app start.
- Dynamic refresh with the stricter parser: a failed fetch/parse leaves the previous
  saved profile unchanged.

### Verification

| Run | Methods | Passed | Skipped | Failed |
|---|---:|---:|---:|---:|
| Baseline `a34ecbd` | 61 | 61 | 0 | 0 |
| `6cdd504` ordinary (`testDebugUnitTest`) | 92 | 86 | 6 | 0 |
| `6cdd504` with `-PliveTransportTests=true` (needs `/usr/bin/ss-server`) | 92 | 92 | 0 | 0 |

`assembleDebug` OK. `lintDebug` 0 errors / 36 warnings. Independently re-run by the
orchestrator after the subagent finished: same result.

Live tests cover TCP HTTP (ChaCha20/AES-256, ±prefix) and UDP echo (ChaCha20/AES-256)
through shadowsocks-libev — **codec-level only**. Not covered: full TUN, QUIC, DNS
fallback, NAT concurrency, TLS validation, sensors, backup, service restarts, Honor.

APKs: previous `outshake-debug.apk` SHA-256 `c087768e…c67b` (9,377,752 B);
candidate `outshake-review-debug.apk` SHA-256 `de599965…6db63` (9,436,328 B).
Both signed with debug cert SHA-256 `6d79a3d099e0b055d9b9d01045c8f25c0879fb6534332acea922d3c43af969d6`.

### Device acceptance checklist (owner, pending)

1. `apksigner verify --print-certs` on both; `adb install -r`; confirm profiles survive.
2. Connect/disconnect via UI, shake, tile; deny/grant/revoke VPN consent.
3. DNS, plain HTTPS, large download/upload, long-lived stream, UDP/QUIC app.
4. Wi-Fi only → mobile only → both → Wi-Fi↔mobile handoff → rapid flaps → captive portal.
5. Stop/revoke *during* a delayed connect (D1 probe — expected risk, not a pass/fail assertion).
6. Background/screen-off/doze shake; force-stop; process kill; reboot with boot-connect on/off.
7. IPv6 blocking + disconnect behavior with packet capture (D10).

### Suggested next work, in priority order

1. **D5** (DNS fallback blocking eviction) — small, contained, high impact on stalls.
2. **D1** (worker generations) — moderate; unlocks safe D2 work.
3. **D11** (honest status + error persistence) — UI-visible, low risk.
4. **D3/D4** — only after building a packet-level harness. Consider evaluating a mature
   userspace stack vs. incremental fixes; decide with data, not by rewrite.
5. **D13** Keystore migration — needs a rollback plan; do after the above.

---

## 2026-07-10 — Reconnect-loop regression fix (baseline for 2 months of daily use)

**Commit:** `a34ecbd`. Model: Claude Fable 5.

**Bug:** on-device, toggling on showed Connected then flapped Reconnecting↔Connected every
few hundred ms. **Root cause:** `registerDefaultNetworkCallback` fired for the VPN's own
TUN network → reconnect → TUN drops → underlying network becomes default → fires again.
**Fix:** filtered `NetworkRequest` with `NET_CAPABILITY_NOT_VPN` + `INTERNET`; pure
`ReconnectPolicy` reconnects only on genuine change to a *different* underlying network;
secondary safety net: ≥3 s between attempts, give up with error after 4 rapid changes;
`setUnderlyingNetworks` for accounting. 8 new tests (61 total). Confirmed working on Honor
by the owner; stable through 2026-09-13.

**Lesson recorded:** tunnel/lifecycle changes must have a regression test that models the
Android callback behavior, not just the pure decision logic.

---

## 2026-07-09 → 2026-07-10 — Initial build and feature rounds (summary)

All by Claude Fable 5 subagents unless noted. Each round was JVM-tested and, from round 2,
verified against a real local `ss-server`; none was emulator-run (no KVM).

| Commit | Round | What shipped |
|---|---|---|
| `63ae055` | v1 | Skeleton, config model, `ss://` (SIP002 + legacy), `ssconf://` → ss-line/JSON/SIP008/YAML graph, prefix-as-salt-head, BouncyCastle AEAD, userspace tun2socks (TCP + DNS-over-TCP), 3 screens, shake (opt-in, 7 s cooldown). 20 tests. |
| `18ced3b` | QS tile + live traffic | `OutshakeTileService` reusing `ConnectionManager.toggle()`; `ShadowsocksLiveTrafficTest` against shadowsocks-libev, both ciphers ±prefix. 27 tests. |
| `42f613f` | UX | Shake default ON, cooldown 5 s, two synthesized shaker OGGs (on/off) via SoundPool on notification stream, `ShakeService` foreground service for background shake (no wakelock, START_STICKY, BootReceiver), toast feedback. 32 tests. Owner confirmed background shake + toast work on MagicOS 10. |
| `d9ace78` | UDP + reliability | SIP007 UDP codec + `UdpNatTable` (60 s idle), UDP DNS with 1.5 s DNS-over-TCP fallback, auto-reconnect on network change (**this introduced the loop fixed in `a34ecbd`**), process-death recovery, connect-on-boot (default off), one-shot ssconf refresh-and-retry on failed connect. UDP live-tested both ciphers. 48 tests. |
| `2b4106e` | Design | Single warm-neutral theme (no dark mode), large ON/OFF switch replacing connect button (state-driven, `bindingSwitch` guard), "Vibrate on toggle" (default ON; 1 tick on, 2 ticks off) via `Haptics`. 53 tests. |
| `a34ecbd` | Fix | Reconnect loop (see entry above). 61 tests. |

Original spec is preserved at the owner's side as `outshake_spec.md` (not in repo).
