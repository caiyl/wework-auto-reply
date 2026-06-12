# 微信版自动回复支持设计文档

**日期：** 2026-06-12  
**分支：** `feat/wechat-platform`  
**状态：** 设计评审中  

## 1. 背景与目标

当前 `chaserpa` 项目仅支持企业微信（WeWork）消息监控与自动回复。业务方希望在一个 App 内同时支持微信（WeChat），用户可在配置页切换监控目标平台。

**核心约束：**
- 一个 App，一个安装包
- 用户可在企业微信与微信之间切换
- 不上架应用商店
- 重视代码复用与维护简单
- 两个平台功能尽量互不影响

## 2. 方案决策

采用 **方案 A：同一项目，抽象平台层**。

| 维度 | 方案 A（选中） | 方案 B（分模块） | 方案 C（新项目） |
|------|---------------|-----------------|-----------------|
| 代码复用 | 高 | 中 | 低 |
| 维护成本 | 低 | 中 | 高 |
| 功能隔离 | 中（接口隔离） | 高（物理隔离） | 高 |
| 包体积 | 一个包，略增 | 一个包，略增 | 两个包 |
| 用户切换 | 配置页一键切换 | 配置页一键切换 | 需安装两个 App |
| 与约束匹配度 | 最匹配 | 过度设计 | 不匹配 |

## 3. 高层架构

```
┌─────────────────────────────────────┐
│           ConfigScreen              │
│  ┌─────────────────────────────┐    │
│  │  平台切换：企业微信 / 微信   │    │
│  └─────────────────────────────┘    │
└──────────────┬──────────────────────┘
               │ ConfigRepository.chatApp
               ▼
┌─────────────────────────────────────┐
│        ChatAccessibilityService     │
│  ┌─────────────────────────────┐    │
│  │  ChatPlatform platform      │    │
│  │  (运行时根据配置注入)        │    │
│  └─────────────────────────────┘    │
└──────────────┬──────────────────────┘
               │
       ┌───────┴───────┐
       ▼               ▼
┌─────────────┐  ┌─────────────┐
│ WeWorkPlatform│  │ WeChatPlatform│
│ (企业微信实现)│  │ (微信实现)    │
└─────────────┘  └─────────────┘
```

通用组件（消息去重、推送、后台保活、配置持久化、日志）完全复用，不感知平台差异。

## 4. 核心抽象

### 4.1 ChatPlatform 接口

```kotlin
interface ChatPlatform {
    val packageName: String
    val displayName: String

    /** 从当前无障碍窗口中找到目标 App 的主窗口根节点 */
    fun findActiveChatRoot(service: AccessibilityService): AccessibilityNodeInfo?

    /** 从窗口根节点提取消息列表 */
    fun extractMessages(root: AccessibilityNodeInfo): List<ChatMessage>

    /** 执行自动回复 */
    fun sendReply(groupName: String, replyText: String)
}

data class ChatMessage(
    val groupName: String,
    val sender: String,
    val content: String,
    val time: String? = null,
    val rawId: String? = null
)
```

### 4.2 实现类

- `WeWorkPlatform`：迁移现有 `WeWorkUIAutomator` 逻辑，并整合消息列表/详情页 resource-id。
- `WeChatPlatform`：新实现，封装微信包名 `com.tencent.mm` 及对应 UI 路径。

## 5. 组件改造

### 5.1 AutoReplyOrchestrator

- 原依赖：`WeWorkUIAutomator`
- 新依赖：`ChatPlatform`
- 其它逻辑不变，继续负责串行化回复任务队列。

### 5.2 ChatAccessibilityService（原 WeWorkAccessibilityService）

- 重命名为 `ChatAccessibilityService`，体现平台无关性。
- 启动时读取 `ConfigRepository.chatApp`，创建对应 `ChatPlatform` 实例。
- 将窗口查找、消息提取、自动回复全部委托给 `ChatPlatform`。
- `latestWeWorkRoot` 等 WeWork 专用字段改为通用命名，如 `latestChatRoot`。

### 5.3 UIPollingCollector

- 不再硬编码企业微信 resource-id。
- 接收 `ChatPlatform` 实例，调用 `findActiveChatRoot()` 与 `extractMessages()`。
- 平台切换时重新初始化轮询器。

### 5.4 ConfigRepository

新增字段：

```kotlin
enum class ChatApp { WEWORK, WECHAT }

var chatApp: ChatApp
    get() = /* 读取 WEWORK 或 WECHAT，默认 WEWORK */
    set(value) = /* 持久化 */
```

保留原有所有配置项，确保企业微信用户升级后行为不变。

### 5.5 ConfigScreen

- 顶部增加平台切换控件（建议使用 `SingleChoiceSegmentedButtonRow` 或 RadioButton）。
- 标题从“企业微信消息推送配置”改为“消息推送配置”。
- 根据所选平台动态显示平台相关提示（如目标群名示例）。

### 5.6 AndroidManifest.xml

- Service 名称改为 `.service.ChatAccessibilityService`。
- `<queries>` 同时声明：
  - `com.tencent.wework`
  - `com.tencent.mm`
- Accessibility service 配置保持通用，不再过滤特定 package。

## 6. 数据流

1. 用户在 `ConfigScreen` 切换平台 → 写入 `ConfigRepository.chatApp`
2. `ChatAccessibilityService` 监听到配置变化（或在 `onServiceConnected` 时）重建 `ChatPlatform`
3. `UIPollingCollector` 使用当前 `ChatPlatform` 轮询窗口
4. 提取到消息后走通用链路：`MessageDeduplicator` → `MessagePusher` → 后端
5. 后端返回回复内容 → `ReplyWorker` / `AutoReplyOrchestrator` → `ChatPlatform.sendReply()`

## 7. 迁移步骤

按以下顺序实施，每步均可单独编译验证：

1. **新增接口与数据类**：创建 `ChatPlatform`、`ChatMessage`。
2. **重构企业微信实现**：将 `WeWorkUIAutomator` 改名为 `WeWorkPlatform`，实现 `ChatPlatform`。
3. **解耦服务层**：`ChatAccessibilityService` 依赖 `ChatPlatform`，通过配置注入 `WeWorkPlatform`。
4. **解耦轮询器**：`UIPollingCollector` 使用 `ChatPlatform`。
5. **配置层升级**：`ConfigRepository` 增加 `chatApp`，默认 `WEWORK`。
6. **UI 升级**：`ConfigScreen` 增加平台切换。
7. **Manifest 更新**：service 改名、queries 补全。
8. **新增微信实现**：创建 `WeChatPlatform` 空壳，逐步填充窗口查找、消息提取、自动回复。
9. **回归测试**：企业微信原有功能不受影响；微信功能逐步验证。

## 8. 测试策略

- **单元测试**：`MessageDeduplicator`、`ConfigRepository` 平台无关逻辑保持不变。
- **手动测试**：
  - 切换平台后服务正确重建
  - 企业微信原有监控/自动回复流程正常
  - 微信消息提取与回复流程正常
  - 切换平台时不会崩溃或消息丢失
- **兼容性测试**：至少覆盖当前已适配的 vivo/OPPO 机型。

## 9. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 微信 UI 结构差异大 | 微信实现周期长 | 先抽象接口，再逐步填充；微信可先实现最小可用路径 |
| 重构破坏企业微信功能 | 高 | 每步重构后都进行完整企业微信回归测试 |
| 平台切换状态不一致 | 中 | 切换时停止旧轮询、清空队列、重建平台实例 |
| 微信自动化政策风险 | 中 | 企业内部自用，不上架；保留日志便于排查 |

## 10. 待确认事项

- [ ] 是否直接重命名 `WeWorkAccessibilityService` 为 `ChatAccessibilityService`？（推荐直接重命名，保持清晰）
- [ ] 平台切换控件使用 SegmentedButton 还是 RadioButton？（推荐 SegmentedButton，与 Material3 风格一致）
- [ ] 微信实现是否优先支持群聊，还是先支持单聊？（推荐先群聊，与原场景对齐）

---

**下一步：** 基于本文档制定详细实施计划（`writing-plans`）。
