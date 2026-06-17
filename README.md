# ChaserPA

Android 企业微信消息监控与自动回复助手。

## 功能

- 监控指定企业微信群的新消息
- 通过 HTTP POST 推送到后台接口
- 从后台拉取待回复消息并自动回复
- 自动保持企业微信在前台消息列表页

## 运行要求

- Android 7.0+（API 24）
- 已安装企业微信

## 必要权限配置

本应用依赖 Android 无障碍服务（AccessibilityService）实现 UI 自动化。除了无障碍权限外，由于需要把企业微信从后台拉回前台，**必须手动开启以下手机厂商权限**。

### vivo / OPPO / 小米 / 华为等国产 ROM

1. **后台弹出界面 / 后台启动**
   - 路径：设置 → 应用管理 → chaserpa → 权限 → 后台弹出界面（或后台启动）
   - 用途：从后台启动 `LaunchBridgeActivity`，进而拉起企业微信

2. **显示悬浮窗**
   - 路径：设置 → 应用管理 → chaserpa → 权限 → 显示悬浮窗
   - 用途：辅助应用切换到前台，提高拉起成功率

3. **允许后台高耗电 / 无限制**
   - 路径：设置 → 电池/电量管理 → 后台耗电管理 → chaserpa
   - 选择：允许后台高耗电 或 无限制
   - 用途：防止系统杀掉无障碍服务和后台轮询 Worker

4. **允许自启动**
   - 路径：设置 → 应用管理 → chaserpa → 自启动
   - 用途：设备重启后服务能自动恢复

### 为什么需要这些权限？

Android 10+ 限制了后台服务直接启动其他应用的 Activity。本应用使用了一个透明的 `LaunchBridgeActivity` 作为跳板：

1. `KeepAliveWorker` 检测到企业微信不在前台
2. 启动 `LaunchBridgeActivity`
3. `LaunchBridgeActivity` 以真实前台 Activity 上下文启动企业微信
4. 企业微信回到前台，`UIPollingCollector` 继续扫描消息列表

如果没有「后台弹出界面」等权限，第 2 步会被系统静默拦截，导致企业微信无法被拉回。

## 使用步骤

1. 打开 App，配置要监控的群名称
2. 填写「我的昵称」（用于过滤自己发送的消息，防止死循环）
3. 配置后台接口地址和 API Key（可选）
4. 点击「保存配置」
5. 在系统设置中开启 chaserpa 的无障碍服务
6. 在手机设置中开启上述后台权限
7. 回到 App，开启「监控」开关
8. 企业微信会被自动保持在前台消息列表页

## 主要模块

- `WeWorkAccessibilityService`：无障碍服务入口，管理所有 Worker
- `MessageCollector`：接收无障碍通知事件
- `UIPollingCollector`：定时扫描企业微信 UI 树读取消息
- `ReplyWorker`：从后台拉取待回复消息并执行自动回复
- `KeepAliveWorker`：每分钟检查企业微信是否在前台，缺失时拉回
- `WeWorkLauncher`：通过 `LaunchBridgeActivity` 绕过后台启动限制
- `MessagePusher`：把消息推送到后台
- `MessageDeduplicator`：3 分钟窗口内去重

## 构建

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```
