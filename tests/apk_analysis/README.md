# 原厂 APK 拆解产物（com.lenzetech.quartzlamp 1.1.1）

来源：`C:\Users\deser\Downloads\QQ\拾音灯 [1.1.1].apk`（Google Play 渠道包）
MD5：`b784e9a2b60f0176f2b826d35b200d1c` · 5.8 MB

## 保留在 git 里的

- `res_dump.txt` — aapt2 资源转储（含全部字符串中英文对照、153 灯效名 cmode1~153）

## 不入 git 的（可一键再生）

- `syding.apk` — 原厂 APK 副本（版权物）
- `extracted/` — 解包目录
- `dexdump.txt` / `dexdump_dis.txt` — dexdump 基本信息版（36M）/ 反汇编版（101M）

再生命令（Git Bash，SDK build-tools 37.0.0）：

```bash
cp "/c/Users/deser/Downloads/QQ/拾音灯 [1.1.1].apk" syding.apk
BT=/c/Users/deser/Android/Sdk/build-tools/37.0.0
"$BT/aapt2.exe" dump resources syding.apk > res_dump.txt
unzip -o -q syding.apk -d extracted
"$BT/dexdump.exe" extracted/classes.dex > dexdump.txt
"$BT/dexdump.exe" -d extracted/classes.dex > dexdump_dis.txt
```

## 已实证的协议事实（详见根目录 project_status.md 第 1 节与 docs/ble_protocol.md）

- GATT：服务 `0000FFF0-0000-1000-8000-00805f9b34fb`，写特征 `0000FFF3-...`
- 写参数：withResponse=true / 顺序写 / 200ms 超时；遍历所有已连接设备（多灯广播）
- 调色帧（10 字节）：`BC 04 06 02 B B R 00 00 55`（头 0xBC、尾 0x55）
- 原厂 App 写日志：`Log.d("BLE-WRITE", hex)` —— 真机抓帧金标准
