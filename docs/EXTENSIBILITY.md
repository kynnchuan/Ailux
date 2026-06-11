# Ailux LLMRequest Three-Tier Extensibility Model

[← Back to README](../README.md) · [中文](EXTENSIBILITY-zh.md)

> Applies to: v0.2.4+
> Related design notes: [`ADR-0003`](../ailux-docs/decisions/adr/0003-llmrequest-extensibility-overrides.md), [`v0.2.4 spec`](../ailux-docs/specs/v0.2/v0.2.4-llmrequest-extensibility.md)

`LLMRequest` exposes extensibility through three explicit tiers. **Pick the tier first, then write code** — the wrong tier forces you to either patch the SDK or write a full mapper, wasting time and polluting semantics.

```text
                Need: a field has to land in the request body
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
 ① Strong-typed field  ② overrides escape hatch ③ Custom RequestMapper
 (high-frequency ∧     (one-off / long tail /    (entirely foreign
  cross-protocol)        complex structure)        protocol)
 model / temperature   buildJsonObject {         class MyMapper :
 / topP / maxTokens    put("seed", 42)            RequestMapper { ... }
 / stop / attachments  putJsonObject(...) {}     // own the entire body
                      }
 Type-safe, IDE help   Zero-wait, structured,    Ultimate, but you
                       can override                rebuild the body
```

## Decision tree

| Question | Tier |
|---|---|
| Most major backends support this field with consistent semantics? | **① strong-typed** |
| Only one or two backends support it, or the value is an array/object? | **② `overrides`** |
| The backend's root schema diverges from OpenAI / Anthropic (e.g. an in-house RPC, a private wire format)? | **③ custom `RequestMapper`** |

`stop` / `attachments` are textbook ① fields. `seed` / `response_format` / a vendor-specific `top_k` are ② fields. An in-house RPC, or AWS Bedrock invoke, is ③.

## Tier ①: strong-typed fields

A field is admitted only if it is "high-frequency ∧ cross-protocol consistent". Current set:

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
    val overrides: JsonObject = JsonObject(emptyMap()),  // v0.2.4 + (replaces extras)
)
```

`stop` maps to `stop` on OpenAI and to `stop_sequences` on Anthropic — that is precisely the value of Tier ①: **the SDK absorbs protocol differences for you**.

## Tier ②: `overrides` structured escape hatch

```kotlin
val req = LLMRequest(
    messages = listOf(Message.User("Reply in JSON")),
    overrides = buildJsonObject {
        put("seed", 42)                                  // number
        put("frequency_penalty", 0.5)                    // float
        putJsonObject("response_format") {               // object
            put("type", "json_object")
        }
        putJsonArray("stream_options") { /* ... */ }      // array
    },
)
```

Behavior: after a mapper finishes building the request body, it merges every key from `overrides` **into the top level**, with same-name **override** semantics (including overriding strong-typed fields like `model` / `temperature` as the ultimate escape).

Both `OpenAIRequestMapper` and `AnthropicRequestMapper` consume `overrides` (fixing the pre-v0.2.4 inconsistency where Anthropic silently dropped `extras`). A custom mapper only needs to call this at the end of its build:

```kotlin
import com.ailux.provider.backend.mapper.applyOverrides

class MyMapper : RequestMapper {
    override fun map(request: LLMRequest, stream: Boolean): String {
        val body = buildJsonObject {
            // ... build your fields
            applyOverrides(request.overrides)  // always last
        }
        return body.toString()
    }
}
```

### Ultimate escape: overriding strong-typed fields

```kotlin
// Backend insists temperature must be an integer 1 — bypass the SDK Float
val req = LLMRequest(
    messages = listOf(Message.User("hi")),
    overrides = buildJsonObject { put("temperature", 1) },
)
```

> ⚠️ **Trade-off**: overriding strong-typed fields means **you** are responsible for the request's validity — all SDK validation and cross-protocol mapping is bypassed. Use only when you genuinely "know what you are doing".

## Tier ③: custom `RequestMapper`

Reach for this only when the backend's root schema is incompatible with both OpenAI and Anthropic. `RequestMapper` is a `fun interface`:

```kotlin
class CustomRpcMapper : RequestMapper {
    override fun map(request: LLMRequest, stream: Boolean): String {
        // your body, your rules
    }
}

val config = BackendProxyConfig(
    baseUrl = "...",
    requestMapper = CustomRpcMapper(),
    streamResponseParser = CustomRpcParser(),
)
```

See the [API reference](API.md) for details.

---

## Migration guide: `extras` → `overrides` (v0.2.4)

`LLMRequest.extras: Map<String, String>` is replaced by `overrides: JsonObject` in v0.2.4. This is a **breaking change** (acceptable in the 0.x window). Wins: ① structured values (numbers, objects, arrays); ② merged at the top level instead of being pinned inside OpenAI's `metadata` sub-object; ③ uniformly consumed by every built-in mapper (fixes Anthropic's historical silent drop of `extras`).

### Plain swap (string-only values)

```kotlin
// Old (v0.2.3):
LLMRequest(
    messages = ...,
    extras = mapOf("user_id" to "u-123"),
)

// New (v0.2.4):
LLMRequest(
    messages = ...,
    overrides = buildJsonObject {
        put("user_id", "u-123")
    },
)
```

### Free upgrade: restore native types

In the `extras` era, numbers and objects had to be `toString`-ed, forcing the backend to re-parse and sometimes failing validation. `overrides` carries native types directly:

```kotlin
// v0.2.3 tragedy: seed had to be a string, backend re-parsed it
extras = mapOf("seed" to "42")

// v0.2.4: seed is an Int
overrides = buildJsonObject { put("seed", 42) }
```

### Injection-point change

`extras` was pinned into OpenAI's `metadata` sub-object and silently dropped on Anthropic. `overrides` always merges into the request body's **top level**. If your backend really wants the field nested in `metadata`, express that explicitly:

```kotlin
overrides = buildJsonObject {
    putJsonObject("metadata") {
        put("user_id", "u-123")
        put("trace_id", traceId)
    }
}
```

### Empty `JsonObject` when there is nothing to send

```kotlin
// Don't write emptyMap() any more
overrides = JsonObject(emptyMap())   // or simply omit the default
```

### Compiler-driven migration

Once you bump to v0.2.4 the compiler flags every `extras = ...` call site at once. Walk through them with the table above — most projects need a single PR.
