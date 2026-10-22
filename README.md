# QuartzLamp

**中文** · [English](README.en.md)

<p align="center">
  <img src="docs/img/icon_light.png" width="128" alt="QuartzLamp 图标（昼）" />
  <img src="docs/img/icon_dark.png" width="128" alt="QuartzLamp 图标（夜）" />
</p>

Lenze Tech「拾音灯」硬件的自有 Android 控制器——Material 3 Expressive 重制版。

> **当前状态**：[v0.5.6](https://github.com/deserthouse/quartzlamp/releases/tag/v0.5.6)（versionCode 15，2026-10-22；变更明细见 [CHANGELOG](CHANGELOG.md)）；模拟器与协议层已验，真灯验收进度与边界以 `project_status.md` §7 与 `docs/acceptance.md` 为准——支持硬件为 Lenze Tech 拾音灯（蓝牙 BLE）。源码构建见下文。

## 功能（三 tab：设备 / 灯效 / 定时）

- **设备**：电源主操作 + 亮度（全局参数，单色/灯效模式均生效）+ 连接管理
  （多灯并行、行级连接/断开/重连，意外断连有限自动重连 3×4s、主动断开不抢连）+
  灯端拾音配置（四种拾音风格与灵敏度，对灯自带麦克风生效）
- **灯效**：`静态 | 动态` 分段切换，互斥应用——
  静态=色环取色+8 预设（BC04 静态颜色）；动态=153 种内置灯效（9 分类 × 17）+
  完整原名+名称色示意条+速度/灵敏度
- **定时**：按星期的定时开/关灯（硬件两组槽位），配置存灯端、可读回
  （无"启用"开关——协议无此位，保存即下发、读取"未设置"即清空）
- 顶栏 ⚙️：石英色板/系统动态色主题切换、关于与声明

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

## 声明

- 本项目为个人学习/自用目的对自有硬件的控制器重实现；与 Lenze Tech 无关联，未复用其代码与美术资源；灯效名称为功能性描述短语。
- AI 使用声明：本项目由 AI（ZCode / GLM）在人类需求定义与验收下深度参与开发。
- 软件按"现状"提供，使用后果自负；BLE 指令仅面向该拾音灯硬件。

> English declaration: see [README.en.md](README.en.md).

---

## 名字的彩蛋

中文名「拾音灯」说的是功能：拾取声音的灯。英文名 QuartzLamp 收下了原厂包名 `quartzlamp`
里藏的谐音玩笑——拾音（shíyīn）≈ 石英（shíyīng）。于是这是一盏名字听着像另一种灯的、
会听声音的灯。
