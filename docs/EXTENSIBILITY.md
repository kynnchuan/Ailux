# Ailux Extensibility Guide

[← Back to README](../README.md) · [中文](EXTENSIBILITY-zh.md)

> Applies to: v0.2.4+ (request body three-tier model), v0.2.5+ (provider extension-point decision tree)
> Related design notes: [`ADR-0003`](../ailux-docs/decisions/adr/0003-llmrequest-extensibility-overrides.md), [`v0.2.4 spec`](../ailux-docs/specs/v0.2/v0.2.4-llmrequest-extensibility.md), [`v0.2.5 spec`](../ailux-docs/specs/v0.2/v0.2.5-extensibility-and-privacy.md)

This document has two parts:
- **Part 1** — the `LLMRequest` **three-tier model** (how to get a field into the request body).
- **Part 2** — the Provider **four extension-point decision tree** (when to actually write a custom mapper / parser / errormapper / auth).

---

# Part 1 · `LLMRequest` three-tier extensibility model

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

# Part 2 · Provider four extension-point decision tree (v0.2.5+)

Part 1 covered "how to push a field into `LLMRequest`". But when the **protocol itself** diverges from OpenAI / Anthropic, or your error codes live inside a business envelope, or auth needs OAuth refresh — `overrides` is the wrong tool. You need a Provider-layer extension point.

`BackendProxyProvider` exposes **4 extension points**, all `fun interface`. **No subclassing required — pass a lambda or singleton.**

| Extension point | Interface | Built-in | When to replace (the decision tree) |
|---|---|---|---|
| Request body | [`RequestMapper`](../ailux-provider-backend/src/main/java/com/ailux/provider/backend/mapper/RequestMapper.kt) | `OpenAIRequestMapper` / `AnthropicRequestMapper` | Backend's **root schema** is incompatible with both OpenAI and Anthropic (field names / nesting / top-level shape). For per-field tweaks see [`overrides`](#tier--overrides-escape-hatch) first. |
| SSE event parsing | [`StreamResponseParser`](../ailux-provider-backend/src/main/java/com/ailux/provider/backend/parser/StreamResponseParser.kt) | `OpenAIStreamResponseParser` / `AnthropicStreamResponseParser` | The backend's `event:` names or `data:` shapes are vendor-specific; or FC delta accumulation works differently. |
| Error normalisation | [`ErrorMapper`](../ailux-provider-backend/src/main/java/com/ailux/provider/backend/mapper/ErrorMapper.kt) | `DefaultErrorMapper` (standard HTTP codes) | Business error codes live in the body (typical: gateway returns HTTP 200 with `{"code":"GATEWAY_RATE_LIMITED"}`); or your HTTP code semantics are overloaded. |
| Authentication | [`AuthProvider`](../ailux-provider-backend/src/main/java/com/ailux/provider/backend/auth/AuthProvider.kt) | none (you must implement, even a static `Bearer xxx`) | You need async refresh (OAuth client_credentials / JWT renewal), or the token comes from KeyStore / SSO. |

## Decision trees

```text
                Backend compatible with OpenAI / Anthropic?
                              │
                ┌─── yes ─────┴─── no ──────┐
                ▼                            ▼
        Need a non-standard field?      Write a RequestMapper
       (seed, top_k, …)                 (see example ①)
                │
        ┌── yes ─┴── no ──┐
        ▼                 ▼
    Use overrides         Use built-in mapper
   (three-tier ②)         (zero code)
```

```text
                  Vendor-specific SSE event/data shapes?
                              │
                ┌─── yes ─────┴─── no ──────┐
                ▼                            ▼
        Write a StreamResponseParser   Use built-in parser
        (see example ②)                (FC aggregation included)
```

```text
              HTTP status alone expresses every error?
                              │
                ┌─── yes ─────┴─── no ──────┐
                ▼                            ▼
        DefaultErrorMapper              Write an ErrorMapper
        (zero code)                     (see example ③)
```

```text
                  Token is a plain static Bearer?
                              │
                ┌─── yes ─────┴─── no ──────┐
                ▼                            ▼
       AuthProvider { "Bearer xyz" }    Write an OAuth/JWT impl
       one-line lambda                  with Mutex single-flight
                                        (see example ④)
```

## Four end-to-end examples (**unit tests — compile-checked, never rot**)

Each example is a standalone test file; the KDoc opens with **why this is the right trigger for that extension point**. The examples deliberately stay out of `src/main` — they are documentation, not presets. Adapt them, don't import them.

| Extension point | Example file | What it shows |
|---|---|---|
| `RequestMapper` | [`AcmeRequestMapperExampleTest.kt`](../ailux-provider-backend/src/test/java/com/ailux/provider/backend/examples/AcmeRequestMapperExampleTest.kt) | Fictional AcmeChat: `messages→chat_history` / `role→speaker` / system messages hoisted to top-level `directives`; still calls `applyOverrides()` at the end so the escape hatch composes. |
| `StreamResponseParser` | [`AcmeStreamResponseParserExampleTest.kt`](../ailux-provider-backend/src/test/java/com/ailux/provider/backend/examples/AcmeStreamResponseParserExampleTest.kt) | Custom `event: delta/metrics/finish`; finish-reason translation; unknown event/reason tolerated. |
| `ErrorMapper` | [`BizCodeErrorMapperExampleTest.kt`](../ailux-provider-backend/src/test/java/com/ailux/provider/backend/examples/BizCodeErrorMapperExampleTest.kt) | Enterprise gateway HTTP 200 + business code; covers IOException/Timeout/malformed JSON; ends up with the correct `retriable` flag. |
| `AuthProvider` | [`OAuthClientCredentialsAuthProviderExampleTest.kt`](../ailux-provider-backend/src/test/java/com/ailux/provider/backend/examples/OAuthClientCredentialsAuthProviderExampleTest.kt) | OAuth2 client_credentials with Mutex single-flight: 16 concurrent callers, one network refresh. |

## Wiring

All four hook into [`BackendProxyConfig`](../ailux-provider-backend/src/main/java/com/ailux/provider/backend/config/BackendProxyConfig.kt) in one place:

```kotlin
val config = BackendProxyConfig(
    baseUrl = "https://acme-chat.example.com/v1/chat",
    requestMapper = AcmeRequestMapper(),               // ① body
    streamResponseParser = AcmeStreamResponseParser(), // ② SSE
    errorMapper = BizCodeErrorMapper(),                // ③ errors
    authProvider = oauthProvider,                      // ④ auth
    // anything you omit falls back to the built-in default
)
```

Omitting a field falls back to the built-in default — you are **never** forced to write a `requestMapper` just because you want a custom `errorMapper`.

## Anti-patterns 🚫

| Anti-pattern | Do this instead |
|---|---|
| Use `overrides` to fake "our `messages` shape" | Write a `RequestMapper` — `overrides` is a top-level merge and cannot reshape nested arrays. |
| Cram OAuth refresh into `RequestMapper` | Use `AuthProvider` — separation of concerns, and `AuthProvider` is `suspend`-safe. |
| "Retry 3 times then fail" inside `ErrorMapper` | Retry belongs to the SDK's built-in `retryWhen` + `ErrorCode.retriable`. `ErrorMapper` only **normalises**. |
| Copy-paste `OpenAIRequestMapper` to flip one field | Use `overrides`; if that's expressively insufficient, wrap the built-in mapper via delegation. |

## Further reading

- [Logging policy & privacy contract](LOGGING.md) — how to keep prompt/response out of logcat when writing custom extension points
- [API reference](API.md) — every `BackendProxyConfig` field
