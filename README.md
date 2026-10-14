# QuartzLamp（拾音灯）

Lenze Tech「拾音灯」硬件的自有 Android 控制器——Material 3 Expressive 重制版。

**名字的彩蛋**：拾音（shíyīn）≈ 石英（shíyīng）——原厂包名 `quartzlamp` 的谐音玩笑。
中文名「拾音灯」说功能，英文名 QuartzLamp 收下这个梗：一盏名字听着像另一种灯的、会听声音的灯。

## 功能

- **调色**：色相/饱和度/亮度，8 预设色
- **模式**：153 种内置灯效（9 分类 × 17 色板）+ 速度/灵敏度
- **麦克风**：手机麦实时律动 / 外麦模式，四种拾音风格
- **音乐**：本地曲目播放，Visualizer 频谱驱动律动
- **定时**：按星期的定时开/关灯，配置存灯端可读回
- 多灯同步：所有指令广播到全部已连接灯

## 技术要点

- Kotlin + Compose + Material 3 Expressive（material3 1.5.0-alpha22 线）
- BLE：FastBle 2.3.4（源码收编 `app/libs-src/`，Apache-2.0，见 `third_party/`），
  与厂商 App 同款传输层；协议帧表见 `docs/ble_protocol.md`（14 种帧，反汇编提取+单测锁定）
- minSdk 31 / targetSdk 37 · 单 :app 模块无 DI · 模拟器自动切换假灯实现（`FakeLampConnection`）供无灯开发

## 构建

```bash
./gradlew assembleDebug    # 需要 JDK 17+（本项目验证于 JDK 21）
./gradlew testDebugUnitTest
```

Release 签名凭据走 `~/.gradle/gradle.properties`（QUARTZLAMP_* 四键），
缺失时自动回退 debug 签名。

## 文档

- `docs/ble_protocol.md` — BLE 协议帧表（唯一事实源）
- `docs/acceptance.md` — 验收清单（模拟器已验项 + 真机项 + 协议仲裁项）
- `project_status.md` — 项目状态

## 声明

- 本项目为个人学习/自用目的对自有硬件的控制器重实现；与 Lenze Tech 无关联，
  未复用其代码与美术资源；灯效名称为功能性描述短语。
- AI 使用声明：本项目由 AI（ZCode / GLM）在人类需求定义与验收下深度参与开发。
- 软件按"现状"提供，使用后果自负；BLE 指令仅面向该拾音灯硬件。
