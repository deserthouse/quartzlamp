# Third-party sources / 第三方源码

## FastBle 2.3.4（源码收编）

| 项 | 值 |
|---|---|
| 上游 | <https://github.com/Jasonchenlijian/FastBle>（Jasonchenlijian） |
| 版本 | 2.3.4，提交 `d89126caee2326ebe8c2102eecbbeddc9eae3274`（提交信息 "update 2.3.4"，2019-01-01） |
| 许可 | Apache License 2.0，全文见 [LICENSE-FastBle-Apache-2.0](LICENSE-FastBle-Apache-2.0) |
| 落点 | `app/libs-src/`（作为 `:app` 的 main 源集目录编译），保留原包名 `com.clj.fastble` |
| 收编日期 | 2026-10-15 |

**为什么收编而不是依赖坐标**：FastBle 2.3.4 当年发布在已停服的 jcenter，Maven Central 与 JitPack
均无官方构建；厂商 App 使用的正是这一版本。以源码形式收编可审计、可锁定版本，构建不依赖外部仓库。
它与被控硬件是厂商已验证的组合，因此本项目原样沿用其调用参数（见 `docs/ble_protocol.md` §1）。

**改动**：无。32 个文件与上游提交内容逐字节一致（行尾差异仅在本地检出时产生，Git 索引内为 LF）。
最近一次核验：2026-11-24——经 GitHub API 取该提交目录树，raw 下载全部 32 个文件，逐文件与
`app/libs-src/com/clj/fastble/` 比对，结果 `content_differ=0`。

**文件清单**（32 个，共 3658 行；路径相对 `app/libs-src/com/clj/fastble/`）：

- 根：`BleManager.java`
- `bluetooth/`：`BleBluetooth` · `BleConnector` · `MultipleBluetoothController` · `SplitWriter`
- `callback/`：`BleBaseCallback` · `BleGattCallback` · `BleIndicateCallback` · `BleMtuChangedCallback` ·
  `BleNotifyCallback` · `BleReadCallback` · `BleRssiCallback` · `BleScanAndConnectCallback` ·
  `BleScanCallback` · `BleScanPresenterImp` · `BleWriteCallback`
- `data/`：`BleConnectStateParameter` · `BleDevice` · `BleMsg` · `BleScanState` · `BleWriteState`
- `exception/`：`BleException` · `ConnectException` · `GattException` · `OtherException` · `TimeoutException`
- `scan/`：`BleScanPresenter` · `BleScanRuleConfig` · `BleScanner`
- `utils/`：`BleLog` · `BleLruHashMap` · `HexUtil`

**再次核验 / 升级方法**：取上游目标提交的全部 `FastBleLib/src/main/java/com/clj/fastble/**.java`，
与本目录做忽略行尾的逐文件比对；有任何内容差异时，在本节"改动"处登记文件与原因
（Apache-2.0 要求对修改过的文件作出说明）。

**Lint**：收编源码沿用其原有的旧式蓝牙 API 写法，相关的 `MissingPermission` 等存量问题登记在
`app/lint-baseline.xml`，不逐文件改写上游源码。

---

除上述 FastBle 源码外，仓库内其余代码、图标素材与文档均为本项目自有内容，随仓库根目录的
Apache-2.0 许可发布；图标来源说明见根目录 [NOTICE.md](../NOTICE.md)。
