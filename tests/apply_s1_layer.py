# S1 逐灯投递契约补丁（R44-04）
import sys

# 1. LampConnection.kt：DeliveryReport + sendDetailed
p = 'app/src/main/java/io/github/deserthouse/quartzlamp/ble/LampConnection.kt'
s = open(p, encoding='utf-8').read()
a = '''/** 会话就绪事件（Q2）：携带会话身份的初始化通知 */'''
b = '''/**
 * 逐灯投递报告（S1/R44-04）：目标集合在发起时冻结，结果逐 MAC。
 * 聚合（All/Some/None）由数据推导，UI 不再消费折叠后的全局布尔位。
 */
data class DeliveryReport(
    val targets: Set<String>,
    val results: Map<String, Boolean>,
) {
    val allOk: Boolean get() = targets.isNotEmpty() && results.values.all { it }
    val failedMacs: Set<String> get() = results.filterValues { !it }.keys

    /** 兼容旧枚举（过渡期）：SendResult 语义保持不变 */
    fun toSendResult(): SendResult = when {
        allOk -> SendResult.SentToAll
        results.values.any { it } -> SendResult.SentToSome
        targets.isEmpty() -> SendResult.NoTarget
        else -> SendResult.Failed
    }
}

/** 会话就绪事件（Q2）：携带会话身份的初始化通知 */'''
assert a in s, "S1-L0"
s = s.replace(a, b, 1)
a = '''    suspend fun sendTo(mac: String, frame: ByteArray): Boolean'''
b = '''    suspend fun sendTo(mac: String, frame: ByteArray): Boolean

    /**
     * 逐灯投递（S1）：目标集合=发起时全部 CONNECTED 会话（冻结），
     * 返回逐 MAC 真实结果。多灯部分失败不再折叠为全局成功。
     */
    suspend fun sendDetailed(frame: ByteArray): DeliveryReport'''
assert a in s, "S1-L1"
s = s.replace(a, b, 1)
open(p, 'w', encoding='utf-8', newline='').write(s)

# 2. FastBle：sendDetailed + send 委托
p = 'app/src/main/java/io/github/deserthouse/quartzlamp/ble/FastBleLampConnection.kt'
s = open(p, encoding='utf-8').read()
start = s.index('    override suspend fun send(frame: ByteArray): SendResult {')
end = s.index('    override suspend fun queryTimer(open: Boolean): ByteArray?')
b = '''    override suspend fun sendDetailed(frame: ByteArray): DeliveryReport {
        val hex = LampFrames.hex(frame)
        Log.d("BLE-WRITE", hex)
        LampConnection.sharedFrameLog.tryEmit(hex)
        recordFrame(hex)
        // 帧#6（BC 09）律动流 → conflate；其余控制帧按序排队（R19-04）
        val reactive = frame.size > 1 && frame[0] == 0xBC.toByte() && frame[1] == 0x09.toByte()
        // S1：目标集合发起时冻结——新加入灯不属于本次操作目标
        val targets = _sessions.value.filterValues { it == SessionState.CONNECTED }.keys.toSet()
        if (targets.isEmpty()) return DeliveryReport(emptySet(), emptyMap())
        val results = HashMap<String, Boolean>()
        for (mac in targets) {
            val q = queues[mac] ?: run { results[mac] = false; continue }
            results[mac] = if (reactive) q.postReactiveAwait(frame) else q.write(frame)
        }
        return DeliveryReport(targets, results)
    }

    override suspend fun send(frame: ByteArray): SendResult = sendDetailed(frame).toSendResult()

'''
s = s[:start] + b + s[end:]
open(p, 'w', encoding='utf-8', newline='').write(s)

# 3. Fake：sendDetailed
p = 'app/src/debug/java/io/github/deserthouse/quartzlamp/ble/FakeLampConnection.kt'
s = open(p, encoding='utf-8').read()
a = '''    override suspend fun send(frame: ByteArray): SendResult {'''
b = '''    override suspend fun sendDetailed(frame: ByteArray): DeliveryReport {
        val hex = LampFrames.hex(frame)
        _frameHistory.value = (listOf(hex) + _frameHistory.value).take(LampConnection.FRAME_HISTORY_LIMIT)
        val targets = _sessions.value.filterValues { it == SessionState.CONNECTED }.keys.toSet()
        return DeliveryReport(targets, targets.associateWith { true })
    }

    override suspend fun send(frame: ByteArray): SendResult = sendDetailed(frame).toSendResult()

'''
assert a in s, "S1-Fake"
s = s.replace(a, b, 1)
open(p, 'w', encoding='utf-8', newline='').write(s)

# 4. Test：sendDetailed（可注入失败 MAC）
p = 'app/src/test/java/io/github/deserthouse/quartzlamp/ui/TestLampConnection.kt'
s = open(p, encoding='utf-8').read()
a = '''    override suspend fun send(frame: ByteArray): SendResult {'''
b = '''    /** S1 测试钩子：该 MAC 投递失败（部分成功反例） */
    var failMac: String? = null

    override suspend fun sendDetailed(frame: ByteArray): DeliveryReport {
        val targets = sessions.value.filterValues { it == SessionState.CONNECTED }.keys.toSet()
        val hex = LampFrames.hex(frame)
        sentFrames += hex
        return DeliveryReport(targets, targets.associateWith { it != failMac })
    }

    override suspend fun send(frame: ByteArray): SendResult = sendDetailed(frame).toSendResult()

'''
assert a in s, "S1-Test"
s = s.replace(a, b, 1)
open(p, 'w', encoding='utf-8', newline='').write(s)
print("S1 layer applied")
