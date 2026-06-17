package com.example.chaserpa.ui

// Android 意图类，用于跳转到系统设置页面
import android.content.Intent
// Android 系统设置常量
import android.provider.Settings
// Compose 布局相关：排列方式、列、行、滚动状态、间距等
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
// Compose Material3 组件库
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
// Compose 状态管理相关
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
// Compose UI 基础：Modifier 用于修改组件的尺寸、边距、点击等
import androidx.compose.ui.Modifier
// Android Toast 提示
import android.widget.Toast
// Compose 中获取当前上下文、剪贴板管理器
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
// Compose 文本相关
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
// Compose 尺寸单位：dp 表示密度无关像素
import androidx.compose.ui.unit.dp
// 项目内部依赖
import com.example.chaserpa.data.ConfigRepository
import com.example.chaserpa.service.MessageLog
import com.example.chaserpa.service.WeWorkAccessibilityService

/**
 * 配置页面。
 *
 * 这是 App 的主界面，用户可以在这里：
 * 1. 查看无障碍服务是否运行；
 * 2. 开启/暂停监控；
 * 3. 配置后台地址、API Key、目标群、昵称等；
 * 4. 调整轮询间隔；
 * 5. 查看实时日志并复制/清空。
 *
 * Kotlin/Compose 语法提示：
 * - @OptIn(ExperimentalMaterial3Api::class) 表示“我明确知道这个 API 还是实验性的，我要使用它”。
 *   类似 Java 中 @SuppressWarnings("deprecation") 或预览版 API 的标记。
 * - @Composable 表示这是一个 Compose UI 组件函数，不能直接返回普通数据，而是描述一段界面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen() {
    // LocalContext.current 获取当前 Composable 所在的 Context，相当于 Activity。
    // 在 Compose 中不要直接持有 Activity，而是通过 LocalContext 按需获取。
    val context = LocalContext.current

    // 获取系统剪贴板管理器，用于“复制日志”功能。
    val clipboardManager = LocalClipboardManager.current

    // remember { ... } 是 Compose 的状态记忆机制：
    // 在界面重组（recomposition）时保留对象，不会每次重新创建。
    // ConfigRepository 负责读写 SharedPreferences（本地持久化配置）。
    val configRepository = remember { ConfigRepository(context) }

    /**
     * 以下是一系列界面状态变量。
     *
     * Kotlin 语法提示：
     * - mutableStateOf(value) 创建可观察状态。状态变化会自动触发 Compose 重新绘制相关 UI。
     * - var xxx by remember { mutableStateOf(...) } 是常见写法：
     *   - var 表示可变变量；
     *   - by 是委托语法，相当于让 mutableState 的 get/set 代理这个变量；
     *   - 这样写之后直接写 backendUrl = "xxx" 就能触发界面更新。
     * - 如果不用 by，需要写 backendUrl.value = "xxx"。
     */
    var backendUrl by remember { mutableStateOf(configRepository.backendUrl) }
    var apiKey by remember { mutableStateOf(configRepository.apiKey) }
    var targetGroups by remember {
        // joinToString 把集合用指定分隔符连接成字符串，类似 Java String.join(", ", set)
        mutableStateOf(configRepository.targetGroups.joinToString(", "))
    }
    var myNickname by remember { mutableStateOf(configRepository.myNickname) }
    var pollInterval by remember { mutableStateOf(configRepository.pollInterval) }
    var adaptivePoll by remember { mutableStateOf(configRepository.adaptivePoll) }
    var replyBackendUrl by remember { mutableStateOf(configRepository.replyBackendUrl) }
    var replyPollInterval by remember { mutableStateOf(configRepository.replyPollInterval) }
    var monitoringEnabled by remember { mutableStateOf(configRepository.monitoringEnabled) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    // WeWorkAccessibilityService.isRunning 是一个伴生对象（companion object）中的属性，
    // 类似 Java 的 public static boolean isRunning，用于判断无障碍服务是否正在运行。
    val serviceRunning = WeWorkAccessibilityService.isRunning

    /**
     * Scaffold 是 Material3 提供的页面骨架组件，包含顶部栏、底部栏、浮动按钮、内容区域等插槽。
     * 这里只用了 topBar（顶部标题栏）。
     */
    Scaffold(
        topBar = {
            // TopAppBar 是顶部标题栏。
            // title 参数接收一个 @Composable Lambda，所以可以直接写 { Text(...) }。
            TopAppBar(
                title = { Text("企业微信消息推送配置") }
            )
        }
    ) { innerPadding ->
        /**
         * Column 是 Compose 的垂直布局容器，类似 Android 传统 View 中的 LinearLayout(vertical)。
         *
         * Modifier 是 Compose 中非常重要的概念，用于给组件添加样式和行为。
         * Kotlin 语法提示：
         * - Modifier.padding(innerPadding).padding(16.dp).fillMaxWidth()... 这种链式调用
         *   等价于 Java 中的 builder 模式：new Modifier.Builder().padding(...).padding(...).build()。
         * - 16.dp 是扩展属性，把数字转成 Compose 的 Dp 单位。
         * - verticalScroll(rememberScrollState()) 让 Column 可以垂直滚动。
         */
        Column(
            modifier = Modifier
                .padding(innerPadding)        // 先预留出标题栏/导航栏的安全区域
                .padding(16.dp)               // 再给内容四周加 16dp 内边距
                .fillMaxWidth()               // 宽度占满父容器
                .verticalScroll(rememberScrollState()) // 支持垂直滚动
        ) {
            /**
             * 显示无障碍服务运行状态。
             *
             * Text 的 color 使用 if/else 表达式直接返回值，
             * Kotlin 中 if 是表达式，等价于 Java 的三目运算符：
             * serviceRunning ? primary : error
             */
            Text(
                text = if (serviceRunning) "✓ 无障碍服务运行中" else "✗ 无障碍服务未启动",
                style = MaterialTheme.typography.bodyLarge,
                color = if (serviceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )

            // 如果无障碍服务没启动，显示一个跳转按钮引导用户去系统设置开启
            if (!serviceRunning) {
                // Spacer 是占位空白组件，类似 HTML 的 <div> 或 Android 的 Space。
                // height(8.dp) 表示高度 8dp。
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        // 构建一个跳转到系统“无障碍设置”的意图
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        // 启动系统设置页面。在 Android 10+ 后台启动 Activity 有限制，
                        // 这里从当前 Activity 启动是允许的。
                        context.startActivity(intent)
                    }
                ) {
                    Text("去开启无障碍服务")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            /**
             * 监控总开关行。
             *
             * Row 是水平布局容器，类似 LinearLayout(horizontal)。
             * verticalAlignment 设置子元素垂直方向对齐方式。
             */
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Column 默认权重为 1，会占据 Row 中剩余的所有宽度，把 Switch 挤到最右边
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
                // Switch 是 Material3 的开关组件
                Switch(
                    checked = monitoringEnabled,
                    onCheckedChange = {
                        // it 是 Kotlin Lambda 单参数的默认名字，相当于 Java Lambda 的 (Boolean isChecked) -> { ... }
                        monitoringEnabled = it
                        // 把新状态保存到本地配置
                        configRepository.monitoringEnabled = it
                        // 通知无障碍服务更新监控状态
                        WeWorkAccessibilityService.updateMonitoringState(it)
                        savedMessage = if (it) "监控已开启" else "监控已暂停"
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            /**
             * OutlinedTextField 是带边框的输入框，类似 Android 传统 EditText。
             *
             * Kotlin 语法提示：
             * - value = backendUrl 表示输入框当前显示的内容。
             * - onValueChange = { backendUrl = it } 是输入变化时的回调，
             *   把新值写回状态变量，Compose 会自动刷新界面。
             * - label = { Text(...) } 是输入框的标签（浮动提示文字）。
             * - placeholder = { Text(...) } 是占位提示文字。
             */
            OutlinedTextField(
                value = backendUrl,
                onValueChange = { backendUrl = it },
                label = { Text("后台接口地址") },
                placeholder = { Text("https://your-backend.com/api/message") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri, // 键盘类型：URL 输入
                    imeAction = ImeAction.Next       // 右下角按钮显示“下一项”
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

            // 当前仅支持纯轮询模式，移除了混合模式切换入口
            Text(
                text = "监控模式: 纯轮询模式",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "仅依靠定时轮询抓取消息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 轮询间隔显示
            Text(
                text = "轮询间隔: ${pollInterval / 1000} 秒",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(4.dp))

            /**
             * Slider 是滑块组件。
             *
             * valueRange = 1000f..10000f 是 Kotlin 的闭区间，表示 1000 到 10000。
             * steps = 8 表示把区间分成 8 等份。
             */
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

            // 回复轮询间隔显示
            Text(
                text = "回复轮询间隔: ${replyPollInterval / 1000} 秒",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(4.dp))

            Slider(
                value = replyPollInterval.toFloat(),
                onValueChange = { replyPollInterval = it.toInt() },
                valueRange = 1000f..15000f,
                steps = 14,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "1.0 秒 - 15.0 秒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            /**
             * 自适应频率开关。
             * 开启后服务会根据消息活跃度自动调整轮询频率。
             */
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

            Spacer(modifier = Modifier.height(24.dp))

            /**
             * 保存配置按钮。
             *
             * Kotlin 语法提示：
             * - targetGroups.split(",") 把字符串按逗号切分成 List<String>。
             * - .map { it.trim() } 对列表中每个元素去前后空格，返回新列表。
             * - .filter { it.isNotEmpty() } 过滤掉空字符串。
             * - .toSet() 转成 Set，自动去重。
             * 这一整段链式调用类似 Java Stream：
             *   Arrays.stream(...).map(...).filter(...).collect(Collectors.toSet())
             */
            Button(
                onClick = {
                    configRepository.backendUrl = backendUrl.trim()
                    configRepository.apiKey = apiKey.trim()
                    configRepository.targetGroups = targetGroups.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    // 自动回复默认始终开启，不再提供界面开关
                    configRepository.autoReply = true
                    configRepository.myNickname = myNickname.trim()
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

            /**
             * savedMessage?.let { ... } 是 Kotlin 的空安全调用。
             * 只有当 savedMessage 不为 null 时才执行花括号里的代码。
             * 花括号里的 it 就是 savedMessage 的非空值。
             */
            savedMessage?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 实时日志标题
            Text(
                text = "实时日志（最近50条）",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            /**
             * 日志显示卡片。
             * Card 是带圆角和阴影的卡片容器。
             */
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
                    /**
                     * LazyColumn 是 Compose 的懒加载垂直列表，类似 RecyclerView。
                     * 它会根据可见区域只渲染当前屏幕上的 item，性能更好。
                     */
                    LazyColumn(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        /**
                         * items(...) 是 LazyColumn 的作用域函数，
                         * 遍历 MessageLog.logs 列表，为每一条日志创建一个 Text。
                         * 每个 item 的 Lambda 参数 log 就是当前日志字符串。
                         */
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

            /**
             * 日志操作按钮行。
             */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                // 复制全部日志
                Button(
                    onClick = {
                        if (MessageLog.logs.isNotEmpty()) {
                            // AnnotatedString 是 Compose 的富文本字符串，剪贴板需要这种类型
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
                // 只复制包含 AUTO 标签的日志
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
                // 清空日志
                Button(
                    onClick = { MessageLog.clear() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("清空")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(24.dp))

            // 使用说明文本
            Text(
                text = "使用说明：\n1. 填写后台地址和 API Key（留空则只打印日志）\n2. 输入要监控的群名称（必须与微信中显示的一致）\n3. 保存配置\n4. 在系统设置中开启 chaserpa 的无障碍服务\n5. 让企业微信在后台，目标群有新消息时会自动采集",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
