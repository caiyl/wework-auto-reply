package com.example.chaserpa.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.example.chaserpa.data.ConfigRepository
import com.example.chaserpa.service.MessageLog
import com.example.chaserpa.service.WeWorkAccessibilityService
import kotlinx.coroutines.launch

/**
 * 配置页面（美化版）。
 *
 * 采用 Material3 卡片分组 + 图标 + Snackbar 提示，让原本长列表式的配置页更有层次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val configRepository = remember { ConfigRepository(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // ==================== 状态变量 ====================
    var backendUrl by remember { mutableStateOf(configRepository.backendUrl) }
    var apiKey by remember { mutableStateOf(configRepository.apiKey) }
    var targetGroups by remember {
        mutableStateOf(configRepository.targetGroups.joinToString(", "))
    }
    var myNickname by remember { mutableStateOf(configRepository.myNickname) }
    var myNicknameError by remember { mutableStateOf(false) }
    var pollInterval by remember { mutableStateOf(configRepository.pollInterval) }
    var adaptivePoll by remember { mutableStateOf(configRepository.adaptivePoll) }
    var replyBackendUrl by remember { mutableStateOf(configRepository.replyBackendUrl) }
    var replyPollInterval by remember { mutableStateOf(configRepository.replyPollInterval) }
    var monitoringEnabled by remember { mutableStateOf(configRepository.monitoringEnabled) }

    // 实时查询无障碍服务在系统设置中是否已启用
    var serviceEnabled by remember {
        mutableStateOf(WeWorkAccessibilityService.isEnabledInSettings(context))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                serviceEnabled = WeWorkAccessibilityService.isEnabledInSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 显示 Snackbar 的辅助函数
    fun showSnackbar(message: String) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("企业微信消息推送配置") },
                actions = {
                    // 顶部小图标：点击刷新无障碍状态
                    IconButton(onClick = {
                        serviceEnabled = WeWorkAccessibilityService.isEnabledInSettings(context)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新状态"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ==================== 服务状态卡片 ====================
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionTitle(
                        icon = Icons.Default.Settings,
                        title = "无障碍服务",
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SuggestionChip(
                            onClick = { },
                            label = {
                                Text(
                                    if (serviceEnabled) "已启用" else "未启用",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            icon = {
                                Icon(
                                    imageVector = if (serviceEnabled) Icons.Default.CheckCircle else Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (serviceEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = if (serviceEnabled) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                }
                            )
                        )

                        if (!serviceEnabled) {
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("去开启")
                            }
                        }
                    }
                }
            }

            // ==================== 监控开关卡片 ====================
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (monitoringEnabled) "监控运行中" else "监控已暂停",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "关闭后可自由操作手机",
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
                            showSnackbar(if (it) "监控已开启" else "监控已暂停")
                        }
                    )
                }
            }

            // ==================== 基础配置卡片 ====================
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionTitle(
                        icon = Icons.Default.Groups,
                        title = "基础配置",
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = targetGroups,
                        onValueChange = { targetGroups = it },
                        label = { Text("目标群名称") },
                        placeholder = { Text("客户群A, 客户群B") },
                        leadingIcon = {
                            Icon(Icons.Default.Groups, contentDescription = null)
                        },
                        supportingText = { Text("用英文或中文逗号分隔") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = myNickname,
                        onValueChange = {
                            myNickname = it
                            myNicknameError = false
                        },
                        label = { Text("我的昵称") },
                        placeholder = { Text("填写你在企业微信中的昵称") },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null)
                        },
                        isError = myNicknameError,
                        supportingText = {
                            if (myNicknameError) {
                                Text("昵称不能为空", color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("用于过滤自己发送的消息，防止死循环")
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ==================== 轮询设置卡片 ====================
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionTitle(
                        icon = Icons.Default.Tune,
                        title = "轮询设置",
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 监控模式
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "监控模式：纯轮询模式",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "仅依靠定时轮询抓取消息",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // 轮询间隔
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "轮询间隔：${pollInterval / 1000} 秒",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Slider(
                        value = pollInterval.toFloat(),
                        onValueChange = { pollInterval = it.toInt() },
                        valueRange = 1000f..10000f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "1.0 秒 - 10.0 秒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // 回复轮询间隔
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "回复轮询间隔：${replyPollInterval / 1000} 秒",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Slider(
                        value = replyPollInterval.toFloat(),
                        onValueChange = { replyPollInterval = it.toInt() },
                        valueRange = 1000f..15000f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "1.0 秒 - 15.0 秒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // 自适应频率
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "自适应频率",
                                style = MaterialTheme.typography.bodyMedium
                            )
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
                }
            }

            // ==================== 后台接口卡片 ====================
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionTitle(
                        icon = Icons.Default.Build,
                        title = "后台接口",
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = { backendUrl = it },
                        label = { Text("后台接口地址") },
                        placeholder = { Text("https://your-backend.com/api/message") },
                        leadingIcon = {
                            Icon(Icons.Default.Link, contentDescription = null)
                        },
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
                        leadingIcon = {
                            Icon(Icons.Default.Key, contentDescription = null)
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = replyBackendUrl,
                        onValueChange = { replyBackendUrl = it },
                        label = { Text("回复拉取地址") },
                        placeholder = { Text("留空则复用上方地址") },
                        leadingIcon = {
                            Icon(Icons.Default.Link, contentDescription = null)
                        },
                        supportingText = { Text("用于拉取待回复消息，留空复用后台接口地址") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ==================== 保存按钮 ====================
            Button(
                onClick = {
                    if (myNickname.trim().isEmpty()) {
                        myNicknameError = true
                        showSnackbar("请填写我的昵称")
                        return@Button
                    }
                    myNicknameError = false

                    configRepository.backendUrl = backendUrl.trim()
                    configRepository.apiKey = apiKey.trim()
                    configRepository.targetGroups = targetGroups.split(",", "，")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    configRepository.autoReply = true
                    configRepository.myNickname = myNickname.trim()
                    configRepository.pollInterval = pollInterval
                    configRepository.adaptivePoll = adaptivePoll
                    configRepository.replyBackendUrl = replyBackendUrl.trim()
                    configRepository.replyPollInterval = replyPollInterval
                    showSnackbar("配置已保存")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("保存配置")
            }

            // ==================== 实时日志卡片 ====================
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionTitle(
                        icon = Icons.Default.Terminal,
                        title = "实时日志",
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        if (MessageLog.logs.isEmpty()) {
                            Text(
                                text = "暂无日志，开启无障碍服务后消息将显示在这里",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                items(MessageLog.logs) { log ->
                                    Text(
                                        text = log,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 3.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (log !== MessageLog.logs.last()) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (MessageLog.logs.isNotEmpty()) {
                                    clipboardManager.setText(AnnotatedString(MessageLog.logs.joinToString("\n")))
                                    showSnackbar("全部日志已复制")
                                } else {
                                    showSnackbar("暂无日志")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("复制", style = MaterialTheme.typography.labelLarge)
                        }

                        OutlinedButton(
                            onClick = {
                                val autoLogs = MessageLog.getAutoLogs()
                                if (autoLogs.isNotEmpty()) {
                                    clipboardManager.setText(AnnotatedString(autoLogs.joinToString("\n")))
                                    showSnackbar("AUTO日志已复制(${autoLogs.size}条)")
                                } else {
                                    showSnackbar("暂无AUTO日志")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("AUTO", style = MaterialTheme.typography.labelLarge)
                        }

                        TextButton(
                            onClick = { MessageLog.clear() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("清空", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // ==================== 使用说明 ====================
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionTitle(
                        icon = Icons.Default.Info,
                        title = "使用说明",
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. 输入要监控的群名称（必须与微信中显示的一致）\n" +
                                "2. 填写我的昵称（必填，用于过滤自己发送的消息，防止死循环）\n" +
                                "3. 填写后台地址和 API Key（留空则只打印日志）\n" +
                                "4. 保存配置\n" +
                                "5. 在系统设置中开启 chaserpa 的无障碍服务\n" +
                                "6. 让企业微信在后台，目标群有新消息时会自动采集",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 卡片分组标题组件。
 *
 * @param icon 标题左侧图标
 * @param title 标题文字
 * @param color 图标颜色
 */
@Composable
private fun SectionTitle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = color
        )
    }
}
