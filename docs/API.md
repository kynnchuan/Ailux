# Ailux API Reference

[← Back to README](../README.md)

## Custom mock rules

```kotlin
import com.ailux.provider.mock.MockProvider
import com.ailux.provider.mock.MockRule

val provider = MockProvider(
    rules = listOf(
        MockRule(
            keyword   = "weather",
            reply     = "It's a great day for a walk.",
            reasoning = "User is asking about weather, give a short suggestion."
        ),
        MockRule(
            keyword = "",                       // empty keyword == fallback
            reply   = "[Mock] You are currently in Mock mode for demo purposes. To experience real model responses, switch to BackendProxyProvider.",
            reasoning = "No keyword matched; remind the user this is a mock environment."
        )
    )
)
```

Matching rules:

1. The first rule whose non-empty `keyword` is contained in the prompt wins.
2. If nothing matches, the rule with `keyword = ""` is used as a fallback.
3. If no fallback rule is provided, the provider returns its built-in default reply.

> Empty `keyword` is treated as a fallback marker because every Kotlin string trivially contains the empty string.

## Custom `AuthProvider`

`AuthProvider` is a `fun interface` whose `getAuthToken()` returns the **complete** `Authorization` header value (scheme included). It is `suspend`, so it can fetch a fresh token on demand.

```kotlin
val auth = AuthProvider {
    // e.g. read from EncryptedSharedPreferences, refresh via your auth service, etc.
    val token = TokenStore.getOrRefresh()
    "Bearer $token"
}

val config = BackendProxyConfig(
    baseUrl = BuildConfig.AILUX_BASE_URL,
    authProvider = auth
)
```

## Streaming events

`Ailux.streamGenerate(...)` returns a cold `Flow<LLMEvent>`:

| Event | Meaning |
| --- | --- |
| `LLMEvent.Token`            | Incremental visible token. Concatenate `text` to render. |
| `LLMEvent.Reasoning`        | Incremental reasoning/chain-of-thought (when the model emits it). |
| `LLMEvent.Usage`            | Token usage / cost info. |
| `LLMEvent.Error`            | Stream-level error; the flow ends after this. |
| `LLMEvent.ToolCallDelta`    | Incremental tool call fragment (internal to parser, rarely needed). |
| `LLMEvent.ToolCallReceived` | Complete tool calls from the model. Execute and loop. |
| `LLMEvent.Done`             | Completion sentinel. Check `finishReason` for next action. |

## Function Calling (Tool Use)

Ailux SDK supports multi-turn Function Calling. Define tools, send the request, handle tool call events, execute functions, and loop until the model is done.

### 1. Define tools

```kotlin
import com.ailux.core.tool.ToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

val tools = listOf(
    ToolDefinition(
        name = "get_weather",
        description = "Get the current weather for a city.",
        arguments = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "City name")
                }
            }
            put("required", buildJsonArray { add(JsonPrimitive("city")) })
        }
    )
)
```

### 2. Multi-turn loop

```kotlin
val messages = mutableListOf<Message>(Message.User("What's the weather in Beijing?"))

var finishReason: FinishReason
do {
    finishReason = FinishReason.COMPLETE
    var pendingToolCalls: List<ToolCall>? = null

    val request = LLMRequest(messages = messages, tools = tools)

    Ailux.streamGenerate(request).collect { event ->
        when (event) {
            is LLMEvent.Token            -> print(event.text)
            is LLMEvent.ToolCallReceived -> pendingToolCalls = event.toolCalls
            is LLMEvent.Done             -> finishReason = event.finishReason
            else -> { /* handle other events as needed */ }
        }
    }

    // If the model requested tool calls, execute them and continue
    if (finishReason == FinishReason.TOOL_CALL && pendingToolCalls != null) {
        messages.add(Message.Assistant(toolCalls = pendingToolCalls))
        for (call in pendingToolCalls!!) {
            val result = executeMyTool(call)  // your implementation
            messages.add(Message.Tool(toolCallId = call.id, content = result))
        }
    }
} while (finishReason == FinishReason.TOOL_CALL)
```

### 3. Custom Parser with non-streaming tools

If your backend returns complete tool calls in one shot (not streamed), implement a custom `StreamResponseParser`:

```kotlin
val myParser = StreamResponseParser { eventType, data ->
    when (eventType) {
        "tool_result" -> {
            val toolCalls = parseMyProtocolToolCalls(data)
            listOf(
                LLMEvent.ToolCallReceived(toolCalls),
                LLMEvent.Done(FinishReason.TOOL_CALL)
            )
        }
        "delta" -> listOf(LLMEvent.Token(parseContent(data)))
        "done"  -> listOf(LLMEvent.Done())
        else    -> emptyList()
    }
}
```

## Cancel an in-flight request

```kotlin
Ailux.cancel()      // global Ailux singleton
// or, when using AiluxClient:
client.cancel()
```

## One-shot (non-streaming) generation

```kotlin
val response = Ailux.generate(LLMRequest(messages = listOf(Message.User("hello")))
println(response.text)
```

## Multiple clients

If you need more than one provider in the same process (e.g. mock + real), use `AiluxClient` directly instead of the global `Ailux` singleton:

```kotlin
val mockClient = AiluxClient(
    AiluxConfig.Builder().setProvider(MockProvider()).build()
)
val realClient = AiluxClient(
    AiluxConfig.Builder()
        .setProvider(BackendProxyProvider())
        .setProviderConfig(backendConfig)
        .build()
)
```

## Testing

`MockProvider` is intentionally network-free and deterministic, so you can unit-test higher-level business logic without any test doubles:

```bash
./gradlew :ailux-provider-mock:testDebugUnitTest
```
