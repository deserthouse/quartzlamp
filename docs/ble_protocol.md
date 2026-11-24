# 拾音灯 BLE 协议（协议事实源）

> **本文档是帧表唯一事实源**：帧改动必须同步本文档并过 `protocol/` 层单元测试。
> 提取方法：厂商 APK（`com.lenzetech.quartzlamp` 1.1.1，MD5 `b784e9a2...`）dex 全量反汇编，
> 覆盖全部 21 个 `MyApplication.write([B)` 调用点 + GATT/扫描/连接代码路径。
> 仲裁标准：原厂 App 真机运行 `adb logcat -s BLE-WRITE` 抓帧（厂商正式包该日志生效）。
> 置信度标注：✅=字节码直接实证；⚠️=静态推断需真机验证。

## 1. GATT 层 ✅

| 项 | 值 |
|---|---|
| 服务 UUID | `0000FFF0-0000-1000-8000-00805f9b34fb` |
| 特征 UUID（写/读/notify 同一个） | `0000FFF3-0000-1000-8000-00805f9b34fb` |
| CCD 描述符 | `00002902-0000-1000-8000-00805f9b34fb` |
| 写参数 | `write(…, data, split=true, sendNextWhenLastSuccess=true, intervalBetweenTwoPackage=200ms, callback)`——FastBle 2.3.4 八参签名（收编源 BleManager.java:603-610）。2026-10-15 R0 纠正：此前误标为"withResponse/顺序写/超时"，实为分包/前包成功才发下一包/包间隔 |

**FastBle 初始化参数（MyApplication.onCreate，照抄）** ✅：
`enableLog(true)` · `setReConnectCount(3, 4000ms)` · `setMaxConnectCount(7)` ·
`setConnectOverTime(99000ms)` · `setOperateTimeout(5000ms)`

**扫描规则** ✅：超时 8000ms · autoConnect=**true** · **无服务 UUID 过滤**，
过滤在回调内做：设备名非空 && `scanRecord[scanRecord[0]+3] == 0x22` && `scanRecord[scanRecord[0]+4] == 0x20`
（首 AD 段长度偏移法，识别字节 **22 20**）。
autoConnect 出处（2026-11-24 字节码复核，`MyApplication.setScanRule` `2f68e0-2f690e`）：`<init>` 把
`isAutoConnect` 字段置 true（`2f64fa`），`setScanRule()` 以 `setAutoConnect(isAutoConnect)` 传入——
是厂商**显式置位**，不是 FastBle 的默认值（`BleScanRuleConfig` 默认 false；此前记作"（默认）"有误）。
`setScanRule()` 全 dex 仅 `onCreate` 一处调用（`2f6826`），所以烤进 FastBle 连接模式的是字段**初值** true，此后不变。

autoConnect 的另一半事实（同批复核）：`isAutoConnect` 字段本身会在**每次扫描入口**被
`MyApplication.startScan(boolean)`（写入点 `2f69ac`）重写——手动点扫描按钮传 **false**
（`BleActivity.onClick` `2f2624`）、`onResume`（`2f2782`）与控制页 `onCreate`（`2f2ff0`）传 **true**。
重写后的值只用于厂商自己的逻辑：扫到设备时
`if (isAutoConnect && isBleReConnect(device)) connect(device)`（`2f6758-2f676c`）。
即厂商的"重连"是**扫描触发**的（回到页面就扫描，扫到待重连的灯就连接），不是定时退避的盲连。

**传输层行为（移植与重连实现须知）** ✅（收编源 `BleBluetooth` / `BleConnector`）：
- 连接失败后的重试只有一条路径：`MSG_CONNECT_FAIL`（连接建立阶段收到断开）且 `connectRetryCount < 3` 时
  延迟 4s 重发 connect——即 `setReConnectCount(3, 4000ms)` 的含义；一段序列最多 4 次连接尝试，
  整段都失败才回调 `onConnectFail`。另两条终止路径**不重试**、直接回调 `onConnectFail`：
  `MSG_CONNECT_OVER_TIME`（99s 内没有任何连接状态变化）与 `MSG_DISCOVER_FAIL`。
  （细节：`MSG_DISCOVER_FAIL` 自身不重试，但不清除此前已排队的 `MSG_RECONNECT`——极少数交错下同一对象
  还会再连一次；`MSG_CONNECT_OVER_TIME` 不存在这种并存。）
- 已连接但服务发现没有结果时**没有超时**：`discoverServices()` 返回 true 之后若既等不到
  `onServicesDiscovered` 也等不到断开，传输层不会回调任何终态（连接成功时 99s 超时消息已被移除）。
  上层的终态兜底超时就是为这种情况准备的。
- FastBle **不处理**已连接后的意外断连——意外断连直接回调 `onDisConnected`，重连必须由上层触发。
  它也**没有取消在途连接的接口**：上层"取消"之后，连接中的对象仍会在后台走完上述重试序列
  （公开 API 只能断开已进入已连接表的设备）。
- 已连接设备登记表按设备键**先到先得**（`MultipleBluetoothController.addBleBluetooth`：键已存在就不登记），
  notify / write / read 都按设备键查这张表。同键出现两个连接对象时，先连上的占位。
- 每个操作带 `setOperateTimeout`（5s）：到点回调失败；若设备应答在 5s 后才到，会对**同一次操作再回调一次**
  真实结果。上层必须把传输回调当作"可能多次"处理。

**我方实现与上表的对应**（Android `FastBleLampConnection`）：初始化参数逐项照抄；**autoConnect 为 `false`
（直连），与厂商不同**——自 M1 骨架起即如此、无决策记录；现值经真灯验证可连（设备刚被扫到时直连更快、
失败有界）。是否回归厂商值属待决项，需真机对比后再定。注意这不是改一个开关的事：
① 厂商的"重连"靠扫描触发，其字段在每次扫描入口被重写；②（推断，未实测）若仅把 autoConnect 改成 true，
离线的灯不会回断开事件，连接会落到 99s 超时那条**不重试**的路径，"每轮最多 4 次连接尝试"的推导随之不成立。

**回读**：定时信息用 GATT **read**（FFF3）读回，notify 只注册不参与 UI（回调仅打日志）。
**多灯广播**：write 遍历所有已连接设备逐个下发。

## 2. 帧表

### 2.1 BC 族（头 `BC` 尾 `55`，定长）

| # | 功能 | 帧 | 参数说明 | 置信度 |
|---|------|-----|---------|--------|
| 1 | 电源开关 | `BC 01 01 <01开\|00关> 55` (5B) | | ✅ |
| 2 | 静态调色（HSV） | `BC 04 06 Hh Hl Sh Sl 00 00 55` (10B) | H=色相[0,360)、S=饱和度×1000；**大端 16 位、除 256 取模**（调色页专用编码） | ✅ |
| 3 | 亮度/明度 | `BC 05 06 Vh Vl 00 00 00 00 55` (10B) | V=进度值（下限 3），大端 16 位**除 255** | ✅ |
| 4 | 灯效模式选择 | `BC 06 02 Ih Il 55` (6B) | 模式索引，大端 16 位除 255 | ✅ |
| 5 | 速度 | `BC 08 01 <speed> 55` (5B) | 进度值 0-100；当前浏览分类=全灯时上限 70（min(v,70)，mode$1:003a-0042；byte[2]=01 见 mode$1:0050-0056）。限幅按当前 tab：mode$4.onTabSelected 汇合点每次切 tab 重估 isInQuandengMode | ✅ |
| 6 | 律动变色（麦/音乐实时） | `BC 09 06 Hh Hl Sh Sl 00 00 55` (10B) | HSV 大端 16 位**除 255**；厂商仅当 R≠0 且 G≠0 才发 | ✅ |
| 7 | 灵敏度（模式页） | `BC 10 01 <sen> 55` (5B) | 进度值（byte[2]=01，mode$2:003e-0044） | ✅ |
| 8 | 拾音源 | `BC 0F 01 <01\|00> 55` (5B) | **01=手机麦、00=灯载麦**（mic$1 array@0x5e=BC0F0101↔rb_mic_phone、array@0x66=BC0F0100↔rb_mic_device，aapt2 资源表实证，R0 定案） | ✅ |
| 9 | 拾音类型 | `BC 11 01 <01\|02\|03\|04> 55` (5B) | **01=经典、02=柔和、03=动感、04=迪斯科**（mic$2 开关表 6 目标+载荷+aapt2 R.id 实证，R0 定案） | ✅ |
| 10 | 拾音灵敏度 | `BC 12 01 <progress> 55` (5B) | 进度值（byte[2]=01，mic$3:0017-001b） | ✅ |

### 2.2 7E 族（头 `7E` 尾 `EF`，9 字节）

| # | 功能 | 帧 | 参数说明 | 置信度 |
|---|------|-----|---------|--------|
| 11 | RGB 直发（麦克风 RGB 模式） | `7E 07 05 03 R G B 00 EF` | RGB 各 1 字节 | ✅ |
| 12 | 定时设置 | `7E 08 82 HH MM 00 <0开\|1关> <周> EF` | HH 24 小时制（厂商 UI 12 小时制换算：PM=+12，12PM 特例→0）；周位掩码 bit i=第 i 天 | ✅ |
| 13 | 定时查询 | `7E 08 82 FF FF FF <0开\|1关> 00 EF` | 发送后 GATT read 读回 | ✅ |
| 14 | 时间同步 | `7E 07 83 HH MM SS WD FF EF` | 时分秒 + 星期（`Calendar.DAY_OF_WEEK-1`：周日=0） | ✅ |

### 2.3 心跳帧（不实现）

`FF 01 02 03 04 05 06 07 EF`（9B，每 4900ms）——Runnable 在 `<init>` 创建后**从未被启动**，
厂商死代码。我们的实现不发送。✅（不发送的依据是反汇编穷尽：runnable 字段无任何读取点）

## 3. 读回格式（定时）✅

read 返回字节：`[3]`=时（0xFF=-1 表示未设置）、`[4]`=分、`[7]`=周位掩码（bit i=第 i 天，
与 getBoolArrayBte 往返一致）。周位 bit0 对应 UI w1（周一）⚠️（往返自洽已证，与星期几的绝对对应待真机验证）。
厂商解析（timer$8.onReadSuccess）：时==-1 即视为未设置，清 UI 并跳过后续解析；**无长度/包络校验**。
我方 parseTimerRead 收紧为：长度恰 9 + 设定时数值域校验（时 0-23/分 0-59/周 0-127），
不校验帧头尾（响应包络未实证，见 §5）。

## 4. 厂商默认值 ✅

mingDu（明度）=600 · speed=50 · sensitivity=50

## 5. 待真机验证清单（验收预检项）

1. ~~帧 #8：`BC 0F 01 <01|00>` 与 手机麦/外麦 的对应方向。~~ ✅ R0 定案：01=手机麦、00=灯载麦（资源表+载荷实证）。
2. ~~帧 #9：`BC 11 01 {1,2,3,4}` ↔ 经典/迪斯科/动感/柔和 的映射。~~ ✅ R0 定案：1=经典、2=柔和、3=动感、4=迪斯科。
3. ⚠️ §3 周位掩码 bit0=周一 的绝对对应。
4. ⚠️ 模式索引（帧 #4）与 153 灯效的映射：`mode.setData()` 的 9 个 list 构造顺序
   （M3 实现时再反汇编 setData 补全，或真机逐项抓帧）。
5. ~~时间同步帧的发送时机（推断为连接建立后，未定位调用点）。~~ ✅ R2 已实现**连接成功即对时**
   （TimerUtils.updatePhoenTimerToLight 同语义，Calendar 现取时分秒+DOW-1）；原厂
   ColorfulActivity 事件枚举的完整触发链未再深挖（我方时机已有 TimerUtils 同构依据，非阻塞）。
6. 扫描识别字节 `22 20` 在真实广播中的位置复核（首 AD 段长度偏移法对不同广播布局的稳健性）。
7. ⚠️ 定时读回的**响应包络**：长度是否恒 9、帧头尾是否 `7E…EF`（厂商代码不校验无从实证；
   真机一次 read 抓包即可钉死；我方解析对不符包返回 null 走"读取失败"路径）。

## 6. 变更记录

- 2026-10-15 M0：全量反汇编提取，初版帧表（14 种帧 + GATT/扫描/初始化参数）。
  修正早期误读：调色帧 byte[3..6] 是 HSV 大端 16 位（非 B B R）。
- 2026-10-15 R0（独立审查 docs/review_2026-10-15.md 触发，全部按一手反汇编复核后定案）：
  - 帧 #5/#7/#10 byte[2] `02→01`（M0 寄存器流向误读，v3=1 写入索引 2）；
  - 帧 #5 全灯限幅改为**上限 70**（min(v,70)）；限幅按**当前浏览 tab** 判定是厂商语义
    （mode$4.onTabSelected 汇合点重估 flag，全 dex 仅此一处写点）——审查报告
    "应按已选效果"的子论点与一手证据不符，**驳回**，我方 ModeScreen 按 tab 判定保持不变；
  - 帧 #8 方向定案（01=手机麦），帧 #9 映射定案（1/2/3/4=经典/柔和/动感/迪斯科）；
  - 2026-10-20 追注：我方 UI 限幅已改**跟已选效果**（浏览式 tab 不发模式帧，厂商"flag 跟
    浏览 tab"前提不适用——依据见 review_2026-10-19.md §2 与用户拍板）；
  - §1 写参数语义纠正（split/sendNextWhenLastSuccess/intervalBetweenTwoPackage，调用值不变）；
  - §3 读回补 timer$8 解析细节，§5 新增响应包络验证项。
- 2026-11-24 审查复核（帧表无变化）：§1 autoConnect 出处更正——厂商显式置 true（`<init>` +
  `setScanRule`），非 FastBle 默认值；登记我方实现使用 `false` 的偏离（待决项）；补传输层内部重试与
  操作超时"迟到再回调一次"的语义，供 Windows 移植与重连实现参照。
- 2026-11-24 独立复审后补充（帧表无变化）：autoConnect 字段在每次扫描入口被 `startScan(boolean)` 重写
  （手动扫描 false / 进入页面 true）、厂商重连为扫描触发；传输层行为补全——两条不重试的终止路径、
  服务发现无超时、无取消在途连接的接口、已连接表先到先得。
