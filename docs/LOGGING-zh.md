# Ailux 日志策略与隐私契约

[← 返回 README](../README-zh.md) · [English](LOGGING.md)

> 适用版本：v0.2.5+
> 关联设计：[`v0.2.5 spec`](../ailux-docs/specs/v0.2/v0.2.5-extensibility-and-privacy.md)
> 相关文档：[扩展性指南](EXTENSIBILITY-zh.md)

Ailux 的日志策略一句话：**Secure by default — 即便你接入了一个会写所有内容的 logger，prompt / response / overrides 也不会出现在 logcat 里**，除非你显式打开。

本文讲三件事：
1. 默认行为（你**什么都不做**会发生什么）。
2. `AiluxLogger` SPI（怎么把 SDK 日志桥到 Timber / SLF4J / Sentry）。
3. `PrivacyConfig`（怎么显式打开/关闭哪些字段）。

---

## 1. 默认行为：什么都不做时发生什么？

```kotlin
// 最小配置 —— 没有显式传 logger / privacy
val config = AiluxConfig(providerConfig = backendConfig)
```

| 字段 | 默认行为 |
|---|---|
| `logger` | `NoopAiluxLogger` —— **完全静默**，连 logcat 都不写 |
| `privacy.logPrompt` | `false` —— 即便你把 logger 换成会写的，prompt 内容仍被替换为 `[prompt redacted: ***]` |
| `privacy.logResponse` | `false` —— 同上，token 流 / reasoning / 响应体被脱敏 |
| `privacy.logOverrides` | `false` —— `overrides` 内容（可能包含 trace_id / user_id 等敏感信息）被脱敏 |
| `Authorization` 头 / API key | **永不**进入日志（在调用点剥离，不依赖 `PrivacyConfig`） |

**这是 secure-by-default 的最严格姿态**：上线生产无需做任何事，SDK 不会把任何敏感字段写出来。

## 2. `AiluxLogger` —— 把 SDK 日志桥到你的栈

`AiluxLogger` 是 `interface`（不是 `fun interface`，因为带 5 个 default 便捷方法 `v/d/i/w/e`）：

```kotlin
public interface AiluxLogger {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)
    // 5 个 default 便捷方法：
    fun v(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.VERBOSE, ...)
    fun d(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.DEBUG, ...)
    fun i(...) ; fun w(...) ; fun e(...)
}
```

实现方只需重写 `log(...)` 一个方法，调用方写 `logger.d("Tag", "msg")` 即可。

### 内置实现

| 实现 | 模块 | 用途 |
|---|---|---|
| `NoopAiluxLogger` | `ailux-core` | 默认。生产推荐。零开销、零输出。 |
| `AndroidAiluxLogger` | `ailux-android` | 桥接 `android.util.Log`。**开发期推荐**，写 logcat。仍受 `PrivacyConfig` 兜底。 |

### 桥到 Timber

```kotlin
object TimberLogger : AiluxLogger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.VERBOSE -> Timber.tag(tag).v(throwable, message)
            LogLevel.DEBUG   -> Timber.tag(tag).d(throwable, message)
            LogLevel.INFO    -> Timber.tag(tag).i(throwable, message)
            LogLevel.WARN    -> Timber.tag(tag).w(throwable, message)
            LogLevel.ERROR   -> Timber.tag(tag).e(throwable, message)
        }
    }
}

val config = AiluxConfig(
    providerConfig = backendConfig,
    logger = TimberLogger,
)
```

### 桥到 SLF4J

```kotlin
class Slf4jLogger(private val base: org.slf4j.Logger) : AiluxLogger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val msg = "[$tag] $message"
        when (level) {
            LogLevel.VERBOSE, LogLevel.DEBUG -> base.debug(msg, throwable)
            LogLevel.INFO  -> base.info(msg, throwable)
            LogLevel.WARN  -> base.warn(msg, throwable)
            LogLevel.ERROR -> base.error(msg, throwable)
        }
    }
}
```

### 桥到 Sentry / 任何"只把异常上报"的栈

```kotlin
object SentryLogger : AiluxLogger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (throwable != null && level >= LogLevel.WARN) {
            Sentry.captureException(throwable, mapOf("ailux_tag" to tag, "msg" to message))
        }
        // 信息级丢弃，避免 Sentry quota 爆炸
    }
}
```

> **隐私契约**：所有桥接实现收到的 `message` 已经被 SDK 内部的 `RedactingLogSink` 按 `PrivacyConfig` 处理过——你**不需要**自己再做一遍脱敏。

## 3. `PrivacyConfig` —— 显式打开你需要看到的字段

```kotlin
public data class PrivacyConfig(
    val logPrompt: Boolean = false,
    val logResponse: Boolean = false,
    val logOverrides: Boolean = false,
    val logHeaders: Boolean = false,         // 是否打 HTTP headers（Authorization 仍强制脱敏）
    val logRequestBody: Boolean = false,     // 是否打完整 HTTP request body
    val redactionMask: String = "***",
    val maxLoggedBodyLength: Int = 2048,     // body 类日志的最大字符数，超长截断
)
```

### 预设常量

| 常量 | 等价于 | 适用 |
|---|---|---|
| `PrivacyConfig.SECURE_DEFAULT` | 全 false / 2048 上限 | 生产构建。等同 `PrivacyConfig()`。 |
| `PrivacyConfig.DEBUG_VERBOSE` | `logPrompt=true, logResponse=true, logOverrides=true`（headers/body 仍关） | 短期 debug。**禁止在 release 构建里硬编码使用**。 |

### 行为表

| 场景 | logPrompt | logResponse | logOverrides | logHeaders | logRequestBody | 结果 |
|---|:--:|:--:|:--:|:--:|:--:|---|
| 默认（生产推荐） | `false` | `false` | `false` | `false` | `false` | 所有内容字段被替换为 `[xxx redacted: ***]` |
| `DEBUG_VERBOSE` 预设 | `true` | `true` | `true` | `false` | `false` | prompt/response/overrides 完整写出；headers/body 仍脱敏 |
| 全开（仅本地极限排错） | `true` | `true` | `true` | `true` | `true` | 所有字段完整写出，**禁止上线** |
| 网关排错（看请求不看响应） | `true` | `false` | `true` | `false` | `false` | 只 prompt + overrides 完整 |
| 内容审核排错 | `false` | `true` | `false` | `false` | `false` | 只 response 完整 |

> `logHeaders=true` 时，`Authorization` / `Proxy-Authorization` / `Cookie` / `Set-Cookie` / `X-Api-Key` 这五类凭据头**仍然**会被替换为 `redactionMask`——不存在"开了 headers 就一定泄露凭据"的路径。

### 自定义脱敏标记

把默认 `***` 换成符合公司日志合规的形式（比如带 trace 的）：

```kotlin
val privacy = PrivacyConfig(redactionMask = "[REDACTED-${BuildConfig.VERSION_NAME}]")
val config = AiluxConfig.Builder()
    .setProvider(provider)
    .setPrivacyConfig(privacy)
    .build()
```

### "我想 debug 但不想留痕在生产"

```kotlin
val config = AiluxConfig.Builder()
    .setProvider(provider)
    .setPrivacyConfig(
        if (BuildConfig.DEBUG) PrivacyConfig.DEBUG_VERBOSE
        else PrivacyConfig.SECURE_DEFAULT
    )
    .build()
```

ProGuard / R8 会把 release 构建的 `BuildConfig.DEBUG=false` 常量折叠，整个 `if` 在 release 包里**编译期**就走到 `SECURE_DEFAULT`——零运行时分支、零误开风险。

### Body 截断

打开 `logRequestBody=true` 后，单次 body 超过 `maxLoggedBodyLength`（默认 2048 字符）会被截断，附带 `... [truncated: N chars dropped]` 提示。这是一个防御性上限，避免单条日志炸到几 MB 的"事故级日志"。设 `Int.MAX_VALUE` 可关闭。

## 4. SDK 字段分类（哪些默认记录、哪些默认脱敏）

| 类别 | 字段 | 默认记录？ | 控制开关 |
|---|---|:--:|---|
| 元信息 | `requestId`、`provider type`、`model name`、`HTTP status`、`错误码` | ✅ | 始终记录（`logSafe`） |
| 时序 | `TTFT`、`总耗时`、`stall 检测`、`重试次数` | ✅ | 始终记录（`logSafe`） |
| **请求内容** | `messages` 文本、`tools` 定义、`attachments` 元信息 | ❌ | `logPrompt` |
| **响应内容** | `Token` 文本、`Reasoning` 文本、`ToolCall.arguments`、HTTP response body | ❌ | `logResponse` |
| **覆盖参数** | `overrides` JSON 全文 | ❌ | `logOverrides` |
| **HTTP headers** | 全部出站 headers（含自定义 header） | ❌ | `logHeaders`（凭据头始终脱敏） |
| **HTTP request body** | 完整 JSON body | ❌ | `logRequestBody` |
| **凭据** | `Authorization`、`Proxy-Authorization`、`Cookie`、`Set-Cookie`、`X-Api-Key` | ❌ | **永远不可开**（强制剥离） |

## 5. 编写自定义扩展点时的隐私守则

写 [自定义 mapper / parser / errormapper / authprovider](EXTENSIBILITY-zh.md#第二部分--provider-四个扩展点决策树v025) 时，**直接调用** `AiluxLogger` 的便捷方法即可——SDK 在你看不见的地方已经过 `RedactingLogSink` 一道。

```kotlin
class MyParser(private val logger: AiluxLogger) : StreamResponseParser {
    override fun parse(eventType: String, data: String): List<LLMEvent> {
        // ✅ 安全：data 是响应内容，但通过 logSafe 不会写到 logger
        // ❌ 错误：你不应该自己把响应直接传 logger.d
        // 正确做法：扩展点不需要打日志；如确需，只打元信息
        if (eventType == "unknown_v3") {
            logger.w("MyParser", "unrecognised event=$eventType (forward-compat skip)")
        }
        return parseInternal(eventType, data)
    }
}
```

**铁律**：
1. 扩展点代码里的 `logger.d/e` 不要把 `data` / `request.messages` / `Authorization` 直接拼进 message。
2. 如果一定要 log 内容辅助 debug，自己加一个 build flag 控制，**不要**绕过 `PrivacyConfig`。

## 6. 报告 bug 时的脱敏诊断

`Ailux.createDiagnosticReport().toShareableText()` 生成的诊断报告**永远不含** prompt / response / overrides / headers / body——可放心粘到 GitHub Issue。详见 [DIAGNOSTICS-zh.md](DIAGNOSTICS-zh.md) 与 [Bug 报告模板](../.github/ISSUE_TEMPLATE/bug_report_zh.yml)。
