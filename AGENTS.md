# quartzlamp 项目规范

拾音灯（Lenze Tech `com.lenzetech.quartzlamp` 配套硬件）的 Material 3 Expressive 自有控制器重建项目。
**唯一状态源：根目录 `project_status.md`**（架构/里程碑/风险/TODO），动手前先读。

## 红线（本项项目专属）

1. **厂商物不入库**：厂商 APK、解包资源（`tests/apk_analysis/extracted/`、`*.apk`）永不进 git；
   不复用厂商代码/图标/品牌文案。**例外（2026-10-15 用户拍板）：153 个灯效名保留原版**——
   颜色×动作的组合式功能描述短语，客观事实性表达，风险可接受；UI 可加视觉预览但名字不动。
2. **BLE 验证必须真机**：AVD 连不了 BLE 外设；协议验证 = 真机 + 真灯（或 nRF Connect 假外设）。
3. **协议事实源是 `docs/ble_protocol.md`**：帧表改动必须同步该文档并过 `protocol/` 层单元测试；
   原厂 App 的 `BLE-WRITE` logcat 抓帧是仲裁标准。
4. 装机验证遵循全局规范冷启纪律（force-stop / --activity-clear-task）。

## 工具链

对齐 OptIcon 基线（Gradle 9.5.1 / AGP 9.2.1 / Kotlin 2.2.10 / compileSdk 37 / minSdk 31 /
material3 1.5.0-alpha22 M3E alpha 线 / `io.github.deserthouse.quartzlamp` / 单 :app 无 DI），
签名凭据走 ~/.gradle/gradle.properties（QUARTZLAMP_* 前缀）。
BLE 层已拍板（2026-10-15）：FastBle 2.3.4 厂商同款、AAR 内嵌 `app/libs/`、照抄厂商写参数，
外面套 callback→Flow 门面（`LampConnection`），FastBle 类不进 UI 层；自写/Nordic 仅作兜底不启用。
