# Ailux 扩展性指南

[← 返回 README](../README-zh.md) · [English](EXTENSIBILITY.md)

> 适用版本：v0.2.4+（请求体三层模型）、v0.2.5+（Provider 扩展点决策树）
> 关联设计：[`ADR-0003`](../ailux-docs/decisions/adr/0003-llmrequest-extensibility-overrides.md)、[`v0.2.4 spec`](../ailux-docs/specs/v0.2/v0.2.4-llmrequest-extensibility.md)、[`v0.2.5 spec`](../ailux-docs/specs/v0.2/v0.2.5-extensibility-and-privacy.md)

本文档分两部分：
- **第一部分** — `LLMRequest` 请求体的**三层模型**（怎么把字段送进去）。
- **第二部分** — Provider 的**四个扩展点决策树**（什么时候该写自定义 mapper/parser/errormapper/auth）。

---

# 第一部分 · `LLMRequest` 三层扩展模型

`LLMRequest` 的扩展能力由三层组成。**先选层、再写代码**——选错层会让你要么改 SDK、要么写一整个 mapper，浪费时间也污染语义。

```text
                使用方需求：某个字段要进入请求体
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
 ① 强类型字段          ② overrides 逃生舱       ③ 自定义 RequestMapper
 (高频 ∧ 跨协议一致)   (单边 / 长尾 / 复杂)      (完全异构协议)
 model / temperature   buildJsonObject {         class MyMapper :
 / topP / maxTokens    put("seed", 42)            RequestMapper { ... }
 / stop / attachments  putJsonObject(...) {}     // 全掌控请求体
                      }
 类型安全、IDE 补全    零等待、结构化、可覆盖    终极、需改造整个 body
```

## 选层决策树

| 问题 | 选层 |
|---|---|
| 这个字段大多数主流后端都支持，且语义跨协议一致？ | **① 强类型** |
| 只有一两家后端支持，或者结构是数组/对象？ | **② `overrides`** |
| 后端协议根 schema 与 OpenAI / Anthropic 都不一样（自家 RPC、私有 wire format）？ | **③ 自定义 `RequestMapper`** |

`stop` / `attachments` 是 ① 的典型；`seed` / `response_format` / 厂商私有 `top_k` 是 ② 的典型；自家 RPC 协议、Bedrock 原生 invoke 是 ③ 的典型。

## 第 ① 层：强类型字段

满足"高频 ∧ 跨协议一致"才纳入。当前清单：

```kotlin
data class LLMRequest(
    val messages: List<Message>,
    val tools: List<ToolDefinition> = emptyList(),
    val toolChoice: String? = null,
    val role: String = "user",
    val model: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val maxTokens: Int? = null,
    val stop: List<String> = emptyList(),                // v0.2.4 +
    val attachments: List<Attachment> = emptyList(),     // v0.2.4 +
    val requestId: String = UUID.randomUUID().toString(),
    val overrides: JsonObject = JsonObject(emptyMap()),  // v0.2.4 + （取代 extras）
)
```

`stop` 在 OpenAI 上映射为 `stop`、在 Anthropic 上映射为 `stop_sequences`——这就是 ① 层的价值：**SDK 屏蔽协议差异**。

## 第 ② 层：`overrides` 结构化逃生舱

```kotlin
val req = LLMRequest(
    messages = listOf(Message.User("用 JSON 回答")),
    overrides = buildJsonObject {
        put("seed", 42)                                  // 数字
        put("frequency_penalty", 0.5)                    // 浮点
        putJsonObject("response_format") {               // 对象
            put("type", "json_object")
        }
        putJsonArray("stream_options") { /* ... */ }      // 数组
    },
)
```

行为：mapper 构造完请求体后，把 `overrides` 的键**逐个 merge 进请求体顶层**；同名**覆盖**（包括覆盖 `model` / `temperature` 这类强类型字段，作为终极逃生）。

`OpenAIRequestMapper` 与 `AnthropicRequestMapper` 都消费 `overrides`（修掉 v0.2.4 之前 Anthropic 静默忽略 `extras` 的 bug）。自定义 mapper 只需在 build 末尾调用：

```kotlin
import com.ailux.provider.backend.mapper.applyOverrides

class MyMapper : RequestMapper {
    override fun map(request: LLMRequest, stream: Boolean): String {
        val body = buildJsonObject {
            // ... 你的字段构造
            applyOverrides(request.overrides)  // 永远在最后一步
        }
        return body.toString()
    }
}
```

### 终极逃生：覆盖强类型字段

```kotlin
// 已知后端要求 temperature 是整型 1，绕过 SDK 的 Float
val req = LLMRequest(
    messages = listOf(Message.User("hi")),
    overrides = buildJsonObject { put("temperature", 1) },
)
```

> ⚠️ **代价**：覆盖强类型字段意味着你**自己负责**这次请求的合法性，绕过了 SDK 的所有合法性检查与跨协议映射。仅在确实"知道自己在干什么"时使用。

## 第 ③ 层：自定义 `RequestMapper`

只有当后端协议根 schema 与 OpenAI / Anthropic 都不兼容时才需要。`RequestMapper` 是 `fun interface`：

```kotlin
class CustomRpcMapper : RequestMapper {
    override fun map(request: LLMRequest, stream: Boolean): String {
        // 完全你说了算的请求体
    }
}

val config = BackendProxyConfig(
    baseUrl = "...",
    requestMapper = CustomRpcMapper(),
    streamResponseParser = CustomRpcParser(),
)
```

详情见 [API 参考](API-zh.md)。

---

## `extras → overrides` 迁移指南（v0.2.4）

`LLMRequest.extras: Map<String, String>` 在 v0.2.4 被 `overrides: JsonObject` 取代。这是**破坏性变更**（在 0.x 窗口可接受），收益是：① 支持结构化值（数字/对象/数组）；② 顶层 merge 而非被钉死进 OpenAI 的 `metadata` 子对象；③ 所有内置 mapper 一致消费（修复 Anthropic 历史上忽略 `extras` 的不一致）。

### 简单替换（值都是字符串）

```kotlin
// 旧（v0.2.3）：
LLMRequest(
    messages = ...,
    extras = mapOf("user_id" to "u-123"),
)

// 新（v0.2.4）：
LLMRequest(
    messages = ...,
    overrides = buildJsonObject {
        put("user_id", "u-123")
    },
)
```

### 受益升级：把字符串值改回原生类型

`extras` 时代为了塞数字/对象，常被迫把它们 toString，代价是后端要再 parse、且某些校验失败。`overrides` 直接传原生类型：

```kotlin
// v0.2.3 时代的悲剧：seed 只能传字符串，后端要 parse
extras = mapOf("seed" to "42")

// v0.2.4：seed 直接是 Int
overrides = buildJsonObject { put("seed", 42) }
```

### 注入位置变化

`extras` 在 OpenAI 上被钉死进 `metadata` 子对象、在 Anthropic 上被静默忽略。`overrides` 统一 merge 到请求体**顶层**。如果你的后端真的要求字段在 `metadata` 子对象内，迁移时显式表达：

```kotlin
overrides = buildJsonObject {
    putJsonObject("metadata") {
        put("user_id", "u-123")
        put("trace_id", traceId)
    }
}
```

### 无字符串值时构造空 `JsonObject`

```kotlin
// 不再写 emptyMap()
overrides = JsonObject(emptyMap())   // 或省略默认值
```

### 编译检查

升级到 v0.2.4 后，编译器会一次性把所有 `extras = ...` 调用点报红。逐个按上表替换即可——大多数项目只需一次 PR。

---

# 第二部分 · Provider 四个扩展点决策树（v0.2.5+）

第一部分讲了"字段怎么送进 `LLMRequest`"。但当后端**协议本身**和 OpenAI / Anthropic 不一样、或者错误码体系是自家业务码、或者要 OAuth 自动刷新 token 时——你需要的是 Provider 层的扩展点，不是 `overrides`。

Ailux 的 `BackendProxyProvider` 暴露了 **4 个扩展点**，全部是 `fun interface`，**不需要继承类，传 lambda 或单例即可**：

| 扩展点 | 接口 | 内置实现 | 替换时机（这就是决策树） |
|---|---|---|---|
| 请求体构造 | [`RequestMapper`](../ailux-provider-backend/src/main/java/com/ailux/provider/backend/mapper/RequestMapper.kt) | `OpenAIRequestMapper` / `AnthropicRequestMapper` | 后端协议**根 schema** 与 OpenAI/Anthropic 都不同（字段名/嵌套/顶层结构）。语义/参数差异先看 [`overrides`](#第--层overrides-结构化逃生舱)。 |
| SSE 事件解析 | [`StreamResponseParser`](../ailux-provider-backend/src/main/java/com/ailux/provider/backend/parser/StreamResponseParser.kt) | `OpenAIStreamResponseParser` / `AnthropicStreamResponseParser` | 后端 SSE 的 `event:` 名 / `data:` 形态自定义；或 FC 增量字段的累积逻辑不同。 |
| 错误归一 | [`ErrorMapper`](../ailux-provider-backend/src/main/java/com/ailux/provider/backend/mapper/ErrorMapper.kt) | `DefaultErrorMapper`（按标准 HTTP 状态码） | 业务错误码塞 body（典型：网关 HTTP 200 + `{"code":"GATEWAY_RATE_LIMITED"}`）；或 HTTP 状态码语义被你们覆盖。 |
| 认证 | [`AuthProvider`](../ailux-provider-backend/src/main/java/com/ailux/provider/backend/auth/AuthProvider.kt) | 无（你必须自行实现或拼 `Bearer xxx` 静态值） | 需要异步刷新（OAuth client_credentials / JWT 续期）、token 来自 KeyStore / SSO 中转。 |

## 决策树

```text
                   后端跟 OpenAI / Anthropic 协议兼容？
                              │
                ┌─────── 是 ──┴── 否 ───────┐
                ▼                            ▼
        想加非标字段？               写 RequestMapper
       （seed, top_k, …）            （见示例 ①）
                │
        ┌── 是 ─┴── 否 ──┐
        ▼               ▼
    用 overrides       直接用内置 mapper
   （三层模型 ②）        （零代码）
```

```text
                  SSE event 名 / data 形态自定义？
                              │
                ┌─────── 是 ──┴── 否 ───────┐
                ▼                            ▼
        写 StreamResponseParser     直接用内置 parser
        （见示例 ②）                 （含 FC 累积）
```

```text
              HTTP 状态码就能完整表达错误？
                              │
                ┌─────── 是 ──┴── 否 ───────┐
                ▼                            ▼
        DefaultErrorMapper           写 ErrorMapper
       （零代码）                    （见示例 ③）
```

```text
                  token 是简单静态 Bearer？
                              │
                ┌─────── 是 ──┴── 否 ───────┐
                ▼                            ▼
        AuthProvider { "Bearer xyz" }   写带 Mutex single-flight
        一行 lambda                      的 OAuth/JWT 实现
                                         （见示例 ④）
```

## 4 个完整示例（**单测，可编译，永不腐烂**）

每个示例都是一份独立单测文件，KDoc 第一段就讲"为什么这是该扩展点的正确触发场景"。**示例本身不进 `src/main`**——它们是文档，不是预置实现，你应当照写而非照搬。

| 扩展点 | 示例文件 | 演示了什么 |
|---|---|---|
| `RequestMapper` | [`AcmeRequestMapperExampleTest.kt`](../ailux-provider-backend/src/test/java/com/ailux/provider/backend/examples/AcmeRequestMapperExampleTest.kt) | 虚构 AcmeChat：`messages→chat_history` / `role→speaker` / 系统消息 hoist 出来成 `directives`；末尾仍调 `applyOverrides()` 让逃生舱可叠加。 |
| `StreamResponseParser` | [`AcmeStreamResponseParserExampleTest.kt`](../ailux-provider-backend/src/test/java/com/ailux/provider/backend/examples/AcmeStreamResponseParserExampleTest.kt) | 自定义 `event: delta/metrics/finish`；终结 reason 翻译；未知 event/reason 容错。 |
| `ErrorMapper` | [`BizCodeErrorMapperExampleTest.kt`](../ailux-provider-backend/src/test/java/com/ailux/provider/backend/examples/BizCodeErrorMapperExampleTest.kt) | 企业网关 HTTP 200 + body 业务码；含 IOException/Timeout/畸形 JSON 兜底；自动落到 `retriable=true`。 |
| `AuthProvider` | [`OAuthClientCredentialsAuthProviderExampleTest.kt`](../ailux-provider-backend/src/test/java/com/ailux/provider/backend/examples/OAuthClientCredentialsAuthProviderExampleTest.kt) | OAuth2 client_credentials；Mutex single-flight：16 协程并发只刷一次。 |

## 装配

四个扩展点通过 [`BackendProxyConfig`](../ailux-provider-backend/src/main/java/com/ailux/provider/backend/config/BackendProxyConfig.kt) 一并装配：

```kotlin
val config = BackendProxyConfig(
    baseUrl = "https://acme-chat.example.com/v1/chat",
    requestMapper = AcmeRequestMapper(),               // ① 自定义请求体
    streamResponseParser = AcmeStreamResponseParser(), // ② 自定义 SSE
    errorMapper = BizCodeErrorMapper(),                // ③ 业务码归一
    authProvider = oauthProvider,                      // ④ OAuth 自动刷新
    // 其他保持默认即可
)
```

没传的字段会回落到内置实现，**不会**因为你只想换 `errorMapper` 就被迫连 `requestMapper` 都自己写。

## 反模式（别做这些事）🚫

| 反模式 | 正确做法 |
|---|---|
| 用 `overrides` 模拟"自家协议的 messages 数组" | 写 `RequestMapper`——`overrides` 是顶层 merge，不能改 `messages` 嵌套形态。 |
| 在 `RequestMapper` 里塞 OAuth 刷新逻辑 | 写 `AuthProvider`——职责分离，且 `AuthProvider` 是 `suspend` 安全。 |
| 在 `ErrorMapper` 里写"重试 3 次再判错" | 重试归 SDK 内置 `retryWhen` + `ErrorCode.retriable` 标志；`ErrorMapper` 只负责**归一**。 |
| 自己写 `OpenAIRequestMapper` 复刻 95% 行为只为改一个字段 | 用 `overrides` 覆盖该字段；如果 `overrides` 表达不出来，再考虑包装内置 mapper 做 delegation。 |

## 延伸阅读

- [日志策略与隐私契约](LOGGING-zh.md) — 自定义扩展点时如何不把 prompt/response 泄漏到 logcat
- [API 参考](API-zh.md) — `BackendProxyConfig` 全字段
