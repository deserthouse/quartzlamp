# quartzlamp 项目规范

拾音灯（Lenze Tech `com.lenzetech.quartzlamp` 配套硬件）的 Material 3 Expressive 自有控制器重建项目。
**唯一状态源：根目录 `project_status.md`**（架构/里程碑/风险/TODO），动手前先读。

## 红线（本项项目专属）

1. **厂商物不入库**：厂商 APK、解包资源（`tests/apk_analysis/extracted/`、`*.apk`）永不进 git；
   不复用厂商代码/图标/文案，灯效名自行重拟。
2. **BLE 验证必须真机**：AVD 连不了 BLE 外设；协议验证 = 真机 + 真灯（或 nRF Connect 假外设）。
3. **协议事实源是 `docs/ble_protocol.md`**：帧表改动必须同步该文档并过 `protocol/` 层单元测试；
   原厂 App 的 `BLE-WRITE` logcat 抓帧是仲裁标准。
4. 装机验证遵循全局规范冷启纪律（force-stop / --activity-clear-task）。

## 工具链

对齐 OptIcon 基线（Gradle 9.5.1 / AGP 9.2.1 / Kotlin 2.2.10 / compileSdk 37 / minSdk 31 /
material3 1.5.0-alpha22 M3E alpha 线 / `io.github.deserthouse.quartzlamp` / 单 :app 无 DI），
签名凭据走 ~/.gradle/gradle.properties（QUARTZLAMP_* 前缀）。
