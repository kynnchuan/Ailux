# Ailux 诊断报告（DiagnosticReport）

[← 返回 README](../README-zh.md) · [English](DIAGNOSTICS.md)

> 适用版本：v0.2.5+（v0.3.0b 起调用面收口到 Session 单链路，详见 [ADR-0009](../ailux-docs/decisions/adr/0009-session-only-single-pipeline.md)）
> 相关文档：[日志策略与隐私契约](LOGGING-zh.md) · [扩展性指南](EXTENSIBILITY-zh.md)

一句话：**`DiagnosticReport` 是 Ailux 给你的"可以直接粘进 GitHub Issue 的脱敏诊断快照"**。

设计原则三条：
1. **天然不含内容**——prompt、response、overrides、headers、body 五类敏感字段从不进入诊断。诊断里只有时序、错误码、重试次数、provider/model 名等元信息。
2. **任何配置都不能改变这一点**——和 `PrivacyConfig` 是两条平行铁律，不存在"开了 verbose 就泄露"的路径。
3. **任务级 + 会话级两个入口**——`task.lastDiagnostic()` 看单次请求；`Ailux.createDiagnosticReport()` 看最近 N 次请求 + SDK 全局快照。

---

## 1. 两个入口

### 任务级：`task.lastDiagnostic()`

`LLMTask` 进入终态（Completed / Failed / Cancelled）后，`lastDiagnostic()` 返回该任务的不可变报告；终态前返回 `null`。重复读取返回同一对象。

```kotlin
Ailux.openSession().use { session ->
    val task = session.streamGenerateAsTask(request)
    task.events.collect { /* ... */ }

    // 终态后任意时机读取
    task.lastDiagnostic()?.toShareableText()?.let { copyToClipboard(it) }
}
```

### 会话级：`Ailux.createDiagnosticReport(includeRecentTasks: Int = 5)`

聚合当前默认 client 的：
- SDK 版本 + provider 类名 + 当前 model 名
- 当前 `PrivacyConfig` 的开关快照
- 最近 N 次已结束任务的诊断（按最新优先），默认 5，最大上限由 client 内 ring buffer 控制（当前 16）。

```kotlin
// 用户反馈"刚才提问没出结果"时
val report = Ailux.createDiagnosticReport(includeRecentTasks = 5)
copyToClipboard(report.toShareableText())
```

多实例场景下用 `AiluxClient.createDiagnosticReport(...)` 直接对某个 client 调用。

## 2. `toShareableText()` 输出长这样

任务级：

```
=== Ailux Diagnostic ===
SDK: 0.2.5
Time: 2026-06-11T11:32:13Z
Task: chat-7f3a (provider=BackendProxyProvider, model=gpt-4o-mini)
Outcome: Failure(HttpException, code=RATE_LIMITED, "rate limited")
Timing: TTFT=312ms, total=1480ms
Retries: 2
  [0] http_429, delay=1000ms
  [1] http_429, delay=2000ms
Privacy: prompt=off, response=off, overrides=off, headers=off, body=off
========================
```

会话级（节选）：

```
=== Ailux Diagnostic ===
SDK: 0.2.5
Time: 2026-06-11T11:32:13Z
Scope: session (provider=BackendProxyProvider, model=gpt-4o)
Outcome: Pending
Retries: 0
Privacy: prompt=off, response=off, overrides=off, headers=off, body=off
Recent tasks: 3
  [0] task-C ok total=830ms
  [1] task-B fail/RATE_LIMITED total=420ms
  [2] task-A ok total=512ms
========================
```

格式约定：
- 纯 ASCII。无 emoji，无装饰符。
- 行首固定前缀（`SDK:` / `Time:` / `Task:` / `Outcome:` / `Timing:` / `Retries:` / `Privacy:` / `Recent tasks:`），grep 友好。
- 顶/尾两条 `=== ... ===` 分隔符固定。
- 这些约定**跨 patch 版本稳定**——配套工具可以信赖。

## 3. 数据模型

```kotlin
public data class DiagnosticReport(
    val sdkVersion: String,
    val timestamp: Long,
    val taskId: String?,              // null = 会话级
    val provider: String,
    val model: String?,
    val timing: TimingMetrics,
    val outcome: Outcome,             // Success / Failure / Cancelled / Pending
    val retries: List<RetryAttempt>,
    val privacy: PrivacyConfigSnapshot,
    val recentTasks: List<DiagnosticReport> = emptyList(),
    val notes: List<String> = emptyList(),
)
```

- `TimingMetrics` 提供 `startedAt` / `firstTokenAt` / `finishedAt` 三个时间戳与派生 `ttftMs` / `durationMs`。
- `Outcome` 是 sealed 家族：`Success` / `Failure(errorClass, errorCode, errorMessage)` / `Cancelled` / `Pending`。
- `RetryAttempt(index, reason, delayMs)` 记录每次自动重试的理由与等待时间。
- `PrivacyConfigSnapshot` 把当时的 5 个隐私开关固化下来——接收者一眼能看出哪些字段是被脱敏的、哪些是因关闭通道而缺失。

## 4. JSON 输出

```kotlin
val json = report.toJson()
```

手写、零依赖的稳定 JSON：
- 字段顺序固定。
- 字符串按 JSON 规范转义（`\\`、`\"`、`\n`、`\r`、`\t` 以及控制字符 `\uXXXX`）。
- 全部为基础类型，便于上报到自己的 telemetry 通道（Sentry breadcrumb / 内部 Kafka 等）。

> 设计上没有 `toShareableText()` 那么强的版本兼容承诺；如果你要长期机器解析，建议把 JSON schema 钉一份在你那边。

## 5. 与 Bug Issue Forms 串接

在 `.github/ISSUE_TEMPLATE/bug_report_zh.yml`（中文）/ `bug_report.yml`（英文）模板里已有"诊断报告（推荐）"字段，要求用户粘 `toShareableText()` 输出。三步走：

1. 用户在 demo / 自家 App 触发"复制诊断"按钮（demo 内置 Debug Panel；自家 App 自行接 ClipboardManager）。
2. App 调 `Ailux.createDiagnosticReport().toShareableText()`，拷到剪贴板。
3. 用户回到 GitHub 提 Issue，按模板粘贴。

因为诊断永远不含内容，无需提醒用户做二次脱敏。

## 6. 不该出现在诊断里的东西（合规自检表）

| 字段 | 出现在诊断里？ |
|---|:--:|
| `messages` 内容 | ❌ |
| `Token` / `Reasoning` 文本 | ❌ |
| `overrides` JSON | ❌ |
| HTTP headers（含 Authorization） | ❌ |
| HTTP request / response body | ❌ |
| API key / OAuth token | ❌ |
| 用户标识符（除非用户在 `notes` 自加） | ❌ |
| **以下是会出现的**：SDK 版本、provider 类名、model 名、错误码、错误消息（来自 SDK 自己的 `LLMError.message`）、TTFT / 总耗时、重试次数 | ✅ |

如果你扩展了 `RequestMapper` / `StreamResponseParser` / `AuthProvider` 并自己抛了带敏感信息的异常，那异常 `message` 会出现在 `Outcome.Failure.errorMessage` 里——这是你自己的合规边界，SDK 不能替你判断。**写自定义扩展点时，抛异常 message 要克制。**

## 7. FAQ

**Q：诊断报告会自动上传到 Ailux 服务器吗？**
A：不会。SDK 不内置任何上报通道。诊断只在本地由你的 App 决定怎么用（写日志、复制剪贴板、发自己的 telemetry、发 Issue 模板）。

**Q：能不能给单个 task 附自定义 notes？**
A：目前 `DiagnosticReport.notes` 只能在 v0.2.5 用 `createDiagnosticReport(...)` 后通过 `copy(notes = ...)` 自行追加。下个版本会评估"task 级 note() API"。

**Q：`recentTasks` 容量能调吗？**
A：当前固定 16，由 `AiluxClient` 内 ring buffer 控制。若有更大需求请提 Issue。

**Q：诊断为什么用 UTC 时间？**
A：跨时区粘贴 issue 时减少误判。如果需要本地时间，自己用 `report.timestamp` 重新格式化。
