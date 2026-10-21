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

## 流程对齐（2026-10-21 用户指令：对齐上游 agents.md 严格控制）

**流程事实源 = `C:\Users\deser\Projects\AGENTS.md`**（审查八节+GitHub 维护 §九；已由全局
指令第十一节逐会话注入）。若该文件晚于 2026-10-21 被修改，先重读事实源再执行。
本项目在事实源之上的具体化约束（源自 2026-10-21 流程评估，后续严格遵循）：

1. **版本与发布纪律（补 §9.6/9.7 落地）**：
   - 候选包（本地出包+桌面投放）≠ 发布；未经用户过目不打 tag。
   - 用户过目通过后，**tag + GitHub Release + CHANGELOG.md 更新同轮完成**，不拆开积压。
   - 0.4.1(5)~0.4.3(7) 定性为**未发布的内部构建**，不补 tag（无发布渠道语义成立）；
     tag 自 v0.4.4 起锚定。
   - **CHANGELOG.md 需建为累计更新日志事实源**（Keep a Changelog 结构，Unreleased 起步，
     历史版本从 acceptance.md 差异注记与 git log 提炼；发布时 Release 正文从同一条目提取）。
2. **提交纪律（§9.9.6 具体化）**：
   - 禁 `git add -A`——一律显式文件列表（并行会话/用户工作树防夹带）。
   - 提交信息、CHANGELOG、Release notes **不引用本地不可达的内部审查 ID**
     （CR-*/AV-R*/W-R* 等报告在本地 gitignore 下）；用自包含表述（现象+修复+验证）。
   - 历史提交中的内部 ID 不重写历史（红线），仅约束增量。
3. **审查纪律（§一/§三具体化）**：
   - 双源/复审至少一源独立于修复者本人（外部审查方或独立会话）；
     代码/架构链长静态审查优先排重模型会话，多模态视觉审查排 Flash。
   - 审查报告落盘后**程序化验证源码引用存在**（file:line 逐条 grep 核对）。
   - 环境失真（黑窗/输入漂移）下取得的观察一律作废重测，不作为缺陷或通过证据。
4. **验证债管理（§9.9.4 具体化）**：
   - 每版 AVD 回归欠账（未闭环项）写入 `project_status.md` 挂账清单；
     **下一版出包前必须先清上一版欠账**，或在出包提交中明确声明带入原因与环境前提。
   - 真机复验欠账同样挂账管理（多版未真机验证需在版本差异注记中披露）。

