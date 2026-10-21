# U2 M3E 编排补丁
import sys

# U2-1：页面标题收敛
for p, old_title in [
    ('app/src/main/java/io/github/deserthouse/quartzlamp/ui/screens/LightsScreen.kt', '灯效'),
    ('app/src/main/java/io/github/deserthouse/quartzlamp/ui/screens/TimerScreen.kt', '定时'),
    ('app/src/main/java/io/github/deserthouse/quartzlamp/ui/screens/DeviceScreen.kt', '设备'),
]:
    s = open(p, encoding='utf-8').read()
    a = 'Text("' + old_title + '", style = MaterialTheme.typography.headlineLarge)'
    b = 'Text("' + old_title + '", style = MaterialTheme.typography.titleLarge) // U2：收敛双头部占位（顶栏品牌+页标题）'
    assert a in s, p
    s = s.replace(a, b, 1)
    open(p, 'w', encoding='utf-8', newline='').write(s)

# U2-2：控制条宽屏并排
p = 'app/src/main/java/io/github/deserthouse/quartzlamp/ui/screens/LightsScreen.kt'
s = open(p, encoding='utf-8').read()
a = '''        // 速度+模式灵敏度控制条
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                CompactSlider(
                    label = "速度",
                    note = if (selectedQuandeng) "已选全灯类，上限 70" else null,
                    // AV04-04：显示发送值——限幅时 UI 与帧一致
                    value = if (selectedQuandeng) mode.speed.coerceAtMost(70) else mode.speed,
                    onDrag = { vm.updateSpeed(it) },
                    onRelease = { vm.sendSpeed() }
                )
                CompactSlider(
                    label = "灵敏度",
                    note = null,
                    value = mode.sensitivity,
                    onDrag = { vm.updateSensitivity(it) },
                    onRelease = { vm.sendSensitivity() }
                )
            }
        }'''
b = '''        // 速度+模式灵敏度控制条（U2：宽屏并排压缩垂直占位，窄屏堆叠；仍位于分段下方）
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
            BoxWithConstraints(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                val slider: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                    CompactSlider(
                        label = "速度",
                        note = if (selectedQuandeng) "已选全灯类，上限 70" else null,
                        // AV04-04：显示发送值——限幅时 UI 与帧一致
                        value = if (selectedQuandeng) mode.speed.coerceAtMost(70) else mode.speed,
                        onDrag = { vm.updateSpeed(it) },
                        onRelease = { vm.sendSpeed() },
                        modifier = Modifier.weight(1f)
                    )
                    CompactSlider(
                        label = "灵敏度",
                        note = null,
                        value = mode.sensitivity,
                        onDrag = { vm.updateSensitivity(it) },
                        onRelease = { vm.sendSensitivity() },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (maxWidth >= 380.dp) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        content = slider
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = slider)
                }
            }
        }'''
assert a in s, "control bar"
s = s.replace(a, b, 1)
a = '''@Composable
private fun CompactSlider(
    label: String,
    note: String?,
    value: Int,
    onDrag: (Int) -> Unit,
    onRelease: () -> Unit
) {
    Column {'''
b = '''@Composable
private fun CompactSlider(
    label: String,
    note: String?,
    value: Int,
    onDrag: (Int) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {'''
assert a in s, "CompactSlider sig"
s = s.replace(a, b, 1)

# U2-3：font2 效果卡单列
a = '''        val items = Effects.effects[tab]
        val groups = Effects.SUB_CAT_ORDER.map { c ->
            c to items.withIndex().filter { Effects.subCatOf(tab, it.value) == c }
        }.filter { it.second.isNotEmpty() }'''
b = '''        val items = Effects.effects[tab]
        // U2：大字下长名唯一区分词不被两行省略号吞掉——fontScale>1.3 切单列
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val cols = if (fontScale > 1.3f) 1 else 2
        val groups = Effects.SUB_CAT_ORDER.map { c ->
            c to items.withIndex().filter { Effects.subCatOf(tab, it.value) == c }
        }.filter { it.second.isNotEmpty() }'''
assert a in s, "grid cols"
s = s.replace(a, b, 1)
a = '''            members.chunked(2).forEach { rowItems ->'''
b = '''            members.chunked(cols).forEach { rowItems ->'''
assert a in s; s = s.replace(a, b, 1)
a = '''                    if (rowItems.size == 1) Spacer(Modifier.weight(1f)) // 奇数行占位对齐'''
b = '''                    if (rowItems.size == 1 && cols == 2) Spacer(Modifier.weight(1f)) // 奇数行占位对齐'''
assert a in s; s = s.replace(a, b, 1)
# imports
if 'import androidx.compose.foundation.layout.BoxWithConstraints' not in s:
    a = 'import androidx.compose.foundation.layout.Box'
    b = 'import androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.BoxWithConstraints'
    assert a in s; s = s.replace(a, b, 1)
open(p, 'w', encoding='utf-8', newline='').write(s)

# U2-4：定时主次操作
p = 'app/src/main/java/io/github/deserthouse/quartzlamp/ui/screens/TimerScreen.kt'
s = open(p, encoding='utf-8').read()
a = '''            Row {
                TextButton(onClick = { vm.saveTimerSlot(open) }) { Text("应用到灯") }
                TextButton(onClick = { vm.queryTimerSlot(open) }) { Text("从灯读取") }
            }'''
b = '''            // U2：应用为主操作（filled Button）、读取为次（text）——层级按任务频率
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveTimerSlot(open) }) { Text("应用到灯") }
                TextButton(onClick = { vm.queryTimerSlot(open) }) { Text("从灯读取") }
            }'''
assert a in s, "timer buttons"
s = s.replace(a, b, 1)
if 'import androidx.compose.material3.Button' not in s:
    a = 'import androidx.compose.material3.CircularProgressIndicator'
    b = 'import androidx.compose.material3.Button\nimport androidx.compose.material3.CircularProgressIndicator'
    assert a in s; s = s.replace(a, b, 1)
open(p, 'w', encoding='utf-8', newline='').write(s)

# U2-5：电源中性态
p = 'app/src/main/java/io/github/deserthouse/quartzlamp/ui/screens/DeviceScreen.kt'
s = open(p, encoding='utf-8').read()
a = '''    val color by vm.color.collectAsState()
    val powerOn by vm.powerOn.collectAsState()
    val powerIssue by vm.powerIssue.collectAsState()'''
b = '''    val color by vm.color.collectAsState()
    val powerOn by vm.powerOn.collectAsState()
    val powerIssue by vm.powerIssue.collectAsState()
    // U2：无已连接灯时电源钮呈中性（不伪装灯端观测）
    val sessions by vm.sessions.collectAsState()
    val anyConnected = sessions.values.any { it == SessionState.CONNECTED }'''
assert a in s, "power collect"
s = s.replace(a, b, 1)
a = '''                Surface(
                    shape = CircleShape,
                    color = if (powerOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                    contentColor = if (powerOn) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,'''
b = '''                Surface(
                    shape = CircleShape,
                    color = when {
                        !anyConnected -> MaterialTheme.colorScheme.surfaceVariant
                        powerOn -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surface
                    },
                    contentColor = when {
                        !anyConnected -> MaterialTheme.colorScheme.onSurfaceVariant
                        powerOn -> MaterialTheme.colorScheme.onPrimary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },'''
assert a in s, "power neutral"
s = s.replace(a, b, 1)
open(p, 'w', encoding='utf-8', newline='').write(s)
print("U2 applied")
