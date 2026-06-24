# Ailux Diagnostic Report

[← Back to README](../README.md) · [中文](DIAGNOSTICS-zh.md)

> Applies to: v0.2.5+ (call surface updated for v0.3.0b session-only API; see [ADR-0009](../ailux-docs/decisions/adr/0009-session-only-single-pipeline.md))
> Related: [Logging & Privacy](LOGGING.md) · [Extensibility guide](EXTENSIBILITY.md)

In one line: **`DiagnosticReport` is the "paste-this-into-a-GitHub-Issue redacted snapshot" that Ailux hands you.**

Three design principles:
1. **Redacted by construction** — prompt, response, overrides, headers, and body content never enter a diagnostic. Only timing, error class/code, retry counters, and provider/model identifiers do.
2. **No configuration changes that** — diagnostics and `PrivacyConfig` are two parallel iron rules. There is no path that flips a verbose switch and starts leaking.
3. **Two entry points** — `task.lastDiagnostic()` for one request; `Ailux.createDiagnosticReport()` for the last N requests plus an SDK-wide snapshot.

---

## 1. Two entry points

### Task-level: `task.lastDiagnostic()`

Once an `LLMTask` reaches a terminal state (Completed / Failed / Cancelled), `lastDiagnostic()` returns the immutable report. Returns `null` before terminal. Repeated reads return the same object.

```kotlin
Ailux.openSession().use { session ->
    val task = session.streamGenerateAsTask(request)
    task.events.collect { /* ... */ }

    // Read any time after terminal state.
    task.lastDiagnostic()?.toShareableText()?.let { copyToClipboard(it) }
}
```

### Session-level: `Ailux.createDiagnosticReport(includeRecentTasks: Int = 5)`

Aggregates from the active default client:
- SDK version + provider class name + current model name
- Snapshot of the active `PrivacyConfig` flags
- Most recent N finished task diagnostics (newest first). Defaults to 5, capped by the per-client ring buffer (currently 16).

```kotlin
// Triggered when the user says "the answer never came back"
val report = Ailux.createDiagnosticReport(includeRecentTasks = 5)
copyToClipboard(report.toShareableText())
```

For multi-client scenarios call `AiluxClient.createDiagnosticReport(...)` directly on the instance you want.

## 2. What `toShareableText()` looks like

Task-level:

```
=== Ailux Diagnostic ===
SDK: 0.2.5
Time: 2026-06-11T11:32:13Z
Task: chat-7f3a (provider=BackendProxyProvider, model=gpt-4o-mini)
Outcome: Failure(HttpException, code=RATE_LIMITED, "rate limited")
Timing: TTFT=312ms, total=1480ms
Retries: 2
  [0] http_429, delay=1000ms
  [1] http_429, delay=2000ms
Privacy: prompt=off, response=off, overrides=off, headers=off, body=off
========================
```

Session-level (excerpt):

```
=== Ailux Diagnostic ===
SDK: 0.2.5
Time: 2026-06-11T11:32:13Z
Scope: session (provider=BackendProxyProvider, model=gpt-4o)
Outcome: Pending
Retries: 0
Privacy: prompt=off, response=off, overrides=off, headers=off, body=off
Recent tasks: 3
  [0] task-C ok total=830ms
  [1] task-B fail/RATE_LIMITED total=420ms
  [2] task-A ok total=512ms
========================
```

Format guarantees:
- Pure ASCII. No emoji, no decoration.
- Fixed line prefixes (`SDK:`, `Time:`, `Task:`, `Outcome:`, `Timing:`, `Retries:`, `Privacy:`, `Recent tasks:`) — grep friendly.
- Stable opening/closing `=== ... ===` markers.
- These guarantees hold across patch versions — tooling can rely on them.

## 3. Data model

```kotlin
public data class DiagnosticReport(
    val sdkVersion: String,
    val timestamp: Long,
    val taskId: String?,              // null = session-level
    val provider: String,
    val model: String?,
    val timing: TimingMetrics,
    val outcome: Outcome,             // Success / Failure / Cancelled / Pending
    val retries: List<RetryAttempt>,
    val privacy: PrivacyConfigSnapshot,
    val recentTasks: List<DiagnosticReport> = emptyList(),
    val notes: List<String> = emptyList(),
)
```

- `TimingMetrics` exposes `startedAt` / `firstTokenAt` / `finishedAt` plus derived `ttftMs` / `durationMs`.
- `Outcome` is a sealed hierarchy: `Success` / `Failure(errorClass, errorCode, errorMessage)` / `Cancelled` / `Pending`.
- `RetryAttempt(index, reason, delayMs)` records each automatic retry.
- `PrivacyConfigSnapshot` freezes the five privacy flags at capture time — the receiver can tell at a glance which channels were redacted and which were unavailable.

## 4. JSON output

```kotlin
val json = report.toJson()
```

Hand-rolled, dependency-free stable JSON:
- Fixed field order.
- Strings escaped per JSON spec (`\\`, `\"`, `\n`, `\r`, `\t`, and control characters as `\uXXXX`).
- All values are primitive types — easy to forward to your own telemetry channel (Sentry breadcrumb, internal Kafka, etc.).

> The JSON shape does not carry the same patch-stability guarantee as `toShareableText()`. If you plan to long-term machine-parse it, pin your own JSON schema downstream.

## 5. Wiring with Issue Forms

The `.github/ISSUE_TEMPLATE/bug_report.yml` (English) / `bug_report_zh.yml` (Chinese) templates already include a "Diagnostic report (recommended)" field asking for `toShareableText()` output. Three-step flow:

1. The user taps a "Copy diagnostic" button in the demo or in your own app (the demo ships a Debug Panel; your own app wires ClipboardManager).
2. The app calls `Ailux.createDiagnosticReport().toShareableText()` and writes it to the clipboard.
3. The user opens a GitHub Issue and pastes per the template.

Because diagnostics never contain content, you do not need to ask the user for a second pass of redaction.

## 6. Compliance checklist — what is NOT in a diagnostic

| Field | In the diagnostic? |
|---|:--:|
| `messages` content | ❌ |
| `Token` / `Reasoning` text | ❌ |
| `overrides` JSON | ❌ |
| HTTP headers (incl. Authorization) | ❌ |
| HTTP request / response body | ❌ |
| API key / OAuth token | ❌ |
| User identifiers (unless you add one via `notes`) | ❌ |
| **What IS present**: SDK version, provider class name, model name, error code, error message (from `LLMError.message`), TTFT / total duration, retry count | ✅ |

If you extend `RequestMapper` / `StreamResponseParser` / `AuthProvider` and throw an exception whose `message` carries sensitive info, that message surfaces in `Outcome.Failure.errorMessage`. That is your compliance boundary — the SDK cannot decide it for you. **Keep custom-extension exception messages restrained.**

## 7. FAQ

**Q: Does the diagnostic get uploaded to Ailux servers automatically?**
A: No. The SDK ships no telemetry channel. Diagnostics stay local; your app decides what to do with them (log file, clipboard, your own telemetry, paste into an Issue).

**Q: Can I attach custom notes per task?**
A: Right now `DiagnosticReport.notes` is populated by post-hoc `copy(notes = ...)` on the report returned by `createDiagnosticReport(...)`. A task-level `note()` API is on the table for the next release.

**Q: Can the `recentTasks` capacity be tuned?**
A: Currently fixed at 16 inside `AiluxClient`'s ring buffer. File an issue if you need more.

**Q: Why UTC timestamps?**
A: To avoid ambiguity when an issue crosses time zones. Re-format `report.timestamp` if you need a local string.
