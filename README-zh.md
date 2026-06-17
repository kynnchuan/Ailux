[English](README.md) | 中文

# Ailux

![CI](https://github.com/kynnchuan/ailux/actions/workflows/ci.yml/badge.svg)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kynnchuan/ailux-sdk.svg?label=Maven%20Central)](https://search.maven.org/artifact/io.github.kynnchuan/ailux-sdk)

Ailux 是一个面向 Android 的轻量级 LLM SDK，帮你在几分钟内接入任意大语言模型。用 `MockProvider` 零配置跑通完整 Chat UI，再一行配置切换到 `BackendProxyProvider` 对接自建后端——同一套流式 API，生产级安全。

### 定位与边界

Ailux 是一个**面向 Android 的轻量 LLM 接入层，而不是 Agent 框架。** 它刻意保持厂商中立（OpenAI / Anthropic / DeepSeek / 任意 OpenAI 兼容接口），且不绑定任何单一云生态，适合走自建后端、或使用非 Gemini / 国内模型的团队。

- 想要**最小化、厂商中立、以自建后端代理为核心的流式客户端** —— 选 **Ailux**。
- 需要**与 Gemini 生态深度整合的多智能体编排**（云端 Gemini + 端侧 Gemini Nano）—— 选 **[Google ADK for Android](https://developer.android.google.cn/ai/adk?hl=zh-cn)**（2026-05-21 发布）。

### 为什么选 Ailux？

| 痛点 | Ailux 方案 |
| --- | --- |
| 各厂商 SDK 碎片化 | 统一 `LLMProvider` 抽象——切换 OpenAI、Anthropic、DeepSeek 或任何兼容接口，业务代码零改动。 |
| SSE 流式处理复杂 | 一个 `Flow<LLMEvent>` 搞定：`Token` / `Reasoning` / `Usage` / `Error` / `ToolCallReceived` / `Done`。 |
| APK 内嵌 Key 泄露风险 | `BackendProxyProvider` 把凭据留在服务端；直连云端路径受 `@OptIn(DirectCloudUsage::class)` 门控。 |
| 没有 Key 就没法开发 | `MockProvider`——完全离线、行为确定、零依赖。跑 Demo、写测试、新人上手即刻开始。 |
| Android 生命周期管理头疼 | 内置 5 种生命周期策略 + ViewModel 开箱即用。 |
| 错误处理不统一 | 统一 `ErrorCode` + `LLMError`，自带 `isRetriable` 标记与自动重试。 |
| 依赖膨胀 | 一行 `implementation("io.github.kynnchuan:ailux-sdk:0.2.6")`，全部搞定。 |
| 多厂商协议差异 | 可插拔 `StreamResponseParser`（内置 OpenAI + Anthropic 解析器），自定义只需 ~20 行。 |
| Function Calling 协议差异 | OpenAI / Anthropic 两套 SSE FC 协议统一为 `LLMEvent.ToolCallReceived`；`ToolCallAggregator` 拼装分片，多轮 FC 业务方自由编排。 |
| 上下文越界 / 413 | `LLMContextManager` 三阶段裁剪管线 + `FcMessageProtector` 保护 FC 配对消息，业务零侵入。 |
| 多请求并发不可控 | `ConcurrencyPolicy` + 每请求 `LLMTask` 句柄 + 停滞检测，超时 / 取消 / 背压一把抓。 |
| 日志泄露用户数据 | `PrivacyConfig` 默认全脱敏 + `AiluxLogger` SPI 桥接 Timber/SLF4J/Sentry；`DiagnosticReport` 生成可分享的脱敏报告。 |
| 接生产后端不堪一击 | v0.2.6 加固：指数退避 + `Retry-After` 重试、`HttpClientConfig` 注入自定义 `OkHttpClient`（mTLS / 证书锁定 / 代理）、`AuthProvider.onUnauthorized` 401 自动刷新重放（单飞、独立预算）+ `AUTH_EXPIRED` 错误分级 + `RequestSigner` 请求级签名。 |

## 当前能力一览（v0.2 线）

- **单依赖接入** —— 一个 Maven 坐标覆盖全部功能。
- **`MockProvider`** —— 无 API Key、无后端、无网络，即刻跑通 Chat Demo。
- **`BackendProxyProvider`** —— 对接自建后端代理（生产推荐），v0.2.6 完成生产化加固。
- **`directCloudConfig(...)`** —— Opt-in 直连云端，快速验证（BYOK，需 `@OptIn`）。
- **流式事件模型** —— `Token` / `Reasoning` / `Usage` / `Error` / `ToolCallReceived` / `Done`。
- **Function Calling 协议解析** —— OpenAI & Anthropic 双协议，`ToolCallAggregator` 分片聚合，多轮 FC 业务方编排。
- **LLMContextManager** —— 三阶段裁剪管线 + `FcMessageProtector` + 可插拔 `TokenCounter`，避免上下文越界。
- **并发与停滞检测** —— `ConcurrencyPolicy`、每请求 `LLMTask` 句柄、`handle{}` / `tokenFlow()` DSL、TTFT / decode 阶段停滞侦测。
- **三层请求扩展模型** —— 强类型字段 / `overrides` 结构化逃生舱 / 自定义 `RequestMapper`；附带多模态 `attachments` 与 `Idempotency-Key`。
- **隐私与诊断** —— `PrivacyConfig` 默认脱敏、`AiluxLogger` SPI、`DiagnosticReport` 一键生成脱敏报告并支持 Issue 模板自助上报。
- **BackendProxy 生产化加固（v0.2.6）** —— `NonStreamResponseParser`、`RetryPolicy`（指数退避+jitter+`Retry-After`）、`HttpClientConfig` / `ProtocolConfig` 三分法、`AuthProvider.onUnauthorized` 401 自动刷新重放、`AUTH_EXPIRED` 错误分级、`RequestSigner` 请求签名。
- **官方 Backend 样板** —— `samples/ailux-backend-sample`，Spring Boot 单体，演示 SSE 转发、服务端 + 客户端 FC、模型路由、鉴权配额、断连即 abort。
- **多实例支持** —— 同进程内通过 `AiluxClient` 并行运行 Mock + 真实 Provider，运行时可切换。
- **Android Compose Demo** —— 逐 token 渲染、思考过程折叠、Usage 展示、Debug Panel 切换扩展配置。

<p align="center">
  <img src="assets/demo/v0.2-chat-backend-empty.png" width="24%" alt="Ailux Chat Demo —— BackendProxy 模式启动页" />
  <img src="assets/demo/v0.2-chat-mock-weather.png" width="24%" alt="Ailux Chat Demo —— MockProvider 流式输出天气示例" />
  <img src="assets/demo/v0.2-debug-panel-request.png" width="24%" alt="Ailux Debug 面板 —— Request-level 运行时配置" />
  <img src="assets/demo/v0.2-debug-panel-client.png" width="24%" alt="Ailux Debug 面板 —— Client-level 运行时配置" />
</p>

> 截图从左到右依次为：运行时切换 Provider（Mock ↔ BackendProxy）、MockProvider 触发内置规则的流式输出、Debug 面板 Request-level（overrides JSON / Context mode / 账户 / Stop 序列 / 多模态附件等）、Debug 面板 Client-level（Provider 切换 / 停滞检测 / 并发策略）。

📲 **立即体验：** [下载 Demo APK](assets/demo/ailux-demo-v0.2.6.apk) —— 基于 `MockProvider` 完全离线运行，无需 API Key。

## 接入

在 app/library 模块的 `build.gradle.kts` 中添加单个聚合依赖：

```kotlin
dependencies {
    implementation("io.github.kynnchuan:ailux-sdk:0.2.6")
}
```

> 仓库需包含 `mavenCentral()`：
>
> ```kotlin
> // settings.gradle.kts
> dependencyResolutionManagement {
>     repositories {
>         google()
>         mavenCentral()
>     }
> }
> ```

## Quick Start

接入只有 **2 步**：第 1 步在 `Ailux.init(...)` 选一种 Provider；第 2 步 `Ailux.streamGenerate(...)` 收事件流。

### 第 1 步 —— `Ailux.init(...)`：从三种 Provider 里**选一种**

三种 Provider 共用同一套 `Ailux.streamGenerate(...)` API，**只有传入 `AiluxConfig` 的方式不同**。

<details>
<summary><b>① <code>MockProvider</code> —— 无 API Key、无网络</b> · 适合本地开发、Demo 演示、单元测试</summary>

```kotlin
import com.ailux.api.Ailux
import com.ailux.api.AiluxConfig
import com.ailux.provider.mock.MockProvider

Ailux.init(
    AiluxConfig.Builder()
        .setProvider(MockProvider())
        .build()
)
```

</details>

<details open>
<summary><b>② <code>BackendProxyProvider</code> —— 生产推荐</b> · 请求经由<b>你自己的</b>后端代理转发到真实模型服务；上游模型 Key 留在服务端，端上只持有自家后端颁发的用户 token</summary>

```kotlin
import com.ailux.api.Ailux
import com.ailux.api.AiluxConfig
import com.ailux.provider.backend.AuthProvider
import com.ailux.provider.backend.BackendProxyConfig
import com.ailux.provider.backend.BackendProxyProvider

val backendConfig = BackendProxyConfig(
    baseUrl          = "https://your-backend.example.com",
    streamEndpoint   = "/v1/chat/stream",     // 你的接口
    generateEndpoint = "/v1/chat/generate",   // 你的接口
    authProvider = AuthProvider {
        // 返回完整 Authorization 头（含 scheme 前缀）。
        // 这里返回的是【你自己后端】颁发给当前用户的 token，而不是上游模型 Key。
        "Bearer ${currentUser.backendToken}"
    }
)

Ailux.init(
    AiluxConfig.Builder()
        .setProvider(BackendProxyProvider())
        .setProviderConfig(backendConfig)
        .build()
)
```

> **Parser 说明：** `BackendProxyProvider` 默认使用内置的 OpenAI 兼容 `StreamResponseParser`；后端协议不同时可以自定义 request/response parser，详见[扩展性指南](docs/EXTENSIBILITY-zh.md)。
>
> **想要可直接对照的真实后端实现？** 见 `samples/ailux-backend-sample`（Spring Boot），演示 SSE 转发、服务端 / 客户端 FC、模型路由、鉴权配额、断连即 abort。

</details>

<details>
<summary><b>③ <code>directCloudConfig(...)</code> —— BYOK，仅适合原型验证</b> · 用自己的云端 Key 直接对接 OpenAI 兼容协议</summary>

> ⚠️ **把云端 API Key 直接打进 Android App，等于把 Key 暴露给任何反编译 APK 的人。** 用户 Prompt 与模型回复会直接离开设备发往上游云厂商，没有你服务端的审核、审计与限流。此方式仅适合个人原型验证；对外 C 端场景请切换为方式 ②。该 API 受 `@OptIn(DirectCloudUsage::class)` 注解保护，必须显式声明。

**1. 把云端 Key 写到 `local.properties`（不要提交）**

```properties
# local.properties
ailux.baseUrl=https://api.deepseek.com
ailux.apiKey=sk-your-deepseek-key
```

**2. 在 app `build.gradle.kts` 中桥接到 `BuildConfig`**

```kotlin
import java.util.Properties

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    defaultConfig {
        buildConfigField("String", "AILUX_BASE_URL", "\"${localProperties.getProperty("ailux.baseUrl", "")}\"")
        buildConfigField("String", "AILUX_API_KEY",  "\"${localProperties.getProperty("ailux.apiKey",  "")}\"")
    }
    buildFeatures { buildConfig = true }
}
```

**3. 用 `directCloudConfig(...)` 装配**

```kotlin
import com.ailux.api.Ailux
import com.ailux.api.AiluxConfig
import com.ailux.provider.backend.BackendProxyProvider
import com.ailux.provider.backend.DirectCloudUsage
import com.ailux.provider.backend.directCloudConfig

@OptIn(DirectCloudUsage::class)
fun setupDirectCloud() {
    val config = directCloudConfig(
        baseUrl = BuildConfig.AILUX_BASE_URL,
        apiKey  = BuildConfig.AILUX_API_KEY
        // streamEndpoint / generateEndpoint 默认 "/chat/completions"
    )

    Ailux.init(
        AiluxConfig.Builder()
            .setProvider(BackendProxyProvider())
            .setProviderConfig(config)
            .build()
    )
}
```

</details>

### 第 2 步 —— `Ailux.streamGenerate(...)`：收事件

`init(...)` 完成后，**无论选了哪种 Provider，这一步的代码完全一样**：

```kotlin
import com.ailux.api.Ailux
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest

Ailux.streamGenerate(LLMRequest(messages = listOf(Message.User("hello"))))
    .events
    .collect { event ->
        when (event) {
            is LLMEvent.Token             -> print(event.text)
            is LLMEvent.Reasoning         -> print(event.text)
            is LLMEvent.ToolCallReceived  -> handleToolCalls(event.toolCalls)
            is LLMEvent.Usage             -> println("usage: ${event.info}")
            is LLMEvent.Error             -> println("error: ${event.error}")
            is LLMEvent.Done              -> println("done: ${event.finishReason}")
            else                          -> { /* StallDetected / Connected / ContextTrimmed / ToolCallDelta */ }
        }
    }
```

接入到此结束 —— 应用启动时 `init(...)` 一次，之后每次请求 `streamGenerate(...)` 即可。

---

### 想用实例而不是全局单例？—— `AiluxClient`

`Ailux` 只是对一个进程级 `AiluxClient` 的薄封装。如果你需要同进程内并存多个 Provider（比如 Mock + Backend 一起跑）、按业务模块分别持有配置、或在 DI 容器里管理生命周期，直接 `new AiluxClient(...)` 即可 —— **同样是 2 步**：

```kotlin
import com.ailux.api.AiluxClient
import com.ailux.api.AiluxConfig
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.provider.mock.MockProvider

// 第 1 步 —— 构造一个 client（AiluxConfig 形状不变，三种 Provider 任选）。
val client = AiluxClient(
    AiluxConfig.Builder()
        .setProvider(MockProvider())
        .build()
)

// 第 2 步 —— streamGenerate 返回一个 LLMTask 句柄。
val task = client.streamGenerate(
    LLMRequest(messages = listOf(Message.User("hello")))
)

task.events.collect { event ->
    when (event) {
        is LLMEvent.Token -> print(event.text)
        is LLMEvent.Done  -> println("done")
        else              -> { /* ... */ }
    }
}

// 只取消当前任务，同一个 client 上的其他任务不受影响：
// task.cancel()

// 当 client 不再需要时（例如 ViewModel.onCleared()）：
// client.release()
```

> `Ailux.streamGenerate(...)` 与 `AiluxClient.streamGenerate(...)` 返回的都是 `LLMTask`：`task.events` 是你 collect 的冷流，`task.cancel()` 只取消当前请求，`task.state` / `task.lastDiagnostic()` 提供可观测性。

## 高级用法

- [扩展性指南](docs/EXTENSIBILITY-zh.md)（v0.2.4+）—— 第一部分：`LLMRequest` 三层扩展模型（强类型 / `overrides` / 自定义 `RequestMapper` 决策树）；第二部分（v0.2.5+）：Provider 四个扩展点决策树（mapper / parser / errormapper / authprovider 何时该写、4 个完整单测示例）。
- [日志策略与隐私契约](docs/LOGGING-zh.md)（v0.2.5+）—— `AiluxLogger` SPI（Timber / SLF4J / Sentry 桥接示例）、`PrivacyConfig`（默认全脱敏）、字段分类表、自定义扩展点的隐私守则。
- [诊断报告（DiagnosticReport）](docs/DIAGNOSTICS-zh.md)（v0.2.5+）—— `task.lastDiagnostic()` / `Ailux.createDiagnosticReport()` 入口、`toShareableText()` 输出格式、与 Issue Forms 的串接。
- [docs/API-zh.md](docs/API-zh.md) —— 自定义 Mock 规则、自定义 `AuthProvider`、流式事件、请求取消、一次性调用、多实例 Client、测试等完整 API 参考。

## Demo 依赖模式

示例 `app` 模块支持三种依赖模式，通过 `gradle.properties` 中的 `AILUX_DEP_MODE` 控制：

| 值 | 行为 |
| --- | --- |
| `source`（默认） | 通过 `project(":ailux-xxx")` 依赖本地源码模块，适合日常开发。 |
| `maven-umbrella` | 依赖已发布的单一聚合 artifact `io.github.kynnchuan:ailux-sdk:<version>`。 |
| `maven-split` | 依赖已发布的细分 artifact（`ailux-api`、`ailux-android`、`ailux-provider-*`）。 |

切换方式 - 编辑 `gradle.properties`：

```properties
AILUX_DEP_MODE=maven-umbrella
```

或通过命令行临时覆盖（不修改文件）：

```bash
./gradlew :samples:chat-demo:assembleDebug -PAILUX_DEP_MODE=maven-umbrella
```

> **注意：** Maven 模式需要对应 artifact 可用。可先执行 `./gradlew publishToMavenLocal` 发布到本地，或确保 Maven Central 上已有对应版本。

## 模块结构

`io.github.kynnchuan:ailux-sdk:0.2.6` 聚合 artifact 通过 `api` 透出以下全部模块：

| 模块 | 用途 |
| --- | --- |
| `ailux-core` | 核心契约层：`LLMProvider` / `LLMRequest` / `LLMResponse` / `LLMEvent`。 |
| `ailux-api`  | API 门面层：`Ailux` / `AiluxClient` / `AiluxConfig`。 |
| `ailux-android` | Android 端集成胶水层。 |
| `ailux-provider-mock` | 零依赖 Mock Provider，用于本地开发 / Demo / 测试。 |
| `ailux-provider-backend` | BackendProxy Provider + opt-in `directCloudConfig(...)` 工厂；v0.2.6 加入 `RetryPolicy` / `HttpClientConfig` / `ProtocolConfig` / `RequestSigner`。 |
| `app` | Compose Chat Demo 示例工程，含运行时 Mock↔Backend 切换与 Debug Panel。 |
| `samples/ailux-backend-sample` | 官方 Spring Boot 后端样板，演示 SSE 转发、服务端 / 客户端 FC、模型路由、鉴权配额、断连即 abort。**不**作为 Maven artifact 发布。 |

> v1.0 起将允许独立依赖单个子模块（`io.github.kynnchuan:ailux-core`、`...:ailux-provider-mock` 等），方便细粒度接入。在此之前，**优先使用聚合 artifact `ailux-sdk`**。

## 隐私与安全

- **不要把真实 API Key 提交到仓库。** 放在 `local.properties`、环境变量或密钥管理服务里。
- **不要在 Issue / PR / 日志 / 截图中暴露用户原文 prompt、模型回复或业务敏感数据。**
- 生产环境请走方式 B（自建后端代理），把凭据、内容审核、审计日志、限流都留在服务端。
- `MockProvider` 完全离线，可放心用于公开 Demo、录屏、CI。
- `directCloudConfig(...)` 使用 `@RequiresOptIn(level = ERROR)` 限制是**有意为之** —— 它**不**是推荐的生产方案。

其他文档在 [docs/](docs/) 目录下 —— [API 参考](docs/API-zh.md) · [CONTRIBUTING](docs/CONTRIBUTING-zh.md) · [CHANGELOG](docs/CHANGELOG-zh.md)。
