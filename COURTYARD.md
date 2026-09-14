# Coo's courtyard

The character is the connection control, not decoration around one. This is an
original, native Android illustration and animation rig, with no copied assets
from Pigeon Pop or another game.

## Interaction contract

| Real state | Coo | Primary action |
|---|---|---|
| Disconnected | Hungry strut, neck bob, curious stare, begging | Feed Coo: connect VPN |
| Connecting | Searching eyes and wing anticipation | Disabled while connecting |
| Connected | Peck, chew, blink, little dance, occasional soft coo | Let Coo rest: disconnect |
| Reconnecting | Searching, concerned expression | Disabled while reconnecting |
| Disconnecting | Feather fluff, settling back to the roost | Disabled while disconnecting |
| Error | Drooped neck, sympathetic expression | Try again |

Without a selected profile the action opens key import. Android VPN consent
still precedes a connection request, and denial never triggers success feedback.
Both the character and the labeled action are usable without shaking. The
descriptive action is also the unambiguous screen-reader/keyboard alternative.

The service's `ConnectionManager.state` is the only source of connection truth.
Animation does not start services, set connection state, run reachability probes,
or modify transport. Sound/haptics acknowledge an accepted request, not success.
The connected hop and feeding are driven by the connected state. That state
does not prove authenticated proxy traffic or leak protection; the existing
limitations in README and AUDIT still apply.

Server selection, deletion and refresh are blocked while a connection is active
or changing, so a selected-server label cannot silently pretend the running
tunnel changed. A pending consent request is saved across configuration changes.

## Motion and illustration

`ui/mascot/PigeonRig.kt` describes time-based choreography independently of
Android. `PigeonView` draws the character as articulated curved paths: independent
body, bending neck/head, eyes/lids, beak, wings, tail, feet and seed particles.
Timing is deterministic rather than accumulated per frame, so skipped frames do
not speed up or slow down the routine. Animation has pauses as well as accents.

Rendering is limited to a foreground visible scene and capped around 30 fps.
Reduced-motion mode uses intentional still poses and no animated seed particles.
Android's animator-duration setting is respected too. All interactions continue
to work without sound or motion.

The visual language is warm paper, mint courtyard, lilac seed action and a
lavender/slate pigeon with iridescent neck and coral feet. Day/night resources
are shared by the home, settings, import and onboarding screens.

## Audio provenance

The `pigeon_*.wav` assets are original deterministic synthesis, not field
recordings and not samples from another app. Their reproducible generator lives
in `tools/`. Low harmonic coos, breath texture and short seed contacts form three
brief, non-looping cues. The audio is intentionally quiet and uses notification
sonification rather than overriding the phone's volume or silent mode.

`CooSoundPlayer` preloads samples, retains only the latest pending cue with a
short expiry, and rechecks preferences when decoding completes. Pausing the
screen stops its current sound and cancels pending playback. Shake feedback has
its own service-owned player; there is no background feeding soundtrack.

## Verification

Run with JDK 17 and Android SDK 34:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

`CourtyardStateTest` checks the six-state presentation and busy-action gates.
The rig tests check deterministic poses and choreography invariants. Audio
policy tests cover quiet modes, stale requests, failure and release handling.
`CourtyardRenderTest` uses Robolectric with native Skia to draw the real Android
layout in day/night states and writes PNGs under `app/build/courtyard-previews`.
This is native-layout validation, not an emulator or physical-device test.

`CourtyardActivityTest` additionally creates the actual AppCompat activities,
checks no-profile import, service-state rendering/action gates, safe recreation,
preference retention and onboarding-demo isolation/step restoration. It exports
actual home, onboarding and settings renders. These checks run at API28 and do
not replace physical Android VPN-consent or sensor testing.

The verified local run on 2026-09-14 completed **125 passing tests, 6 opt-in
live-server skips, debug APK assembly, and lint with 0 errors / 53 warnings**.
Generate the full native motion frames with:

```sh
./gradlew :app:testDebugUnitTest -PrenderMascotFrames=true
```

Before calling a device release verified, check:

- Same signing certificate as the installed app; update without uninstalling.
- Existing profiles/settings preserved, static and dynamic keys still import.
- Denied/granted VPN consent; no connection request on an onboarding demo tap.
- Connect/disconnect via character, action label, shake and Quick Settings tile.
- Rapid repeat taps and shakes during transitions do not produce extra requests.
- The connected state animates feeding; reconnect/error never looks connected.
- Silent/vibrate, notification volume zero, sound off, reduced motion and system
  animation disabling all behave as expected.
- Pause, lock screen, return from Settings, rotate, and repeat permission flow.
- TalkBack, large text, a compact screen and landscape remain usable.
- On the Honor daily driver, inspect smoothness, sound timbre/level, battery use
  and actual TCP/UDP/DNS/IPv6 behavior separately from mascot/UI tests.

No transport limitation in the existing audit is claimed fixed by this redesign.
