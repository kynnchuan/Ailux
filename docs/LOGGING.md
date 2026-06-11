# Ailux Logging Policy & Privacy Contract

[← Back to README](../README.md) · [中文](LOGGING-zh.md)

> Applies to: v0.2.5+
> Related design: [`v0.2.5 spec`](../ailux-docs/specs/v0.2/v0.2.5-extensibility-and-privacy.md)
> See also: [Extensibility Guide](EXTENSIBILITY.md)

The Ailux logging policy in one sentence: **Secure by default — even if you wire in a logger that writes everything, prompt / response / overrides will not appear in logcat** unless you explicitly opt in.

This document covers three things:
1. The default behaviour (what happens when you do **nothing**).
2. The `AiluxLogger` SPI (how to bridge SDK logs into Timber / SLF4J / Sentry).
3. `PrivacyConfig` (how to selectively open/close which fields).

---

## 1. Default behaviour: what happens out of the box?

```kotlin
// Minimal config — no explicit logger / privacy
val config = AiluxConfig(providerConfig = backendConfig)
```

| Field | Default behaviour |
|---|---|
| `logger` | `NoopAiluxLogger` — **completely silent**, not even logcat |
| `privacy.logPrompt` | `false` — even if you swap in a writing logger, prompt content is replaced with `[prompt redacted: ***]` |
| `privacy.logResponse` | `false` — same for token stream / reasoning / response body |
| `privacy.logOverrides` | `false` — `overrides` content (which may contain trace_id / user_id / etc.) is redacted |
| `Authorization` header / API key | **Never** logged (stripped at the call site, independent of `PrivacyConfig`) |

This is the strictest secure-by-default posture: ship to production with zero config and the SDK leaks nothing sensitive.

## 2. `AiluxLogger` — bridge SDK logs into your stack

`AiluxLogger` is an `interface` (not a `fun interface` — it carries 5 default convenience methods `v/d/i/w/e`):

```kotlin
public interface AiluxLogger {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)
    // 5 default convenience methods:
    fun v(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.VERBOSE, ...)
    fun d(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.DEBUG, ...)
    fun i(...) ; fun w(...) ; fun e(...)
}
```

Implementers override only `log(...)`. Callers write `logger.d("Tag", "msg")` directly.

### Built-in implementations

| Implementation | Module | Use case |
|---|---|---|
| `NoopAiluxLogger` | `ailux-core` | Default. Recommended for production. Zero overhead, zero output. |
| `AndroidAiluxLogger` | `ailux-android` | Bridges to `android.util.Log`. **Recommended for development**, writes logcat. Still subject to `PrivacyConfig`. |

### Bridging to Timber

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

### Bridging to SLF4J

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

### Bridging to Sentry / any "exceptions only" stack

```kotlin
object SentryLogger : AiluxLogger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (throwable != null && level >= LogLevel.WARN) {
            Sentry.captureException(throwable, mapOf("ailux_tag" to tag, "msg" to message))
        }
        // drop info level so the Sentry quota doesn't blow up
    }
}
```

> **Privacy contract**: every bridge implementation receives a `message` that has already been processed by the SDK's internal `RedactingLogSink` against the active `PrivacyConfig`. You do **not** need to redact again.

## 3. `PrivacyConfig` — explicitly open the fields you need

```kotlin
public data class PrivacyConfig(
    val logPrompt: Boolean = false,
    val logResponse: Boolean = false,
    val logOverrides: Boolean = false,
    val logHeaders: Boolean = false,         // emit outbound HTTP headers (credential headers stay scrubbed)
    val logRequestBody: Boolean = false,     // emit the full HTTP request body
    val redactionMask: String = "***",
    val maxLoggedBodyLength: Int = 2048,     // defensive cap for body-class log lines
)
```

### Preset constants

| Constant | Equivalent to | When to use |
|---|---|---|
| `PrivacyConfig.SECURE_DEFAULT` | All flags `false`, 2048 cap | Production. Same as `PrivacyConfig()`. |
| `PrivacyConfig.DEBUG_VERBOSE` | `logPrompt=true, logResponse=true, logOverrides=true` (headers/body still off) | Short debug sessions. **Never hard-code into a release build.** |

### Behaviour matrix

| Scenario | logPrompt | logResponse | logOverrides | logHeaders | logRequestBody | Result |
|---|:--:|:--:|:--:|:--:|:--:|---|
| Default (production) | `false` | `false` | `false` | `false` | `false` | All content replaced with `[xxx redacted: ***]` |
| `DEBUG_VERBOSE` preset | `true` | `true` | `true` | `false` | `false` | prompt/response/overrides full; headers/body still redacted |
| Everything on (local extreme triage) | `true` | `true` | `true` | `true` | `true` | All fields full — **never ship this** |
| Gateway triage (request only) | `true` | `false` | `true` | `false` | `false` | Only prompt + overrides full |
| Content moderation triage | `false` | `true` | `false` | `false` | `false` | Only response full |

> Even with `logHeaders=true`, the five credential headers `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key` are still replaced with `redactionMask`. There is no path that flips a single switch to leak credentials.

### Custom redaction mask

Replace the default `***` with something that matches your company's log compliance format:

```kotlin
val privacy = PrivacyConfig(redactionMask = "[REDACTED-${BuildConfig.VERSION_NAME}]")
val config = AiluxConfig.Builder()
    .setProvider(provider)
    .setPrivacyConfig(privacy)
    .build()
```

### "Debug locally, never in production"

```kotlin
val config = AiluxConfig.Builder()
    .setProvider(provider)
    .setPrivacyConfig(
        if (BuildConfig.DEBUG) PrivacyConfig.DEBUG_VERBOSE
        else PrivacyConfig.SECURE_DEFAULT
    )
    .build()
```

ProGuard / R8 constant-folds `BuildConfig.DEBUG=false` in release builds — the entire `if` is compiled down to `SECURE_DEFAULT`. Zero runtime branch, zero "oops, left it on" risk.

### Body truncation

When `logRequestBody=true`, any body longer than `maxLoggedBodyLength` (default 2048 chars) is truncated and stamped with `... [truncated: N chars dropped]`. This is a defensive cap that prevents a single log line from blowing up to several megabytes ("incident-grade logging"). Set it to `Int.MAX_VALUE` to disable.

## 4. SDK field classification (what's logged by default vs redacted)

| Class | Field | Logged by default? | Toggle |
|---|---|:--:|---|
| Metadata | `requestId`, provider type, model name, HTTP status, error code | ✅ | Always (`logSafe`) |
| Timing | TTFT, total duration, stall detection, retry count | ✅ | Always (`logSafe`) |
| **Request content** | `messages` text, `tools` definitions, `attachments` metadata | ❌ | `logPrompt` |
| **Response content** | `Token` text, `Reasoning` text, `ToolCall.arguments`, HTTP response body | ❌ | `logResponse` |
| **Overrides** | `overrides` JSON | ❌ | `logOverrides` |
| **HTTP headers** | All outbound headers (incl. custom headers) | ❌ | `logHeaders` (credential headers always scrubbed) |
| **HTTP request body** | Full JSON body | ❌ | `logRequestBody` |
| **Credentials** | `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key` | ❌ | **Never togglable** (always stripped) |

## 5. Privacy rules when writing custom extension points

When implementing [custom mapper / parser / errormapper / authprovider](EXTENSIBILITY.md#part-2--provider-four-extension-point-decision-tree-v025), **call** the `AiluxLogger` convenience methods directly — the SDK has already piped them through `RedactingLogSink` behind the scenes.

```kotlin
class MyParser(private val logger: AiluxLogger) : StreamResponseParser {
    override fun parse(eventType: String, data: String): List<LLMEvent> {
        // ✅ Safe: log only metadata
        // ❌ Wrong: do not splat `data` / `request.messages` / `Authorization` into log messages
        if (eventType == "unknown_v3") {
            logger.w("MyParser", "unrecognised event=$eventType (forward-compat skip)")
        }
        return parseInternal(eventType, data)
    }
}
```

**Two iron rules**:
1. Inside extension-point code, do not splice `data` / `request.messages` / `Authorization` into log messages.
2. If you really must log content for debug, gate it behind your own build flag — **don't** circumvent `PrivacyConfig`.

## 6. Redacted diagnostic for bug reports

`Ailux.createDiagnosticReport().toShareableText()` produces a diagnostic that **never** contains prompt / response / overrides / headers / body — safe to paste into a GitHub issue. See [DIAGNOSTICS.md](DIAGNOSTICS.md) and the [bug report template](../.github/ISSUE_TEMPLATE/bug_report.yml).
