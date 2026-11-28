# QuartzLamp

[中文](README.md) · **English**

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/img/icon_dark.png">
  <img src="docs/img/icon_light.png" width="96" alt="QuartzLamp">
</picture>

An unofficial Material 3 Expressive Android controller for the Lenze Tech
sound-reactive lamp ("拾音灯"), rebuilt from scratch.

> **Status**: [v0.5.8](https://github.com/deserthouse/quartzlamp/releases/tag/v0.5.8) (versionCode 17, 2026-11-24; see [CHANGELOG](CHANGELOG.md)); emulator and protocol layers verified — real-lamp acceptance progress and boundaries per `project_status.md` §7 and `docs/acceptance.md`. Supported hardware: the Lenze Tech sound-reactive lamp (Bluetooth BLE). Building from source below.

[Build from source](#build) · [Hardware acceptance notes](docs/acceptance.md) · [Changelog](CHANGELOG.md)

## Do You Need This App?

It does one thing: serve as the remote control for the Lenze Tech sound-reactive lamp.
It is useful to you only if all three of these hold:

- **You own the lamp**: the one the vendor app "拾音灯" (`com.lenzetech.quartzlamp`) is made for.
  On shopping sites it may turn up under the Alienware name or some other brand, shaped as a
  mouse pad, a headphone stand, or — more likely — some other trendy little piece of junk.
  The protocol targets these only; other models are not supported.
- **You want a modern-looking app**: one that keeps up with current Android, with optional system
  dynamic color and a different look by day and by night.
- **You think the vendor app is terrible**: you want a different remote. This is one rebuilt
  from scratch; it installs alongside the vendor app, so you don't have to uninstall that first.

What you get by switching: a Material 3 Expressive UI, modifiable source code, and the public
[protocol frame table](docs/ble_protocol.md) — start there if you want to write your own controller.

## Features (three tabs: Device / Effects / Timer)

- **Device**: power as the primary action + brightness (a global parameter — applies in
  both static-color and effect modes) + connection management (multi-lamp, per-device
  connect/disconnect/reconnect; auto-reconnect after unexpected drops — 3 rounds, 4s
  apart, cancellable at any time; the auto-reconnect fix has not yet been verified on
  a real lamp; manual disconnects never fight back) + lamp pickup configuration
  (four pickup styles and sensitivity for the lamp's own microphone)
- **Effects**: `Static | Dynamic` segmented toggle, applied exclusively —
  Static = hue wheel + 8 presets (BC04 static color); Dynamic = 153 built-in effects
  (9 categories × 17) with original names, name-color hint bars, speed/sensitivity
- **Timer**: weekly on/off schedules (two hardware slots), stored on the lamp and
  readable back (no "enable" switch — the protocol has no such bit; saving sends
  immediately, reading an "unset" slot clears it)
- Top-bar ⚙️: quartz / dynamic-color theme toggle, about & declarations

## Technical Highlights

- Kotlin + Compose + Material 3 Expressive (material3 1.5.0-alpha22 line)
- BLE: FastBle 2.3.4 vendored as source (`app/libs-src/`, Apache-2.0, see `third_party/`) —
  the same transport the vendor app uses; frame table at `docs/ble_protocol.md`
  (14 frames, extracted from disassembly and locked by unit tests)
- minSdk 31 / targetSdk 37 · single `:app` module, no DI · emulators auto-switch to a
  fake lamp (`FakeLampConnection`) for development without hardware
- The repo also contains a **Windows client port in progress** (`windows/`): the protocol
  library, GATT queue and session layer are ported with unit tests (run
  `dotnet test tests/QuartzLamp.Tests` from inside `windows/`, where `global.json` pins
  the .NET 10 SDK); there is no usable UI yet and it is unreleased — the Android app
  remains the current deliverable

## Build

```bash
./gradlew assembleDebug    # requires JDK 21 (AGP 9 hard requirement; system JDK 11 fails)
./gradlew testDebugUnitTest
```

Release signing reads `~/.gradle/gradle.properties` (four `QUARTZLAMP_*` keys);
**the build fails explicitly if they are missing (no debug-signing fallback)**.

## Docs

- `docs/ble_protocol.md` — BLE protocol frame table (single source of truth)
- `docs/acceptance.md` — acceptance checklist (emulator-verified + on-device + protocol arbitrations)
- `project_status.md` — project status (Chinese)

## Declaration

- This is an independent reimplementation of a controller for personally owned hardware, for learning and personal use. Not affiliated with Lenze Tech; no vendor code or assets reused. Effect names are functional descriptive phrases.
- Provided as-is, without warranty of any kind; use at your own risk. BLE commands target this lamp hardware only.

> 中文声明见 [README.md](README.md)。

---

## AI disclosure

This project contains no human ingredients. AI (ZCode / GLM) assisted with development. The maintainer defines requirements and reviews the result.

## About the Name

The Chinese name 拾音灯 ("sound-picking lamp") says what it does. The English name
QuartzLamp embraces the pun hidden in the vendor's package name `quartzlamp`:
拾音 (shíyīn, "to pick up sound") ≈ 石英 (shíyīng, "quartz"). Hence — a lamp that
listens, named after a rock it is not.
