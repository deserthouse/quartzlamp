# quartzlamp — 拾音灯控制器重建项目

> 状态文件：本文件是唯一状态源（架构/功能/TODO/文件拓扑），阶段完成后原地更新。
> 创建：2026-10-15 · 当前阶段：**方案已定稿，待用户批准后进入 M0**

## 0. 项目定位

把厂商 App（`com.lenzetech.quartzlamp` 1.1.1，Lenze Tech「拾音灯」官方控制器，Google Play 渠道包）
重建为一个 Material 3 Expressive 的自有 Android 应用：同样的灯、同样的功能、自己的实现与 UI。
**目标硬件边界：本灯（1.1.1 配套固件）为唯一目标，不做多型号兼容。**

## 1. 可行性结论：能做，且有较高把握

依据（全部来自 APK 静态拆解实证，产物见 `tests/apk_analysis/`）：

1. **厂商 App 是纯 BLE 遥控器，无云端依赖**。无 INTERNET 权限、无账号、无 OTA、无分析 SDK——
   所有控制逻辑都在客户端，不存在"服务器侧黑盒"这一不可复刻层。这是可行性的决定性因素。
2. **协议关键面已打通**：GATT 服务 `FFF0`、写特征 `FFF3`、写参数（带响应/顺序发/200ms 超时）、
   多灯广播模式、帧结构（帧头 `0xBC` + 指令 + 参数 + 帧尾 `0x55`，定长 10 字节起步）、
   一个已实证的调色帧 `BC 04 06 02 B B R 00 00 55`。
3. **剩余协议可完整提取**：全部指令发送收敛于单点 `MyApplication.write([B)`，dex 未混淆，
   反汇编该方法的所有调用点即可得到完整帧表；且原厂 App 的 `Log.d("BLE-WRITE")` 在正式包中
   仍然生效——真机装原厂 App 跑一遍 UI、logcat 抓帧即为金标准，可与静态提取交叉验证。
4. **功能无技术黑盒**：153 个灯效名已全部提取（资源 cmode1~153 中英文）；拾音=MediaRecorder
   分贝轮询→实时变色；音乐律动=Visualizer 频谱；定时=灯端存储可回读。全部是标准 Android API。

## 2. 风险与未知（附对策）

| # | 风险 | 等级 | 对策 |
|---|------|------|------|
| R1 | 未提取的指令帧（模式/定时读写/灵敏度/速度/开关）存在校验和或状态机 | 低 | 已见帧均为简单定长无校验；静态提取 + 原厂 logcat 抓帧双重验证 |
| R2 | notify 特征 UUID 未确认（定时回读可能走 notify） | 低 | dex 中 `notify()` 调用点反汇编可得；原厂 App 抓包核对 |
| R3 | 扫描过滤条件（设备名前缀/厂商数据）未确认 | 低 | `setScanRule()`/`filterDevice()` 反汇编可得 |
| R4 | 真机+真灯依赖：AVD 无法连接 BLE 外设，协议验证必须真机 | 中 | M2 起需要用户提供测试环境；过渡期可用第二台手机跑 nRF Connect 当"假灯"收帧开发 |
| R5 | 灯效 UI 索引 ↔ 帧内模式号映射未提取 | 低 | `mode.setModeByIndex()` 反汇编 + 原厂 App 逐项点选抓帧 |
| R6 | M3 Expressive 仍处 alpha（material3 1.5.0 未发布，最新稳定 1.4.0） | 低 | OptIcon 已在 `1.5.0-alpha22` 上稳定运行，走同一路径 |

## 3. 技术决策

### 3.1 工具链基线（对齐 OptIcon，全局规范第七节）

Gradle 9.5.1 / AGP 9.2.1 / Kotlin 2.2.10 / Java 21 / compileSdk 37 / minSdk 31 / targetSdk 37 ·
compose-bom 2025.06.00 + material3 1.5.0-alpha22（M3E alpha 线）· lifecycle 2.9.0 ·
navigation-compose 2.9.0 · activity-compose 1.10.0 · 命名空间 `io.github.deserthouse.quartzlamp` ·
单 :app 模块、无 DI 框架 · keystore 走 ~/.gradle/gradle.properties 注入（QUARTZLAMP_* 前缀）。

### 3.2 架构

Compose + MVVM + StateFlow。分层：`ble/`（连接与协议）· `protocol/`（帧构造/解析，纯 Kotlin 可单测）·
`ui/`（扫描、调色、麦克风、模式、音乐、定时六屏 + Navigation）· `data/`（灯效表等静态数据）。
不移植厂商代码；从 APK 提取的 UUID/帧格式属客观接口事实，自行实现。

### 3.3 BLE 层选型（待用户拍板）

| 方案 | 说明 | 评估 |
|------|------|------|
| **B. 自写协程 BLE 封装（推荐）** | `BluetoothLeScanner` + `BluetoothGatt` 包成 Flow/挂起函数 | 协议面极小（一个写特征±一个 notify），约 400 行零依赖；契合单模块极简基线；Android BLE 经典坑（GATT 133、操作串行化）可控 |
| A. Nordic Kotlin BLE Library | `no.nordicsemi.android.kotlin.ble`，Apache-2.0 | 成熟但长期 alpha，为复杂 GATT 场景设计；若自写撞上边角问题可退此方案 |
| C. FastBle（厂商同款） | `com.clj.fastble` | 多年未维护、回调式 API 与协程生态不合，不推荐 |

### 3.4 待用户拍板项

1. BLE 层选型（默认按推荐 B 执行）。
2. 应用名与品牌（命名规范：用户拍板）。
3. 灯效文案：153 个效名自行重拟（不照抄原厂字符串，版权自保），是否接受。
4. 测试环境：真灯何时在手（决定 M2 排期）。

## 4. 里程碑（每段独立可止损）

| 里程碑 | 内容 | 交付物/验收 | 依赖 |
|--------|------|------------|------|
| **M0 协议全量提取** | 反汇编全部 write/notify 调用点 + 原厂 App logcat 抓帧交叉验证 | `docs/ble_protocol.md` 完整帧表（含抓帧实录） | 原厂 App 可装（真灯更佳） |
| **M1 工程骨架** | git init、Gradle 基线、导航框架、六屏空页面、BLE 权限流 | 可编译安装的 debug APK | 无 |
| **M2 连接与调色** | 扫描/连接/写通，色环选色灯即变色 | **真灯端到端变色**（本项目第一硬验收） | 真机+真灯 |
| **M3 模式页** | 153 灯效 + 速度/灵敏度 + 开关 | 真灯逐类抽验 | M2 |
| **M4 麦克风页** | 手机麦 dB→实时变色；外麦灵敏度下发 | 真灯对声源律动 | M2 |
| **M5 音乐页** | 本地音乐 + Visualizer 频谱律动 | 真灯随播曲律动 | M2 |
| **M6 定时页** | 定时读写灯端、周重复 | 定时生效 + 回读一致 | M2 |
| **M7 打磨发布** | M3E 动效/深色模式/dynamic color、release 签名、README | 发布渠道上线 | 全部 |

止损设计：M0 纯本地零风险；M1 后即有可安装产物；M2 若协议验证失败（帧不生效），
回 M0 补抓帧，损失一天以内。

## 5. 验证策略

- **协议金标准**：原厂 App（本 APK）写通道日志 `Log.d("BLE-WRITE", hex)` 在正式包生效——
  真机安装、遍历 UI 功能、`adb logcat -s BLE-WRITE` 抓全量真实帧，与静态反汇编互相印证。
- **协议文档即测试**：帧表落 `docs/ble_protocol.md`，`protocol/` 层对帧表写单元测试（构造/断言字节级一致）。
- **装机验证冷启**：全规范第七节（force-stop 后验证，不认热重叠假象）。
- **交付自测门禁**：每个里程碑在真机跑通基础逻辑后才报完成。

## 6. 合规红线

- 不复用厂商 APK 的代码/图标/文案（153 灯效名重拟）；
- 协议事实（UUID、帧格式）为客观接口数据，自行实现无碍；
- 本仓库永不入库厂商 APK 本体与其解包资源（.gitignore 已锁）；
- 若开源：附 AI 使用声明 + 免责声明（对齐全局规范六.8）。

## 7. 当前状态（2026-10-15）

- ✅ 原厂 APK 拆解完成（产物 `tests/apk_analysis/`，结论见本文第 1 节）
- ✅ 可行性评估与重建方案定稿（本文件）
- ⏳ 等待用户批准方案 → M0 协议全量提取

### 核心文件拓扑

```
project_status.md          ← 本文件（唯一状态源）
tests/apk_analysis/        ← 拆解产物（APK/解包已 gitignore，再生命令见其 README）
docs/ble_protocol.md       ← M0 产出（尚未创建）
app/                       ← M1 产出（尚未创建）
```
