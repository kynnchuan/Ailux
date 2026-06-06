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

`Ailux.streamGenerate(...)` 返回一个冷 `Flow<LLMEvent>`：

| 事件 | 含义 |
| --- | --- |
| `LLMEvent.Token`     | 增量可见 token。拼接 `text` 进行渲染。 |
| `LLMEvent.Reasoning` | 增量推理/思维链（模型输出时才有）。 |
| `LLMEvent.Usage`     | Token 用量/成本信息。 |
| `LLMEvent.Error`     | 流级别错误；此事件后 flow 结束。 |
| `LLMEvent.Done`      | 正常完成哨兵。 |

## 取消进行中的请求

```kotlin
Ailux.cancel()      // 全局 Ailux 单例
// 或者使用 AiluxClient 时：
client.cancel()
```

## 一次性（非流式）调用

```kotlin
val response = Ailux.generate(LLMRequest(prompt = "hello"))
println(response.text)
```

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
