# QuartzLamp

Lenze Tech「拾音灯」硬件的自有 Android 控制器——Material 3 Expressive 重制版。

## 功能

- **调色**：色环取色、饱和度/亮度厚轨道，8 预设色，电源主操作
- **灯效**：153 种内置灯效（9 分类 × 17），完整原名 + 名称色示意条，速度/灵敏度
- **律动**：三源切换——手机麦克风 / 灯载麦克风 / 本地音乐（Visualizer 频谱驱动）；
  四种拾音风格与灵敏度对灯端生效。停止聆听=停止发送律动帧，灯保持最后颜色
  （协议帧#6 仅含色相/饱和度，无亮度位；音量→亮度映射列为后续候选，需帧#11 RGB 直发验证）
- **定时**：按星期的定时开/关灯，配置存灯端、可读回（无"启用"开关——协议无此位，
  保存即下发、读取"未设置"即清空）
- **设备**：多灯并行管理，行级连接/断开/重连，指令广播到全部已连接灯；
  意外断连有限自动重连（3 次 × 4s），主动断开不抢连

## 技术要点

- Kotlin + Compose + Material 3 Expressive（material3 1.5.0-alpha22 线）
- BLE：FastBle 2.3.4（源码收编 `app/libs-src/`，Apache-2.0，见 `third_party/`），
  与厂商 App 同款传输层；协议帧表见 `docs/ble_protocol.md`（14 种帧，反汇编提取+单测锁定）
- minSdk 31 / targetSdk 37 · 单 :app 模块无 DI · 模拟器自动切换假灯实现（`FakeLampConnection`）供无灯开发

## 构建

```bash
./gradlew assembleDebug    # 需要 JDK 21（AGP 9 实测门槛，系统默认 JDK 11 会失败）
./gradlew testDebugUnitTest
```

Release 签名凭据走 `~/.gradle/gradle.properties`（QUARTZLAMP_* 四键），
**缺失时构建显式失败（不回退 debug 签名）**。

## 文档

- `docs/ble_protocol.md` — BLE 协议帧表（唯一事实源）
- `docs/acceptance.md` — 验收清单（模拟器已验项 + 真机项 + 协议仲裁项）
- `project_status.md` — 项目状态

## 声明 / Declaration

- 本项目为个人学习/自用目的对自有硬件的控制器重实现；与 Lenze Tech 无关联，
  未复用其代码与美术资源；灯效名称为功能性描述短语。
  This is an independent reimplementation of a controller for personally owned hardware,
  for learning and personal use. Not affiliated with Lenze Tech; no vendor code or assets reused.
  Effect names are functional descriptive phrases.
- AI 使用声明：本项目由 AI（ZCode / GLM）在人类需求定义与验收下深度参与开发。
  AI disclosure: developed with substantial AI assistance (ZCode / GLM) under human
  direction and acceptance.
- 软件按"现状"提供，使用后果自负；BLE 指令仅面向该拾音灯硬件。
  Provided as-is, without warranty of any kind; use at your own risk. BLE commands target
  this lamp hardware only.

---

## English

An unofficial Material 3 Expressive Android controller for the Lenze Tech
sound-reactive lamp ("拾音灯"), rebuilt from scratch.

### Features

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

### Technical Highlights

- Kotlin + Compose + Material 3 Expressive (material3 1.5.0-alpha22 line)
- BLE: FastBle 2.3.4 vendored as source (`app/libs-src/`, Apache-2.0, see `third_party/`) —
  the same transport the vendor app uses; frame table at `docs/ble_protocol.md`
  (14 frames, extracted from disassembly and locked by unit tests)
- minSdk 31 / targetSdk 37 · single `:app` module, no DI · emulators auto-switch to a
  fake lamp (`FakeLampConnection`) for development without hardware

### Build

```bash
./gradlew assembleDebug    # requires JDK 21 (AGP 9 hard requirement; system JDK 11 fails)
./gradlew testDebugUnitTest
```

Release signing reads `~/.gradle/gradle.properties` (four `QUARTZLAMP_*` keys);
**the build fails explicitly if they are missing (no debug-signing fallback)**.

### Docs

- `docs/ble_protocol.md` — BLE protocol frame table (single source of truth)
- `docs/acceptance.md` — acceptance checklist (emulator-verified + on-device + protocol arbitrations)
- `project_status.md` — project status (Chinese)

---

## 名字的彩蛋 / About the Name

中文名「拾音灯」说的是功能：拾取声音的灯。英文名 QuartzLamp 收下了原厂包名 `quartzlamp`
里藏的谐音玩笑——拾音（shíyīn）≈ 石英（shíyīng）。于是这是一盏名字听着像另一种灯的、
会听声音的灯。

The Chinese name 拾音灯 ("sound-picking lamp") says what it does. The English name
QuartzLamp embraces the pun hidden in the vendor's package name `quartzlamp`:
拾音 (shíyīn, "to pick up sound") ≈ 石英 (shíyīng, "quartz"). Hence — a lamp that
listens, named after a rock it is not.
