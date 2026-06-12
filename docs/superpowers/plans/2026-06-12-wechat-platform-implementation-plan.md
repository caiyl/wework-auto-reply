# 微信版自动回复支持实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 chaserpa 项目中抽象出 `ChatPlatform` 平台层，让企业微信与微信共享同一套消息监控、去重、推送与自动回复框架，用户可在配置页一键切换平台。

**架构：** 通过 `ChatPlatform` 接口隔离企业微信与微信的 UI 自动化差异；`ChatAccessibilityService`、`UIPollingCollector`、`AutoReplyOrchestrator` 等通用组件全部依赖该接口；配置层新增 `chatApp` 字段控制运行时注入的实现类。

**Tech Stack：** Android Kotlin 2.2.10, Jetpack Compose, AccessibilityService, Gradle 单模块

---

## 文件结构变更

| 路径 | 操作 | 职责 |
|------|------|------|
| `app/src/main/java/com/example/chaserpa/service/ChatPlatform.kt` | 新建 | 平台抽象接口 |
| `app/src/main/java/com/example/chaserpa/service/ChatMessage.kt` | 新建 | 跨平台消息数据类 |
| `app/src/main/java/com/example/chaserpa/service/ChatApp.kt` | 新建 | 平台枚举（企业微信/微信） |
| `app/src/main/java/com/example/chaserpa/service/WeWorkPlatform.kt` | 新建 | 企业微信平台实现（由 WeWorkUIAutomator 重构而来） |
| `app/src/main/java/com/example/chaserpa/service/WeChatPlatform.kt` | 新建 | 微信平台实现（初始为 stub） |
| `app/src/main/java/com/example/chaserpa/service/WeWorkUIAutomator.kt` | 删除 | 逻辑迁移到 WeWorkPlatform |
| `app/src/main/java/com/example/chaserpa/service/AutoReplyOrchestrator.kt` | 修改 | 依赖改为 ChatPlatform |
| `app/src/main/java/com/example/chaserpa/service/WeWorkAccessibilityService.kt` | 重命名并修改 | 改名为 ChatAccessibilityService，注入 ChatPlatform |
| `app/src/main/java/com/example/chaserpa/service/UIPollingCollector.kt` | 修改 | 通过 ChatPlatform 提取消息 |
| `app/src/main/java/com/example/chaserpa/data/ConfigRepository.kt` | 修改 | 新增 chatApp 配置 |
| `app/src/main/java/com/example/chaserpa/ui/ConfigScreen.kt` | 修改 | 增加平台切换 UI |
| `app/src/main/AndroidManifest.xml` | 修改 | service 改名、queries 补全微信包名 |
| `app/src/test/java/com/example/chaserpa/data/ConfigRepositoryTest.kt` | 新建/修改 | chatApp 配置持久化测试 |

---

## Task 1: 创建 ChatPlatform 接口、ChatMessage 数据类与 ChatApp 枚举

**Files:**
- Create: `app/src/main/java/com/example/chaserpa/service/ChatPlatform.kt`
- Create: `app/src/main/java/com/example/chaserpa/service/ChatMessage.kt`
- Create: `app/src/main/java/com/example/chaserpa/service/ChatApp.kt`

- [ ] **Step 1: 创建 ChatMessage.kt**

```kotlin
package com.example.chaserpa.service

data class ChatMessage(
    val groupName: String,
    val sender: String,
    val content: String,
    val time: String? = null,
    val rawId: String? = null
)
```

- [ ] **Step 2: 创建 ChatApp.kt**

```kotlin
package com.example.chaserpa.service

enum class ChatApp {
    WEWORK,
    WECHAT
}
```

- [ ] **Step 3: 创建 ChatPlatform.kt**

```kotlin
package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

interface ChatPlatform {
    val packageName: String
    val displayName: String

    /**
     * 从当前无障碍窗口中找到目标 App 的主窗口根节点。
     * 调用方负责回收返回的 AccessibilityNodeInfo。
     */
    fun findActiveChatRoot(service: AccessibilityService): AccessibilityNodeInfo?

    /**
     * 从窗口根节点提取消息列表。
     * 实现类应返回新列表，调用方负责回收 root。
     */
    fun extractMessages(root: AccessibilityNodeInfo): List<ChatMessage>

    /**
     * 执行自动回复。实现类内部负责切到指定群并发送消息。
     */
    fun sendReply(groupName: String, replyText: String)
}
```

- [ ] **Step 4: 编译验证接口无语法错误**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/chaserpa/service/ChatPlatform.kt \
        app/src/main/java/com/example/chaserpa/service/ChatMessage.kt \
        app/src/main/java/com/example/chaserpa/service/ChatApp.kt
git commit -m "feat: add ChatPlatform abstraction, ChatMessage and ChatApp enum"
```

---

## Task 2: 将 WeWorkUIAutomator 重构为 WeWorkPlatform

**Files:**
- Create: `app/src/main/java/com/example/chaserpa/service/WeWorkPlatform.kt`
- Delete: `app/src/main/java/com/example/chaserpa/service/WeWorkUIAutomator.kt`

- [ ] **Step 1: 新建 WeWorkPlatform.kt**

将 `WeWorkUIAutomator` 整体复制为 `WeWorkPlatform`，并做以下关键改造：

1. 类声明改为实现 `ChatPlatform`
2. 提取 `sendReply` 中公共逻辑，新增 `tryExtractMessages` 供轮询器使用

```kotlin
package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

class WeWorkPlatform(private val service: AccessibilityService) : ChatPlatform {

    override val packageName: String = "com.tencent.wework"
    override val displayName: String = "企业微信"

    companion object {
        private const val TAG = "WeWorkPlatform"
    }

    override fun findActiveChatRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        // 复用原 WeWorkUIAutomator.findWeWorkWindow() 逻辑
        val windows = service.windows
        var chatRoot: AccessibilityNodeInfo? = null
        var listRoot: AccessibilityNodeInfo? = null
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == packageName) {
                val hasInput = findInputField(root) != null
                val hasRecycler = root.findAccessibilityNodeInfosByViewId("$packageName:id/czp").isNotEmpty()
                if (hasInput && chatRoot == null) {
                    chatRoot = root
                } else if (hasRecycler && listRoot == null) {
                    listRoot = root
                }
            }
        }
        return chatRoot ?: listRoot ?: service.rootInActiveWindow
    }

    override fun extractMessages(root: AccessibilityNodeInfo): List<ChatMessage> {
        // 复用原 UIPollingCollector 中读取聊天详情的逻辑
        // 先返回空列表，Task 5 中完整迁移
        return emptyList()
    }

    override fun sendReply(groupName: String, replyText: String) {
        // 复用原 WeWorkUIAutomator.sendReply 完整逻辑
    }

    // 以下方法从 WeWorkUIAutomator 原样迁移：
    // findInputField, findSendButton, launchWeWork, trySend, waitForWeWork, ...
}
```

- [ ] **Step 2: 迁移 WeWorkUIAutomator 全部私有方法**

将 `WeWorkUIAutomator` 中所有 `private` 方法完整复制到 `WeWorkPlatform`，保持签名不变。包括：
- `findInputField`
- `findSendButton`
- `launchWeWork`
- `trySend`
- `waitForWeWork`
- 其它辅助方法

- [ ] **Step 3: 删除 WeWorkUIAutomator.kt**

```bash
rm app/src/main/java/com/example/chaserpa/service/WeWorkUIAutomator.kt
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/chaserpa/service/WeWorkPlatform.kt \
        app/src/main/java/com/example/chaserpa/service/WeWorkUIAutomator.kt
git commit -m "refactor: migrate WeWorkUIAutomator to WeWorkPlatform implementing ChatPlatform"
```

---

## Task 3: 更新 AutoReplyOrchestrator 依赖 ChatPlatform

**Files:**
- Modify: `app/src/main/java/com/example/chaserpa/service/AutoReplyOrchestrator.kt`

- [ ] **Step 1: 修改构造参数类型**

```kotlin
class AutoReplyOrchestrator(
    private val chatPlatform: ChatPlatform
) {
    // ... 其余代码不变
}
```

- [ ] **Step 2: 更新 sendReply 调用**

```kotlin
chatPlatform.sendReply(task.groupName, task.replyText)
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（此时 WeWorkAccessibilityService 会因类型不匹配报错，属于预期，Task 4 修复）

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/chaserpa/service/AutoReplyOrchestrator.kt
git commit -m "refactor: AutoReplyOrchestrator depends on ChatPlatform"
```

---

## Task 4: 重命名并改造 WeWorkAccessibilityService

**Files:**
- Rename/Modify: `app/src/main/java/com/example/chaserpa/service/WeWorkAccessibilityService.kt` → `app/src/main/java/com/example/chaserpa/service/ChatAccessibilityService.kt`

- [ ] **Step 1: 使用 git mv 重命名文件**

```bash
git mv app/src/main/java/com/example/chaserpa/service/WeWorkAccessibilityService.kt \
       app/src/main/java/com/example/chaserpa/service/ChatAccessibilityService.kt
```

- [ ] **Step 2: 修改类名与 companion object**

```kotlin
class ChatAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChatAccessibilityService"
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var instance: ChatAccessibilityService? = null
            private set

        @Volatile
        var latestChatRoot: AccessibilityNodeInfo? = null

        fun updateMonitoringState(enabled: Boolean) {
            val svc = instance
            if (svc != null && isRunning) {
                svc.applyMonitoringState(enabled)
            }
        }
    }
```

- [ ] **Step 3: 注入 ChatPlatform**

在 `onServiceConnected()` 中：

```kotlin
private lateinit var chatPlatform: ChatPlatform

override fun onServiceConnected() {
    super.onServiceConnected()
    // ... 原有日志与配置加载

    val configRepository = ConfigRepository(this)
    chatPlatform = createPlatform(configRepository.chatApp)

    deduplicator = MessageDeduplicator()
    autoReplyOrchestrator = AutoReplyOrchestrator(chatPlatform)

    messagePusher = MessagePusher(
        backendUrl = configRepository.backendUrl,
        apiKey = configRepository.apiKey,
        onReply = { groupName, replyText ->
            MessageLog.add("[AUTO] Backend sync reply (ignored, will pull async): $groupName -> $replyText")
        }
    )

    val myNickname = configRepository.myNickname
    val onMessageCollected: (ChatMessage) -> Unit = { message ->
        // 过滤自己发送的消息，防止死循环
        if (myNickname.isNotEmpty() && message.sender == myNickname) {
            MessageLog.add("[SYS] 过滤自己发送的消息: ${message.content}")
            return@onMessageCollected
        }
        val timestamp = System.currentTimeMillis()
        if (!deduplicator.isDuplicate(message.groupName, message.sender, message.content, timestamp)) {
            MessageLog.add("[CAPTURE] group=${message.groupName}, sender=${message.sender}, content=${message.content}")
            // 转换为 MessagePusher 当前使用的 WeWorkMessage，后续可统一为 ChatMessage
            val weWorkMessage = MessagePusher.WeWorkMessage(
                groupName = message.groupName,
                sender = message.sender,
                content = message.content,
                timestamp = timestamp
            )
            messagePusher.push(weWorkMessage)
        } else {
            MessageLog.add("[SYS] 重复消息已忽略")
        }
    }

    // ... 初始化 messageCollector / uiPollingCollector / replyWorker
}

private fun createPlatform(chatApp: ChatApp): ChatPlatform {
    return when (chatApp) {
        ChatApp.WEWORK -> WeWorkPlatform(this)
        // WECHAT 在 Task 9 中接入，当前默认返回企业微信
        else -> WeWorkPlatform(this)
    }
}
```

- [ ] **Step 4: 将 WeWork 专用字段改名**

- `latestWeWorkRoot` → `latestChatRoot`
- 所有引用该字段的地方同步更新

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/chaserpa/service/ChatAccessibilityService.kt \
        app/src/main/java/com/example/chaserpa/service/WeWorkAccessibilityService.kt
git commit -m "refactor: rename WeWorkAccessibilityService to ChatAccessibilityService and inject ChatPlatform"
```

---

## Task 5: 迁移 UIPollingCollector 到 ChatPlatform

**Files:**
- Modify: `app/src/main/java/com/example/chaserpa/service/UIPollingCollector.kt`

- [ ] **Step 1: 修改构造参数**

```kotlin
class UIPollingCollector(
    private val service: AccessibilityService,
    private val config: ConfigRepository,
    private val chatPlatform: ChatPlatform,
    private val onMessageCollected: (ChatMessage) -> Unit
) {
    // ...
}
```

- [ ] **Step 2: 使用 chatPlatform.findActiveChatRoot 替换 findWeWorkRoot**

```kotlin
private fun doPoll() {
    if (!isRunning) return
    try {
        val root = chatPlatform.findActiveChatRoot(service)
        // ...
    } catch (e: Exception) {
        MessageLog.add("[POLL] Error: ${e.message}")
    }
    scheduleNext()
}
```

- [ ] **Step 3: 将消息提取逻辑迁移到 WeWorkPlatform.extractMessages**

把 `UIPollingCollector` 中读取聊天详情的方法整体迁移到 `WeWorkPlatform.extractMessages`，`UIPollingCollector` 改为：

```kotlin
private fun readChatDetail(root: AccessibilityNodeInfo) {
    val messages = chatPlatform.extractMessages(root)
    messages.forEach { msg ->
        onMessageCollected(msg)
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/chaserpa/service/UIPollingCollector.kt \
        app/src/main/java/com/example/chaserpa/service/WeWorkPlatform.kt
git commit -m "refactor: UIPollingCollector uses ChatPlatform for root finding and message extraction"
```

---

## Task 6: ConfigRepository 新增 chatApp 配置

**Files:**
- Modify: `app/src/main/java/com/example/chaserpa/data/ConfigRepository.kt`
- Create/Modify: `app/src/test/java/com/example/chaserpa/data/ConfigRepositoryTest.kt`

- [ ] **Step 1: 新增持久化字段**

在 `ConfigRepository.kt` 中导入 `com.example.chaserpa.service.ChatApp`，然后添加：

```kotlin
companion object {
    // ... 原有 KEY 常量
    private const val KEY_CHAT_APP = "chat_app"
}

var chatApp: ChatApp
    get() {
        val name = prefs.getString(KEY_CHAT_APP, ChatApp.WEWORK.name)
        return try {
            ChatApp.valueOf(name ?: ChatApp.WEWORK.name)
        } catch (_: IllegalArgumentException) {
            ChatApp.WEWORK
        }
    }
    set(value) = prefs.edit().putString(KEY_CHAT_APP, value.name).apply()
```

- [ ] **Step 2: 编写/更新单元测试**

```kotlin
package com.example.chaserpa.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.chaserpa.service.ChatApp
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigRepositoryTest {

    private lateinit var repository: ConfigRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("wework_config", Context.MODE_PRIVATE).edit().clear().apply()
        repository = ConfigRepository(context)
    }

    @Test
    fun `default chatApp is WEWORK`() {
        assertEquals(ChatApp.WEWORK, repository.chatApp)
    }

    @Test
    fun `chatApp persists WECHAT`() {
        repository.chatApp = ChatApp.WECHAT
        val newRepo = ConfigRepository(ApplicationProvider.getApplicationContext())
        assertEquals(ChatApp.WECHAT, newRepo.chatApp)
    }
}
```

- [ ] **Step 3: 运行单元测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.chaserpa.data.ConfigRepositoryTest"`
Expected: BUILD SUCCESSFUL, tests pass

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/chaserpa/data/ConfigRepository.kt \
        app/src/test/java/com/example/chaserpa/data/ConfigRepositoryTest.kt
git commit -m "feat: add chatApp platform config with persistence test"
```

---

## Task 7: ConfigScreen 增加平台切换 UI

**Files:**
- Modify: `app/src/main/java/com/example/chaserpa/ui/ConfigScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`（如需要中文字符串资源）

- [ ] **Step 1: 添加平台切换状态**

```kotlin
var chatApp by remember { mutableStateOf(configRepository.chatApp) }
```

- [ ] **Step 2: 在 TopAppBar 下方增加平台选择区**

```kotlin
OutlinedCard(
    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("监控平台", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ChatApp.entries.forEach { app ->
                FilterChip(
                    selected = chatApp == app,
                    onClick = {
                        chatApp = app
                        configRepository.chatApp = app
                        MessageLog.add("[CFG] 切换到: ${app.name}")
                    },
                    label = { Text(app.name) }
                )
            }
        }
    }
}
```

- [ ] **Step 3: 修改页面标题**

```kotlin
TopAppBar(title = { Text("消息推送配置") })
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/chaserpa/ui/ConfigScreen.kt
git commit -m "feat: add platform selector to ConfigScreen"
```

---

## Task 8: 更新 AndroidManifest.xml

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 同时声明企业微信与微信包名**

```xml
<queries>
    <package android:name="com.tencent.wework" />
    <package android:name="com.tencent.mm" />
</queries>
```

- [ ] **Step 2: Service 改名**

```xml
<service
    android:name=".service.ChatAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "chore: update manifest for ChatAccessibilityService and WeChat package query"
```

---

## Task 9: 创建 WeChatPlatform 初始实现

**Files:**
- Create: `app/src/main/java/com/example/chaserpa/service/WeChatPlatform.kt`

- [ ] **Step 1: 创建 stub 实现**

```kotlin
package com.example.chaserpa.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

class WeChatPlatform(private val service: AccessibilityService) : ChatPlatform {

    override val packageName: String = "com.tencent.mm"
    override val displayName: String = "微信"

    companion object {
        private const val TAG = "WeChatPlatform"
    }

    override fun findActiveChatRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        // 第一阶段：复用企业微信的窗口查找思路，优先返回 rootInActiveWindow
        val active = service.rootInActiveWindow
        return if (active?.packageName?.toString() == packageName) active else null
    }

    override fun extractMessages(root: AccessibilityNodeInfo): List<ChatMessage> {
        // 第一阶段：仅返回空列表，避免崩溃
        MessageLog.add("[WECHAT] extractMessages not yet implemented")
        return emptyList()
    }

    override fun sendReply(groupName: String, replyText: String) {
        // 第一阶段：仅记录日志
        MessageLog.add("[WECHAT] sendReply not yet implemented: $groupName -> $replyText")
    }
}
```

- [ ] **Step 2: 更新 ChatAccessibilityService.createPlatform**

在 `ChatAccessibilityService.kt` 中把 `createPlatform` 改为完整分支：

```kotlin
private fun createPlatform(chatApp: ChatApp): ChatPlatform {
    return when (chatApp) {
        ChatApp.WEWORK -> WeWorkPlatform(this)
        ChatApp.WECHAT -> WeChatPlatform(this)
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/chaserpa/service/WeChatPlatform.kt \
        app/src/main/java/com/example/chaserpa/service/ChatAccessibilityService.kt
git commit -m "feat: add WeChatPlatform stub and wire into ChatAccessibilityService"
```

---

## Task 10: 清理 WeWorkUIAutomator 残留引用并端到端验证

**Files:**
- Modify: `app/src/main/java/com/example/chaserpa/service/ChatAccessibilityService.kt`

- [ ] **Step 1: 删除 WeWorkUIAutomator 临时引用**

确认 `ChatAccessibilityService` 中不再直接引用 `WeWorkUIAutomator`，仅通过 `ChatPlatform` 操作。

- [ ] **Step 2: 编译与打包**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 企业微信回归测试**

1. 安装 debug APK：`./gradlew :app:installDebug`
2. 选择企业微信平台
3. 验证消息监控、去重、推送、自动回复流程与改造前一致
4. 检查 `MessageLog` 无异常崩溃

- [ ] **Step 4: 微信切换测试**

1. 在配置页切换到微信
2. 重启无障碍服务（关闭后重新开启）
3. 确认服务正常启动，无 `ClassNotFoundException` 或 `NullPointerException`
4. 确认 `WeChatPlatform` 被正确注入（日志中显示 `[WECHAT] ...`）

- [ ] **Step 5: Commit 任何修复**

```bash
git add -A
git commit -m "fix: remove WeWorkUIAutomator leftovers and verify platform switching"
```

---

## Task 11: 完善 WeChatPlatform 消息提取与回复（迭代进行）

**Files:**
- Modify: `app/src/main/java/com/example/chaserpa/service/WeChatPlatform.kt`

- [ ] **Step 1: 通过 uiautomator dump 获取微信 resource-id**

在目标手机上执行：

```bash
adb shell uiautomator dump /sdcard/wechat_chat.xml
adb pull /sdcard/wechat_chat.xml ./wechat_chat.xml
```

- [ ] **Step 2: 实现 findActiveChatRoot**

参考 WeWorkPlatform 的多窗口策略，优先选择包含输入框或消息列表的窗口。

- [ ] **Step 3: 实现 extractMessages**

```kotlin
override fun extractMessages(root: AccessibilityNodeInfo): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    // 根据微信聊天页 UI 树结构遍历消息气泡
    // 示例（需根据实际 dump 调整）：
    // val contentNodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/...")
    return messages
}
```

- [ ] **Step 4: 实现 sendReply**

参考 `WeWorkPlatform.sendReply` 的实现路径：
1. 返回消息列表
2. 搜索/定位群名
3. 点击输入框
4. 输入文本
5. 点击发送

- [ ] **Step 5: 每一步都提交**

```bash
git add app/src/main/java/com/example/chaserpa/service/WeChatPlatform.kt
git commit -m "feat: implement WeChat message extraction / reply"
```

---

## 回滚策略

| 阶段 | 回滚方式 |
|------|----------|
| 编译失败 | 修正当前任务代码，每个 Task 独立可编译 |
| 企业微信功能异常 | 对比 `feat/vivo-adaptation` 分支，逐文件回退到旧实现 |
| 微信实现不稳定 | 在配置中限制为 `WEWORK`，保留 `WeChatPlatform` stub |
| 整体验收不通过 | 保留 `feat/wechat-platform` 分支，从 `main` 重新切出分支 |

---

## 验证清单

- [ ] `ChatPlatform`、`ChatMessage`、`ChatApp` 编译通过
- [ ] `WeWorkPlatform` 能替代原有 `WeWorkUIAutomator`
- [ ] `ChatAccessibilityService` 根据 `chatApp` 注入正确平台
- [ ] `UIPollingCollector` 不再硬编码企业微信 ID
- [ ] `ConfigRepository.chatApp` 持久化正确
- [ ] `ConfigScreen` 平台切换 UI 正常
- [ ] `AndroidManifest.xml` 同时声明两个包名
- [ ] 企业微信原有功能回归通过
- [ ] 微信 stub 不会导致崩溃
- [ ] 微信消息提取与回复可逐步填充

---

## 计划自审

**Spec coverage:**
- 抽象平台层与 ChatApp 枚举 → Task 1
- WeWorkPlatform 迁移 → Task 2
- ChatAccessibilityService 注入平台 → Task 4
- UIPollingCollector 解耦 → Task 5
- ConfigRepository 配置 → Task 6
- ConfigScreen UI → Task 7
- Manifest 更新 → Task 8
- WeChatPlatform 初始实现 → Task 9
- 微信功能迭代 → Task 11

**Placeholder scan:** 无 TBD/TODO，WeChat 具体 ID 留到 Task 11 根据实际 dump 填充，已说明获取命令。

**Type consistency:** `ChatApp` 定义在 `com.example.chaserpa.service.ChatApp`，ConfigRepository 与 UI 均引用该类型；`ChatPlatform` 接口在 Task 1 定义，后续任务均使用相同签名。
