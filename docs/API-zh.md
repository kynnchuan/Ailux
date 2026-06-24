# Ailux API 参考

[← 返回 README](../README-zh.md)

## 自定义 Mock 规则

```kotlin
import com.ailux.provider.mock.MockProvider
import com.ailux.provider.mock.MockRule

val provider = MockProvider(
    rules = listOf(
        MockRule(
            keyword   = "天气",
            reply     = "今天天气不错，适合出门遛弯。",
            reasoning = "用户在问天气，给一个简短建议。"
        ),
        MockRule(
            keyword = "",                       // 空 keyword == 兜底规则
            reply   = "[Mock] 当前为 Mock 演示模式，回复均为预设内容。如需体验真实模型效果，请切换到 BackendProxyProvider。",
            reasoning = "无关键词匹配，提示用户当前处于 Mock 环境。"
        )
    )
)
```

匹配逻辑：

1. 第一个 `keyword` 非空且被 prompt 包含的规则胜出。
2. 如果没有匹配，使用 `keyword = ""` 的兜底规则。
3. 如果没有提供兜底规则，Provider 返回内置默认回复。

> 空 `keyword` 被视为兜底标记，因为任何 Kotlin 字符串都包含空字符串。

## 自定义 `AuthProvider`

`AuthProvider` 是一个 `fun interface`，其 `getAuthToken()` 返回**完整的** `Authorization` 头值（含 scheme 前缀）。它是 `suspend` 的，因此可以按需刷新 Token。

```kotlin
val auth = AuthProvider {
    // 例如从 EncryptedSharedPreferences 读取，或通过你的认证服务刷新
    val token = TokenStore.getOrRefresh()
    "Bearer $token"
}

val config = BackendProxyConfig(
    baseUrl = BuildConfig.AILUX_BASE_URL,
    authProvider = auth
)
```

## 流式事件

> **v0.3.0b 调用面变更**：原本的 `Ailux.streamGenerate(...)` / `Ailux.generate(...)` 每调用入口已删除，请通过 `Session` 发起调用。详见 [ADR-0009](../ailux-docs/decisions/adr/0009-session-only-single-pipeline.md)。

`Session.streamGenerate(...)`（以及便利方法 `Session.streamGenerateAsTask(...)`）发出 `LLMEvent`：

| 事件 | 含义 |
| --- | --- |
| `LLMEvent.Token`            | 增量可见 token。拼接 `text` 进行渲染。 |
| `LLMEvent.Reasoning`        | 增量推理/思维链（模型输出时才有）。 |
| `LLMEvent.Usage`            | Token 用量/成本信息。 |
| `LLMEvent.Error`            | 流级别错误；此事件后 flow 结束。 |
| `LLMEvent.ToolCallDelta`    | 工具调用增量片段（Parser 内部使用，通常不需关注）。 |
| `LLMEvent.ToolCallReceived` | 模型发出的完整工具调用。执行后继续循环。 |
| `LLMEvent.Done`             | 完成哨兵。检查 `finishReason` 决定后续动作。 |

## Function Calling（工具调用）

Ailux SDK 支持多轮 Function Calling。定义工具 → 发送请求 → 处理工具调用事件 → 执行函数 → 循环直到模型完成。

### 1. 定义工具

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
        description = "获取指定城市的当前天气。",
        arguments = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "城市名称")
                }
            }
            put("required", buildJsonArray { add(JsonPrimitive("city")) })
        }
    )
)
```

### 2. 多轮循环

```kotlin
// 整个多轮工具循环共享一个 Session —— 由它维护对话历史 / KV 缓存，
// 每轮只发送*增量*消息。
Ailux.openSession().use { session ->
    // 首轮用户消息
    var turnMessages: List<Message> = listOf(
        Message.User("北京今天天气怎么样？")
    )

    var finishReason: FinishReason
    do {
        finishReason = FinishReason.COMPLETE
        var pendingToolCalls: List<ToolCall>? = null

        val request = LLMRequest(messages = turnMessages, tools = tools)

        session.streamGenerate(request).collect { event ->
            when (event) {
                is LLMEvent.Token            -> print(event.text)
                is LLMEvent.ToolCallReceived -> pendingToolCalls = event.toolCalls
                is LLMEvent.Done             -> finishReason = event.finishReason
                else -> { /* 按需处理其他事件 */ }
            }
        }

        // 如果模型请求了工具调用，执行后把工具结果作为下一轮的增量回灌即可，
        // 无需再次重发历史消息。
        if (finishReason == FinishReason.TOOL_CALL && pendingToolCalls != null) {
            turnMessages = pendingToolCalls!!.map { call ->
                val result = executeMyTool(call)  // 你的实现
                Message.Tool(toolCallId = call.id, content = result)
            }
        }
    } while (finishReason == FinishReason.TOOL_CALL)
}
```

### 3. 自定义 Parser（非流式工具调用）

如果你的后端一次性返回完整的工具调用（非流式增量），可以实现自定义 `StreamResponseParser`：

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

## 取消进行中的请求

```kotlin
Ailux.cancel()      // 全局 Ailux 单例
// 或者使用 AiluxClient 时：
client.cancel()
```

## 一次性（非流式）调用

```kotlin
val response = Ailux.openSession().use {
    it.generate(LLMRequest(messages = listOf(Message.User("hello"))))
}
println(response.text)
```

> 长生命周期对话请把 `Session` 实例挂在 `ViewModel` 等长生存域里复用，每次调用只发送当轮增量，不要每次请求都重新 open / close。

## 多实例 Client

如果你需要在同一进程中使用多个 Provider（例如 mock + 真实），可以直接使用 `AiluxClient` 而非全局 `Ailux` 单例：

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

## 测试

`MockProvider` 故意设计为无网络、确定性的，因此你可以直接对高层业务逻辑做单元测试，无需任何 test double：

```bash
./gradlew :ailux-provider-mock:testDebugUnitTest
```
