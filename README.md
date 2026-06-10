English | [中文](README-zh.md)

# Ailux

![CI](https://github.com/kynnchuan/ailux/actions/workflows/ci.yml/badge.svg)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kynnchuan/ailux-sdk.svg?label=Maven%20Central)](https://search.maven.org/artifact/io.github.kynnchuan/ailux-sdk)

Ailux is a lightweight Android LLM SDK that lets you integrate any large language model in minutes. Ship a working Chat UI with **zero** API keys using `MockProvider`, then flip one config to route through your own backend with `BackendProxyProvider` — same streaming API, production-ready security.

### Why Ailux?

| Pain point | Ailux solution |
| --- | --- |
| Fragmented vendor SDKs | Unified `LLMProvider` abstraction — swap OpenAI, Anthropic, DeepSeek, or any compatible endpoint without touching business code. |
| Complex SSE streaming | One `Flow<LLMEvent>` model: `Token` / `Reasoning` / `Usage` / `Error` / `ToolCallReceived` / `Done`. |
| API key leaks in APK | `BackendProxyProvider` keeps credentials server-side; direct-cloud path is gated by `@OptIn(DirectCloudUsage::class)`. |
| Can't develop without a real key | `MockProvider` — fully offline, deterministic, zero dependencies. Run demos, write tests, onboard teammates instantly. |
| Android lifecycle headaches | 5 lifecycle policies + ViewModel integration out of the box. |
| Inconsistent error handling | Unified `ErrorCode` + `LLMError` with `isRetriable` flag and automatic retry. |
| Dependency bloat | Single `implementation("io.github.kynnchuan:ailux-sdk:0.1.0")` — one line, everything included. |
| Multi-vendor protocol quirks | Pluggable `StreamResponseParser` (OpenAI + Anthropic built-in); add your own in ~20 lines. |

> **v0.1 ships as a single umbrella artifact.** One `implementation(...)` line gives you the core contract, API facade, Android integration, MockProvider, and BackendProxyProvider. Sub-modules will be split out in v1.0 for finer-grained adoption.

## v0.1 snapshot

- **Single-dependency install** — one Maven coordinate covers everything.
- **`MockProvider`** — no API key, no backend, no network. Instant Chat Demo.
- **`BackendProxyProvider`** — talks to your own backend proxy (production-recommended).
- **`directCloudConfig(...)`** — opt-in direct-to-cloud for quick prototyping (BYOK; requires `@OptIn`).
- **Streaming event model** — `Token` / `Reasoning` / `Usage` / `Error` / `Done`.
- **Request cancellation** — `Ailux.cancel()` or `client.cancel()` at any time.
- **Multi-instance support** — run Mock + real providers in the same process via `AiluxClient`.
- **Android Compose Demo** — incremental token rendering, collapsible reasoning, usage display.

<p align="center">
  <img src="assets/demo/v0.1-chat-initial.jpg" width="42%" alt="Ailux v0.1 Chat Demo initial screen" />
  <img src="assets/demo/v0.1-chat-streaming.jpg" width="42%" alt="Ailux v0.1 Chat Demo streaming screen" />
</p>

> Demo video: [`assets/demo/v0.1-chat-demo.mp4`](assets/demo/v0.1-chat-demo.mp4)

📲 **Try it now:** [Download Demo APK (v0.1.0)](assets/demo/ailux-demo-v0.1.0.apk) — runs entirely offline with `MockProvider`, no API key needed.

## Roadmap

| Status | Feature |
| --- | --- |
| ✅ Shipped | MockProvider, BackendProxyProvider, streaming events, request cancellation, multi-instance `AiluxClient`, Android lifecycle integration, Compose Chat Demo |
| ✅ v0.2.0 | Function Calling — OpenAI & Anthropic protocol parsing, `ToolCallAggregator`, multi-turn FC loop, `AnthropicRequestMapper` |
| 🚧 Next | Context Window management (LLMContextManager), token counting |
| 📋 Planned | Sub-module split (fine-grained adoption), official Backend reference implementation, privacy diagnostics |
| 💡 Exploring | On-device runtime, multi-modal support, agent orchestration |

> Roadmap items may change. "Planned" and "Exploring" do not imply a release timeline.

## Install

Add the single umbrella artifact to your app/library module:

```kotlin
dependencies {
    implementation("io.github.kynnchuan:ailux-sdk:0.1.0")
}
```

That's it. No need to pick `ailux-core` / `ailux-api` / `ailux-android` / `ailux-provider-mock` / `ailux-provider-backend` separately — the umbrella module re-exports all of them with `api`.

> Make sure `mavenCentral()` is in your repositories block:
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

Pick the path that matches your scenario. All three paths share the **same** `Ailux.streamGenerate(...)` API — only the provider construction differs.

### Option A — `MockProvider` (no API key, no network)

Best for local development, demos, and unit tests.

```kotlin
import com.ailux.api.Ailux
import com.ailux.api.AiluxConfig
import com.ailux.core.model.LLMEvent
import com.ailux.core.model.LLMRequest
import com.ailux.provider.mock.MockProvider

Ailux.init(
    AiluxConfig.Builder()
        .setProvider(MockProvider())
        .build()
)

Ailux.streamGenerate(LLMRequest(messages = listOf(Message.User("hello"))))
    .collect { event ->
        when (event) {
            is LLMEvent.Token     -> print(event.text)
            is LLMEvent.Reasoning -> print(event.text)
            is LLMEvent.Usage     -> println("usage: ${event.info}")
            is LLMEvent.Error     -> println("error: ${event.error}")
            is LLMEvent.Done      -> println("done")
        }
    }
```

### Option B — `BackendProxyProvider` (recommended for production)

Talk to **your own** backend proxy that forwards requests to the upstream model service. Credentials never leave your server.

**1. Put credentials in `local.properties` (do not commit)**

```properties
# local.properties
ailux.baseUrl=https://your-backend.example.com
ailux.apiKey=YOUR_BACKEND_TOKEN
```

**2. Bridge them to `BuildConfig` in your app `build.gradle.kts`**

```kotlin
// app/build.gradle.kts
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

**3. Wire the provider**

```kotlin
import com.ailux.api.Ailux
import com.ailux.api.AiluxConfig
import com.ailux.core.model.LLMRequest
import com.ailux.provider.backend.AuthProvider
import com.ailux.provider.backend.BackendProxyConfig
import com.ailux.provider.backend.BackendProxyProvider

val backendConfig = BackendProxyConfig(
    baseUrl = BuildConfig.AILUX_BASE_URL,
    streamEndpoint   = "/v1/chat/stream",     // your endpoint
    generateEndpoint = "/v1/chat/generate",   // your endpoint
    authProvider = AuthProvider {
        // Return the COMPLETE Authorization header value (including the scheme).
        "Bearer ${BuildConfig.AILUX_API_KEY}"
    }
)

Ailux.init(
    AiluxConfig.Builder()
        .setProvider(BackendProxyProvider())
        .setProviderConfig(backendConfig)
        .build()
)

Ailux.streamGenerate(LLMRequest(messages = listOf(Message.User("hello"))))
    .collect { /* same as Option A */ }
```

> **Parser note:** By default, `BackendProxyProvider` uses the built-in OpenAI-compatible `StreamResponseParser`. If your backend returns a different protocol format, you can supply a custom request/response parser — see [API Reference](docs/API.md) for details.

### Option C — `directCloudConfig(...)` (BYOK, prototype only)

If you don't yet have a backend proxy and just want to try the SDK end-to-end with your own cloud key, Ailux provides an opt-in factory that targets an OpenAI-compatible endpoint directly from the device.

> ⚠️ **Privacy & security warning**
>
> - Embedding a cloud API key inside an Android app exposes it to anyone who reverse-engineers your APK.
> - User prompts and model responses leave the device and reach the upstream provider directly, without your server-side moderation, audit, or rate limiting.
> - **Use this path only for personal prototyping.** For anything user-facing, switch to Option B (BackendProxyProvider).
> - Because of these risks, the API is gated by an opt-in annotation: you must explicitly write `@OptIn(DirectCloudUsage::class)`.

```kotlin
import com.ailux.api.Ailux
import com.ailux.api.AiluxConfig
import com.ailux.core.model.LLMRequest
import com.ailux.provider.backend.BackendProxyProvider
import com.ailux.provider.backend.DirectCloudUsage
import com.ailux.provider.backend.directCloudConfig

@OptIn(DirectCloudUsage::class)
fun setupDirectCloud() {
    val config = directCloudConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey  = BuildConfig.AILUX_API_KEY
        // streamEndpoint / generateEndpoint default to "/chat/completions"
    )

    Ailux.init(
        AiluxConfig.Builder()
            .setProvider(BackendProxyProvider())
            .setProviderConfig(config)
            .build()
    )

    Ailux.streamGenerate(
        LLMRequest(
            prompt = "hello",
            model  = "deepseek-v4-flash"
        )
    ).collect { /* same as Option A */ }
}
```

`local.properties` for Option C:

```properties
# local.properties
ailux.baseUrl=https://api.deepseek.com   # informational; the URL is hard-coded above
ailux.apiKey=sk-your-deepseek-key
```

## Advanced Usage

See [docs/API.md](docs/API.md) for the full API reference, including custom mock rules, custom `AuthProvider`, streaming events, request cancellation, one-shot generation, multiple clients, and testing.

## Demo dependency mode

The sample `app` module supports three dependency modes, controlled by `AILUX_DEP_MODE` in `gradle.properties`:

| Value | Behavior |
| --- | --- |
| `source` (default) | Depends on local source modules via `project(":ailux-xxx")`. Best for development. |
| `maven-umbrella` | Depends on the single published artifact `io.github.kynnchuan:ailux-sdk:<version>`. |
| `maven-split` | Depends on individual published artifacts (`ailux-api`, `ailux-android`, `ailux-provider-*`). |

Switch by editing `gradle.properties`:

```properties
AILUX_DEP_MODE=maven-umbrella
```

Or override on the command line without modifying any file:

```bash
./gradlew :samples:chat-demo:assembleDebug -PAILUX_DEP_MODE=maven-umbrella
```

> **Note:** Maven modes require the artifacts to be available. Publish to local first with `./gradlew publishToMavenLocal`, or ensure the version is live on Maven Central.

## Modules

The `io.github.kynnchuan:ailux-sdk:0.1.0` umbrella artifact transitively (`api`) re-exports all of these:

| Module | Purpose |
| --- | --- |
| `ailux-core` | Core contract layer: `LLMProvider`, `LLMRequest`, `LLMResponse`, `LLMEvent`. |
| `ailux-api`  | API facade: `Ailux`, `AiluxClient`, `AiluxConfig`. |
| `ailux-android` | Android-side integration glue. |
| `ailux-provider-mock` | Zero-dependency mock provider for local dev / demos / tests. |
| `ailux-provider-backend` | BackendProxy provider + opt-in `directCloudConfig(...)` factory. |
| `app` | Sample Compose Chat Demo. |

> v1.0 will let you depend on individual sub-modules (`io.github.kynnchuan:ailux-core`, `...:ailux-provider-mock`, etc.) for finer-grained adoption. Until then, prefer the umbrella artifact `ailux-sdk`.

## Privacy and security

- **Never commit real API keys to the repository.** Keep them in `local.properties`, environment variables, or a secret manager.
- **Never expose raw user prompts, model responses, or business-sensitive data** in issues, PRs, logs, or screenshots.
- For production, route requests through your own backend (Option B) so credentials, moderation, audit logging, and rate limiting stay server-side.
- `MockProvider` is fully offline and safe for public demos, screen recordings, and CI.
- The `directCloudConfig(...)` path is gated by `@RequiresOptIn(level = ERROR)` on purpose — it is **not** a recommended production path.

Other docs under [docs/](docs/) — [API Reference](docs/API.md) · [CONTRIBUTING](docs/CONTRIBUTING.md) · [CHANGELOG](docs/CHANGELOG.md).
