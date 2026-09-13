# Outshake

A small, Android-native Kotlin Shadowsocks VPN client for personal use. It accepts
a **subset** of Outline access keys and configurations; it is not the official
Outline client and does not embed Outline's tunnel engine.

See [AUDIT.md](AUDIT.md) for the dated audit/review log (findings, deferred risks,
verification history). Read it before proposing changes.

## What it does

- Imports static `ss://` and dynamic HTTPS-backed `ssconf://` profiles.
- Implements Shadowsocks AEAD TCP and UDP using `chacha20-ietf-poly1305`,
  `aes-256-gcm`, or `aes-128-gcm`.
- Relays IPv4 app traffic through a small userspace TCP/UDP engine. UDP has an
  idle-expiring endpoint table; DNS has a UDP-to-TCP fallback attempt.
- Offers a Material3 DayNight theme (dark mode supported), profile cards, a
  large circular connect button, a first-run onboarding flow (welcome →
  notifications → battery-optimization exemption → shake intro) and a Quick
  Settings tile. Shake-to-toggle is **on by default**, with a five-second
  cooldown and optional haptics. Existing preferences are preserved.
- Attempts reconnects on non-VPN network changes and sticky-service recovery.
  Connect-on-boot is optional and off by default.

**Status is not a health check.** “Connected” means the service established a
local TUN interface; the initial check opens a TCP socket to the proxy server.
It does not authenticate the Shadowsocks password or test DNS, UDP, or Internet
access. The tile mirrors that service state. Shake sounds/haptics and “connection
requested” messages acknowledge intent, not success.

## Install or upgrade without losing profiles

1. Keep the previous working APK and retain your original keys privately.
2. **Check the signing certificate before upgrading.** Android requires the same
   application ID (`com.outshake`) and compatible signing key. A different
   machine's debug build will usually have a different debug certificate.
3. Stop the VPN before upgrading. Install over the existing app:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. Open Outshake, select a profile, and connect in the app once to grant Android's
   VPN consent. Grant notification permission if you want its status messages.
   Add the Outshake tile using your system's Quick Settings editor.

Do **not** uninstall or clear storage to work around `INSTALL_FAILED_UPDATE_INCOMPATIBLE`:
that deletes profiles/settings. Obtain a build signed with your original key
instead. Same-signature updates preserve local preferences; the review makes no
profile-schema migration. Keep the old APK for rollback, but remember that
Android also checks version codes. Both this review and the original 1.0 build
use versionCode 1; future releases may require a different rollback procedure.

This review tightens config validation. Existing safe profiles remain stored;
unsafe saved prefixes are refused when creating a transport. Re-import a static
key to apply corrected URL-prefix decoding. A dynamic refresh applies the stricter
parser; a failed fetch/parse leaves the previous saved profile unchanged. A
connect-time retry saves a valid newly fetched config before retrying reachability,
even if that retry fails. Never change the cipher or prefix arbitrarily: ask the
server administrator for a compatible key.

## Build and tests

Requirements: **JDK 17**, Android SDK command-line tools, accepted SDK licenses,
platform `android-34`, build-tools `34.0.0`. Platform-tools (`adb`) are needed only
for device installation/testing. The wrapper uses Gradle 8.7, AGP 8.5.2 and Kotlin
1.9.24. Declared minSdk is 24; compileSdk/targetSdk remain 34.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
# If required, install SDK packages using your command-line tools:
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
sdkmanager --licenses

./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The ordinary JVM suite includes parser, crypto, fetch-policy, feedback and pure
reconnect/NAT decision tests. Android API calls are stubbed; these tests do **not**
validate sensors, notifications, consent, socket protection, routing or lifecycle.
Six local-server test methods are skipped unless explicitly enabled:

```sh
# Optional: install shadowsocks-libev (ss-server) from your OS package manager.
# Tests look in /usr/bin/ss-server and /usr/local/bin/ss-server.
./gradlew :app:testDebugUnitTest -PliveTransportTests=true --rerun-tasks
```

Those six tests send TCP HTTP traffic (with/without prefixes) and UDP echo
datagrams through a local `ss-server`, using the ChaCha20 and AES-256 codecs.
They bypass `Tun2SocksEngine` and Android entirely: **not full-TUN, QUIC,
DNS-fallback, emulator or Honor-device tests**. AES-128 has JVM round-trip tests,
not a live-server test. A missing `ss-server` produces explicit skips, not proof
of interoperability. Inspect `app/build/test-results/testDebugUnitTest/` for
failures and skipped methods.

Debug builds are debuggable and intended for local testing. Release signing is
**not configured**; `assembleRelease` is not a signed distributable release.
Back up your signing key securely outside this repository. Never publish a
keystore, signing password, real profile or private dynamic-config URL.

```sh
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs previous.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

## Exact supported configuration subset

| Input | Behavior |
|---|---|
| SIP002 `ss://base64(method:password)@host:port[/][?prefix=...][#name]` | Standard/URL-safe base64, padded/unpadded; literal URL-decoded `method:password` also accepted |
| Legacy `ss://base64(method:password@host:port)[#name]` | Supported, without query parameters |
| Static query fields | Only `prefix` and the inert `outline` marker; duplicates, plugins and other query fields fail |
| Dynamic body containing one `ss://` line | Supported; multiple nonblank lines fail |
| Flat JSON/YAML object | One host (`server` or `host`) and port (`server_port` or `port`), OR `endpoint: host:port`; one cipher (`method` or `cipher`), one password (`password` or `secret`); optional TCP `prefix` |
| SIP008 `{servers:[...]}` | Uses **the first entry**, not the first supported entry; failure there does not select another |
| `transport: {$type: shadowsocks, ...}` | Same endpoint/key for TCP and UDP; no shared prefix |
| `transport: {$type: tcpudp, tcp: {...}, udp: {...}}` | Both branches required; same host, port, cipher and password; optional TCP prefix; UDP prefix must be absent/empty |
| IPv6 proxy endpoint, e.g. `[::1]:8388` | Address parsing supported; distinct from IPv6 app-traffic support |
| Separate TCP/UDP endpoints or secrets, UDP prefixes | **Rejected**, not silently replaced by TCP settings |
| `first-supported`, lists of transport alternatives, WebSockets/WSS, TLS wrappers, `dial`, plugins, Shadowsocks 2022 or other ciphers | **Unsupported** |

Field names are case-sensitive; `$type`/`type` are aliases, but specifying both
is ambiguous and rejected. Endpoint/cipher/password aliases likewise cannot be
combined, even with equal values. Ports must be integers (or integer strings)
from 1 to 65535. Credential fields must be strings: quote numeric-looking or
boolean-looking YAML passwords. Unknown fields fail.

Allowed non-transport metadata is limited to `id`, `remarks`, `name` on a leaf;
`name`, `description` beside a root `transport`; and `version`, `bytes_used`,
`bytes_remaining` beside `servers`. These fields are ignored. Display names
come from the key fragment/name hint or host:port. JSON is read with the safe
YAML loader, **not** a separate strict RFC-JSON validator. Duplicate keys,
collection aliases, excessive nesting and oversized bodies are rejected.

Example representable transport graph (illustrative credentials only):

```yaml
transport:
  $type: tcpudp
  tcp:
    $type: shadowsocks
    endpoint: example.com:443
    cipher: chacha20-ietf-poly1305
    secret: "replace-with-your-real-secret"
    prefix: "POST "
  udp:
    $type: shadowsocks
    endpoint: example.com:443
    cipher: chacha20-ietf-poly1305
    secret: "replace-with-your-real-secret"
```

### Prefix semantics and safety limits

Prefixes overwrite the **start of the TCP salt**; they are not a separately
prepended header. Static URL prefixes follow Outline's encoding of Latin-1 byte
characters through UTF-8 URL escaping: `%C2%A8` becomes the single byte `A8`.
Legacy raw percent bytes are retained only if they are not valid UTF-8. This
resolves an ambiguity in favor of the documented Outline form. Dynamic prefix
strings must contain only characters U+0000–U+00FF.

Outshake retains **at least 16 random salt bytes**: at most 16 prefix bytes for
ChaCha20/AES-256, and no non-empty prefix for AES-128's 16-byte salt. This is a
deliberately conservative local policy. Full-salt prefixes reuse the same
subkey/nonces across connections and are unsafe. Use the shortest server-approved
prefix; it does not guarantee censorship evasion.

Outline's **URL** prefixes are TCP-only, but its richer dynamic config supports
independent UDP prefixes and endpoints. Outshake does not implement those UDP
prefixes. See [Outline prefixing](https://developer.getoutline.org/vpn/advanced/prefixing)
and [configuration definitions](https://developer.getoutline.org/vpn/management/config/)
(reviewed 2026-09-13).

### Dynamic fetch and refresh

`ssconf://host/path?query#name` becomes HTTPS, excluding the fragment. Normal
platform TLS/hostname validation is used. Only HTTPS redirects are followed
(including cross-host redirects), at most three; URL user info is rejected.
Bodies are capped at 256 KiB, with 15-second connect/read timeouts and a
30-second elapsed-budget check between blocking operations. This is not a hard
30-second cancellation deadline: a blocking operation can overrun it.
The app disables connection caching and requests no-cache/no-store.

The fetched transport is stored locally with the original secret URL. There is
no scheduled polling, ETag refresh protocol or server-pushed rotation. Refresh
is manual, or once when the **initial TCP reachability probe** fails for a
dynamic profile. A rotated password on a still-reachable server does not trigger
that retry. Refreshing or selecting a profile does not replace the running
tunnel: disconnect and reconnect to apply it.

## Security and known limitations

- **Custom TCP engine:** no retransmission queue, congestion control, receive-window
  tracking or robust half-close. SYN retries can replace sessions; FIN handling,
  races during close and unbounded pending data need further work. A local TUN
  link is not a guarantee of lossless delivery. Large downloads and long-lived
  streams can stall.
- **UDP/DNS:** session count and worker count are unbounded. Expiry/replacement
  races and a blocking DNS fallback on the eviction scheduler remain. Packet
  parsing lacks complete length/fragment/checksum validation; large datagrams,
  fragments and IPv6-origin replies are not comprehensively handled.
- **Cryptography:** authentication tags are checked, but there is no cross-session
  replay cache. Partial encrypted length frames can be mistaken for clean EOF.
  This review is not an independent cryptographic certification.
- **Lifecycle/reconnect:** the `NOT_VPN` callback filter is retained to avoid the
  prior self-triggered reconnect loop. It observes all matching networks rather
  than selecting/validating one default path. In-flight start/reconnect workers
  are not generation-scoped; disconnect/revoke can race them. No exactly-once
  handoff or guaranteed process-death recovery is claimed.
- **IPv6/privacy:** the engine processes IPv4 only. With no IPv6 address/route/DNS
  and no `allowFamily(AF_INET6)`, Android documents IPv6 as blocked by default,
  not implicitly tunneled; this must still be verified on the device. Outshake's
  own app traffic is excluded from the VPN, including config retrieval and proxy
  hostname resolution. There is no app-managed kill switch: when the tunnel is
  down, ordinary traffic may use the underlying network. Android Always-on /
  “Block connections without VPN” behavior has not been validated here.
  ([Android VpnService.Builder](https://developer.android.com/reference/android/net/VpnService.Builder))
- **Credentials:** profiles, passwords and raw keys are plaintext in private
  SharedPreferences (not encrypted using Android Keystore). This review disables
  app backup and excludes preferences from cloud/device-transfer rules. That
  does not erase older backups or protect a rooted/compromised/debuggable device,
  clipboard, keyboard or screenshot. Preserve original keys securely yourself.
- **Background shake:** a partial wake lock keeps the CPU awake while the
  accelerometer is registered, but sensor delivery, foreground-service starts,
  sticky restart and boot behavior still depend on Android/OEM restrictions.
  There is no guarantee while force-stopped, power-restricted or after consent
  is revoked. Sensitivity changes are pushed live into the running detector.

## Troubleshooting, including Honor / MagicOS

- **Shake does nothing:** first connect from the app and grant VPN consent. Check
  an active profile, the shake toggle, notification permission and the five-second
  cooldown. Sensitivity changes apply live; if in doubt, toggle shake off/on. Confirm the device
  has an accelerometer. A service notification does not prove sensor delivery.
- **Works foreground, not asleep:** inspect Outshake's per-app battery/background
  execution settings. On Honor, look for App launch/background-running controls;
  labels vary by MagicOS version. Allow background running only if desired and
  measure battery impact. Test awake/background/screen-off separately. Force-stop
  requires reopening the app; a foreground service cannot override every OEM rule.
- **Connected but no traffic:** try an ordinary HTTPS page and DNS-dependent
  browsing; test UDP separately. Check cipher/password/endpoint and manually
  refresh a dynamic profile. A successful TCP port probe is insufficient.
- **Network switch fails:** disconnect, wait, and reconnect once from the app.
  Do not repeatedly shake during transitions. Preserve the known-working APK
  and compare Wi-Fi-only, mobile-only and handoff behavior before replacing it.
- **Import/refresh rejected:** consult the exact matrix. UDP prefixes, wrappers,
  separate endpoints and unsafe salt prefixes are explicit incompatibilities,
  not credentials that should be “fixed” by stripping unknown fields.

For a safe bug report, provide build/commit, Android/MagicOS version, network
type, timestamp, state transition and reproduction steps—**never access keys**.
If local logs are necessary, collect a short filtered window on your own machine:

```sh
adb logcat -d -v threadtime -s OutshakeVpn:W Tun2Socks:W '*:S' > outshake-local-log.txt
```

Review and redact it **before sharing**. Existing transport logs can contain
server/destination IPs, hostnames, ports and exception text. Remove all `ss://`,
`ssconf://`, full HTTPS config URLs (paths/queries can be bearer tokens), passwords
and profile names. Do not post unfiltered logcat, screenshots of imported keys,
preference exports, bugreports or keystores.

### Upstream review boundary — 2026-09-13

This is a native Kotlin project, not the Cordova/Capacitor Outline application.
Outline's August 31 [Android API-36/toolchain commit](https://github.com/OutlineFoundation/outline-apps/commit/358321a635de24e0955506e3fa2239bff37d6ec3)
and [native alignment/R8 work](https://github.com/OutlineFoundation/outline-apps/commit/a8ef172c90ad94dc703bca975f3baf46ed7dbfda)
are not drop-in updates for this client. The September SDK
[x/v0.2.1 fetch-tool QUIC change](https://github.com/OutlineFoundation/outline-sdk/releases/tag/x/v0.2.1)
is not a new Shadowsocks VPN wire protocol. These are upstream commits/tool
releases, not evidence of a newly shipped Outline Android release. No SDK,
dependency or target-API upgrade is bundled into this conservative review.
