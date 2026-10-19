# QuartzLamp

[中文](README.md) · **English**

An unofficial Material 3 Expressive Android controller for the Lenze Tech
sound-reactive lamp ("拾音灯"), rebuilt from scratch.

## Features

- **Color**: hue wheel, saturation/brightness thick-track sliders, 8 presets,
  power as the primary action
- **Effects**: 153 built-in effects (9 categories × 17) with original names and
  name-color hint bars; speed/sensitivity controls
- **Rhythm**: three sources — phone microphone / lamp's own microphone / local music
  (Visualizer-driven). Four pickup styles and the pickup sensitivity apply to the lamp.
  Stopping listening stops sending reactive frames; the lamp keeps its last color
  (protocol frame #6 carries hue/saturation only — no brightness; volume→brightness is a
  future candidate requiring frame #11 RGB verification)
- **Timer**: weekly on/off schedules stored on the lamp and readable back
  (no "enable" switch — the protocol has no such bit; saving sends immediately,
  reading an "unset" slot clears it)
- **Devices**: multi-lamp management with per-device connect/disconnect/reconnect;
  commands broadcast to all connected lamps; limited auto-reconnect after unexpected
  disconnects (3 × 4s), manual disconnects never fight back

## Technical Highlights

- Kotlin + Compose + Material 3 Expressive (material3 1.5.0-alpha22 line)
- BLE: FastBle 2.3.4 vendored as source (`app/libs-src/`, Apache-2.0, see `third_party/`) —
  the same transport the vendor app uses; frame table at `docs/ble_protocol.md`
  (14 frames, extracted from disassembly and locked by unit tests)
- minSdk 31 / targetSdk 37 · single `:app` module, no DI · emulators auto-switch to a
  fake lamp (`FakeLampConnection`) for development without hardware

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

## Declaration / 声明

- This is an independent reimplementation of a controller for personally owned hardware,
  for learning and personal use. Not affiliated with Lenze Tech; no vendor code or assets
  reused. Effect names are functional descriptive phrases.
  本项目为个人学习/自用目的对自有硬件的控制器重实现；与 Lenze Tech 无关联，
  未复用其代码与美术资源；灯效名称为功能性描述短语。
- AI disclosure: developed with substantial AI assistance (ZCode / GLM) under human
  direction and acceptance.
  AI 使用声明：本项目由 AI（ZCode / GLM）在人类需求定义与验收下深度参与开发。
- Provided as-is, without warranty of any kind; use at your own risk. BLE commands target
  this lamp hardware only.
  软件按"现状"提供，使用后果自负；BLE 指令仅面向该拾音灯硬件。

---

## About the Name

The Chinese name 拾音灯 ("sound-picking lamp") says what it does. The English name
QuartzLamp embraces the pun hidden in the vendor's package name `quartzlamp`:
拾音 (shíyīn, "to pick up sound") ≈ 石英 (shíyīng, "quartz"). Hence — a lamp that
listens, named after a rock it is not.
