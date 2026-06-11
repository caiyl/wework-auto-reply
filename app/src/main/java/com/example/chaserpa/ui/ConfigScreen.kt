package com.example.chaserpa.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.chaserpa.data.ConfigRepository
import com.example.chaserpa.service.MessageLog
import com.example.chaserpa.service.WeWorkAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val configRepository = remember { ConfigRepository(context) }

    var backendUrl by remember { mutableStateOf(configRepository.backendUrl) }
    var apiKey by remember { mutableStateOf(configRepository.apiKey) }
    var targetGroups by remember {
        mutableStateOf(configRepository.targetGroups.joinToString(", "))
    }
    var autoReply by remember { mutableStateOf(configRepository.autoReply) }
    var myNickname by remember { mutableStateOf(configRepository.myNickname) }
    var monitorMode by remember { mutableStateOf(configRepository.monitorMode) }
    var pollInterval by remember { mutableStateOf(configRepository.pollInterval) }
    var adaptivePoll by remember { mutableStateOf(configRepository.adaptivePoll) }
    var replyBackendUrl by remember { mutableStateOf(configRepository.replyBackendUrl) }
    var replyPollInterval by remember { mutableStateOf(configRepository.replyPollInterval) }
    var monitoringEnabled by remember { mutableStateOf(configRepository.monitoringEnabled) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    val serviceRunning = WeWorkAccessibilityService.isRunning

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("企业微信消息推送配置") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = if (serviceRunning) "✓ 无障碍服务运行中" else "✗ 无障碍服务未启动",
                style = MaterialTheme.typography.bodyLarge,
                color = if (serviceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )

            if (!serviceRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                ) {
                    Text("去开启无障碍服务")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (monitoringEnabled) "✅ 监控已开启" else "⏸️ 监控已暂停",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (monitoringEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "关闭后可自由操作手机，不会干扰企业微信",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = monitoringEnabled,
                    onCheckedChange = {
                        monitoringEnabled = it
                        configRepository.monitoringEnabled = it
                        WeWorkAccessibilityService.updateMonitoringState(it)
                        savedMessage = if (it) "监控已开启" else "监控已暂停"
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = backendUrl,
                onValueChange = { backendUrl = it },
                label = { Text("后台接口地址") },
                placeholder = { Text("https://your-backend.com/api/message") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = replyBackendUrl,
                onValueChange = { replyBackendUrl = it },
                label = { Text("回复拉取地址（留空则复用上方地址）") },
                placeholder = { Text("https://your-backend.com/api/pending-replies") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = targetGroups,
                onValueChange = { targetGroups = it },
                label = { Text("目标群名称（用逗号分隔）") },
                placeholder = { Text("客户群A, 客户群B") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = myNickname,
                onValueChange = { myNickname = it },
                label = { Text("我的昵称（防死循环，选填）") },
                placeholder = { Text("填写后自动跳过自己发送的消息") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "监控模式",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (monitorMode == ConfigRepository.MonitorMode.HYBRID) {
                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("混合模式") }
                    OutlinedButton(onClick = { monitorMode = ConfigRepository.MonitorMode.POLLING_ONLY }, modifier = Modifier.weight(1f)) { Text("纯轮询模式") }
                } else {
                    OutlinedButton(onClick = { monitorMode = ConfigRepository.MonitorMode.HYBRID }, modifier = Modifier.weight(1f)) { Text("混合模式") }
                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("纯轮询模式") }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = when (monitorMode) {
                    ConfigRepository.MonitorMode.HYBRID -> "混合模式：无障碍事件 + 定时轮询双保险"
                    ConfigRepository.MonitorMode.POLLING_ONLY -> "纯轮询模式：仅依靠定时轮询抓取消息"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "轮询间隔: ${pollInterval / 1000} 秒",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(4.dp))

            Slider(
                value = pollInterval.toFloat(),
                onValueChange = { pollInterval = it.toInt() },
                valueRange = 1000f..10000f,
                steps = 8,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "1.0 秒 - 10.0 秒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "回复轮询间隔: ${replyPollInterval / 1000} 秒",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(4.dp))

            Slider(
                value = replyPollInterval.toFloat(),
                onValueChange = { replyPollInterval = it.toInt() },
                valueRange = 1000f..30000f,
                steps = 28,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "1.0 秒 - 30.0 秒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "自适应频率")
                    Text(
                        text = "忙时加快、闲时放慢，降低功耗",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = adaptivePoll,
                    onCheckedChange = { adaptivePoll = it }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "自动回复（收到：xxx）",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoReply,
                    onCheckedChange = { autoReply = it }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    configRepository.backendUrl = backendUrl.trim()
                    configRepository.apiKey = apiKey.trim()
                    configRepository.targetGroups = targetGroups.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    configRepository.autoReply = autoReply
                    configRepository.myNickname = myNickname.trim()
                    configRepository.monitorMode = monitorMode
                    configRepository.pollInterval = pollInterval
                    configRepository.adaptivePoll = adaptivePoll
                    configRepository.replyBackendUrl = replyBackendUrl.trim()
                    configRepository.replyPollInterval = replyPollInterval
                    savedMessage = "配置已保存"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存配置")
            }

            savedMessage?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "实时日志（最近50条）",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                if (MessageLog.logs.isEmpty()) {
                    Text(
                        text = "暂无日志，开启无障碍服务后消息将显示在这里",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        items(MessageLog.logs) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (MessageLog.logs.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(MessageLog.logs.joinToString("\n")))
                            Toast.makeText(context, "全部日志已复制", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "暂无日志", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("复制全部")
                }
                Button(
                    onClick = {
                        val autoLogs = MessageLog.getAutoLogs()
                        if (autoLogs.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(autoLogs.joinToString("\n")))
                            Toast.makeText(context, "AUTO日志已复制(${autoLogs.size}条)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "暂无AUTO日志", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("只复制AUTO")
                }
                Button(
                    onClick = { MessageLog.clear() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("清空")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "使用说明：\n1. 填写后台地址和 API Key（留空则只打印日志）\n2. 输入要监控的群名称（必须与微信中显示的一致）\n3. 保存配置\n4. 在系统设置中开启 chaserpa 的无障碍服务\n5. 让企业微信在后台，目标群有新消息时会自动采集",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
