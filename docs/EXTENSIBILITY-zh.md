# Ailux LLMRequest 三层扩展模型

[← 返回 README](../README-zh.md) · [English](EXTENSIBILITY.md)

> 适用版本：v0.2.4+
> 关联设计：[`ADR-0003`](../ailux-docs/decisions/adr/0003-llmrequest-extensibility-overrides.md)、[`v0.2.4 spec`](../ailux-docs/specs/v0.2/v0.2.4-llmrequest-extensibility.md)

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
