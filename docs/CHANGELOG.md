English | [中文](CHANGELOG-zh.md)

# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to Semantic Versioning.

## [Unreleased]

_No unreleased changes yet._

---

## [0.2.6] - 2026-06-17

Theme: **`BackendProxyProvider` production hardening.** v0.2.0~0.2.5 made the SDK architecturally mature; v0.2.6 closes the real production gaps in the transport layer — non-streaming parse symmetry, retry policy with backoff, `OkHttpClient` injection, streaming usage, cancel/billing-boundary docs, a three-way config split, and the auth-failure closed loop. Spec: [`v0.2.6-backendproxy-hardening`](../ailux-docs/specs/v0.2/v0.2.6-backendproxy-hardening.md).

> Status: **all 8 stages landed** (H-1 / H-2 / H-3 / H-4 / H-6 / H-7 / H-X / three-way config split). Released as the v0.2 series GA on Maven Central.

### Added

- **Auth-failure closed loop (H-7).** A complete refresh-and-replay path for HTTP 401, designed as **mechanism, not policy** (refresh strategy stays a business decision):
  - `AuthProvider.onUnauthorized(): Boolean` — default-method extension on the SAM interface (zero-breaking: existing lambdas still compile). Return `true` after a successful refresh; the provider replays the request **once per task**, threaded through the same `RetryPolicy` pipeline (no parallel retry plumbing).
  - `ErrorCode.AUTH_EXPIRED` (retriable) — surfaces when `onUnauthorized()` returned `true` but the replay still failed, so UIs can distinguish "silent re-login attempted" from a flat `AUTH_FAILED`.
  - **Replay budget is one per task, independent of `RetryPolicy.maxRetries`** — works even with `RetryPolicy.NONE`. Streaming and non-streaming paths share the same logic; collectors never see a spurious `LLMEvent.Error` from the recovered 401.
  - **`RequestSigner` + `SignableRequest`** (`provider-backend/auth`) — separate hook for backends that need request-level integrity signing (HMAC over body, replay timestamps, AWS SigV4-style). Runs **after** auth / custom headers / `Idempotency-Key`, so signers can deliberately overlay any of them. Kept orthogonal to `AuthProvider` because `getAuthToken()` cannot see the request body.
  - **`CachingAuthProvider` example** with `Mutex` single-flight refresh — 16 concurrent 401s fire exactly one IdP round-trip.
  - **18 new tests** (5 example + 13 end-to-end via MockWebServer) covering happy path, second-401-after-refresh, refresh-declined, throwing refresher, no-AuthProvider, `RetryPolicy.NONE` interop, replay-budget bound, signer header override, signer no-op, and the matching streaming variants. **153 backend tests pass.**


- **`NonStreamResponseParser` extension point** (`provider-backend`). New `fun interface NonStreamResponseParser`; built-in `OpenAINonStreamResponseParser` aligns with `choices[0].message.content`, `AnthropicNonStreamResponseParser` aligns with the latest Messages API. Symmetric with the streaming extension point and eliminates the previous home-grown `{"text": ...}` envelope (**breaking**, see Changed).
- **`RetryPolicy`** (`provider-backend/config`). Exponential backoff + decorrelated jitter + `Retry-After` honored, unified across streaming and non-streaming. `BackendProxyConfig.retryCount: Int` is **removed**; new `BackendProxyConfig.retryPolicy: RetryPolicy` replaces it. `ErrorCode.retriable` is the sole input; `ErrorCode.SERVER_ERROR` (retriable) is added so HTTP 5xx actually retries.
- **`HttpClientConfig`** (`provider-backend/config`). Injection points for the real transport stack:
  - `baseHttpClient: OkHttpClient?` — derive from a pre-tuned client (cert pinning / proxy / DNS / interceptors / **cross-instance connection pool reuse** / mTLS via `sslSocketFactory` + `X509TrustManager`);
  - `customizer: (OkHttpClient.Builder) -> Unit` — last-mile customization runs after `baseHttpClient.newBuilder()`;
  - `connectTimeoutMs` / `readTimeoutMs` / `writeTimeoutMs` move here from `BackendProxyConfig`.
- **`ProtocolConfig`** (`provider-backend/config`). Co-locates the protocol-layer extension points: `requestMapper`, `streamResponseParser`, `nonStreamResponseParser`, `errorMapper`, and **`includeUsageInStream: Boolean` (default true)** that injects `stream_options.include_usage` into outbound OpenAI bodies so the SDK actually receives the final `choices:[] + usage` frame.
- **Cancellation & billing boundary**, documented honestly:
  - KDoc on `LLMTask.cancel()` / `AiluxClient.cancelAll()` / `BackendProxyProvider.streamGenerate()` / `generate()` makes explicit that `cancel` disconnects the client but **does not guarantee the upstream model stops generating or billing**;
  - `samples/ailux-backend-sample` demonstrates the "disconnect = abort" pattern via `OkHttp Call.cancel()` and ships `X-Accel-Buffering: no` on SSE responses to defeat reverse-proxy buffering;
  - `CancellationSemanticsTest` (5 cases) covers stream/non-stream cancel, cancel-during-backoff, and "no spurious events after cancel".
- **Tests.** `NonStreamResponseParser`: OpenAI (~260 lines) and Anthropic (~183 lines) parser tests; `DefaultErrorMapperTest` (14 cases) closes the 4xx/5xx mapping matrix; `OpenAIRequestMapperTest` extended with `stream_options.include_usage` cases. **136 tests pass** post-refactor.

### Changed

- **Breaking** — `BackendProxyProvider` constructor takes three configs: `BackendProxyProvider(config: BackendProxyConfig, httpConfig: HttpClientConfig = HttpClientConfig(), protocolConfig: ProtocolConfig = ProtocolConfig())`. `BackendProxyConfig` retains "who to connect to" (baseUrl, endpoints, auth, retry, headers); `HttpClientConfig` owns the transport stack; `ProtocolConfig` owns mappers/parsers/errorMapper. Defaults preserve old behaviour, but every call site that passed transport timeouts or custom parsers needs to migrate to the new split.
- **Breaking** — `BackendProxyConfig.retryCount: Int` is removed; replaced by `BackendProxyConfig.retryPolicy: RetryPolicy` (no retries by default; opt in with `RetryPolicy(maxAttempts = 3)` or finer-grained construction).
- **Breaking** — Non-streaming responses are now parsed via `NonStreamResponseParser`. The legacy `{"text": ...}` envelope produced by the previous home-grown path is gone; bring your own backend to the OpenAI / Anthropic non-streaming contract (or implement a custom `NonStreamResponseParser`). No real backend ever produced the legacy format, so the blast radius is essentially zero in practice.
- `provider-backend/parser/` is reorganised into `parser/stream/` and `parser/nonstream/`. Update imports accordingly.
- `DirectCloudConfig` returns `Pair<BackendProxyConfig, HttpClientConfig>` to match the split.

### Fixed

- `OpenAINonStreamResponseParser` previously read the wrong usage field names (`input_tokens` / `output_tokens` instead of `prompt_tokens` / `completion_tokens`), so non-streaming usage reads always returned 0/0.
- `DefaultErrorMapper` now correctly maps HTTP 5xx to `ErrorCode.SERVER_ERROR` (retriable) — previously misclassified into a non-retriable bucket, defeating the retry policy for genuine server errors.
- Demo `samples/chat-demo` no longer leaks the previously-dead `includeUsageInStream` config field — it now flows through `ProtocolConfig` into the default `OpenAIRequestMapper`.

### Out of scope (this version, on purpose)

- **#5 weak-network "recovery" (partial result continuation / resumable download / auto-fallback)**: actively rejected — recovery is policy, not transport-layer mechanism. The SDK's duty is to **not eat the information** (errorCode / `StallDetected` / `requestId` / `Idempotency-Key`), not to encode a recovery protocol. See [ADR-0006](../ailux-docs/decisions/adr/0006-fallback-predicate-and-best-effort.md) and v0.2.6 spec §1.3 Q1.
- **SSE reorder / loss repair (P1-2)**, **HTTP 413 / context pre-check (P1-1/P1-7)**, **`TtftTimeoutPolicy` (P1-6)**, **`DiagnosticReport v2` timeline/waterfall (P1-8)**: deferred to v0.4 (see [ADR-0008](../ailux-docs/decisions/adr/0008-v04-redefine-governance.md) for the post-rescope cuts).

---

## [0.2.5] - 2026-06-11

Theme: **Production-grade extensibility guide + privacy/diagnostics groundwork.** Folds the original v0.2.5 (extension-point guide) and v0.2.6 (privacy + diagnostics) into a single release: line 1 — the four-extension-point decision tree with compile-checked living examples; line 2 — the `AiluxLogger` SPI + secure-by-default `PrivacyConfig` + redaction-safe `DiagnosticReport`. Spec: [`v0.2.5-extensibility-and-privacy`](../ailux-docs/specs/v0.2/v0.2.5-extensibility-and-privacy.md).

### Added

- **Extension-point decision tree** (`docs/EXTENSIBILITY.md` Part 2). Side-by-side decision tree for the four `BackendProxyConfig` `fun interface` extension points — `RequestMapper` / `StreamResponseParser` / `ErrorMapper` / `AuthProvider` — focused on *when* to write a custom implementation, not just *how*. Includes one-stop wiring example and an anti-pattern table.
- **Four living examples**, packaged as unit tests under `ailux-provider-backend/src/test/.../examples/` so they are compile-checked and never rot:
  - `AcmeRequestMapperExampleTest` — fictional in-house protocol (`messages → chat_history`, `role → speaker`, system messages hoisted to top-level `directives`), still calls `applyOverrides()` so the escape hatch composes;
  - `AcmeStreamResponseParserExampleTest` — custom SSE `event: delta/metrics/finish`, finish-reason translation, unknown event/reason tolerated;
  - `BizCodeErrorMapperExampleTest` — enterprise gateway HTTP 200 + business code envelope, including IOException/Timeout/malformed-JSON fallbacks landing on `retriable = true`;
  - `OAuthClientCredentialsAuthProviderExampleTest` — OAuth2 client_credentials with `Mutex` single-flight refresh (16 concurrent callers → one network round-trip).
- **`AiluxLogger` SPI** (`ailux-core/logging`). `fun interface` with `v/d/i/w/e` default methods + `LogLevel` + `NoopAiluxLogger`. Host code calls `logger.d(...)` directly. `ailux-android/logging/AndroidAiluxLogger` bridges `android.util.Log` with 23-char tag truncation and per-level filtering — but `ailux-core` itself has zero Android dependency.
- **`RedactingLogSink`** (`ailux-core/logging/internal`). SDK-internal mediator that enforces `PrivacyConfig` at every log site so feature code never reaches the raw logger directly.
- **`PrivacyConfig`** (`ailux-core/privacy`). Secure-by-default data class with five toggles (`logPrompt` / `logResponse` / `logOverrides` / `logHeaders` / `logRequestBody`) plus `redactionMask` (default `***`) and `maxLoggedBodyLength` (default 2048).
  - `PrivacyConfig.SECURE_DEFAULT` (production) and `PrivacyConfig.DEBUG_VERBOSE` (short debugging sessions) factories.
  - Even when `logHeaders = true`, `RedactingLogSink` permanently redacts `Authorization` / `Proxy-Authorization` / `Cookie` / `Set-Cookie` / `X-Api-Key` (case-insensitive).
  - `logRequestBody` / `logResponseBody` truncate beyond `maxLoggedBodyLength` with an explicit truncation marker.
  - Wired via `AiluxConfig.Builder.setLogger(...)` / `setPrivacyConfig(...)` — defaults stay safe (`NoopAiluxLogger` + `SECURE_DEFAULT`).
- **`DiagnosticReport`** (`ailux-core/diagnostics`). New `DiagnosticReport`, sealed `Outcome`, `TimingMetrics`, `RetryAttempt`, `PrivacyConfigSnapshot`.
  - `toShareableText()` is pure ASCII; `toJson()` is hand-rolled with no `kotlinx.serialization` dependency.
  - **Field-level contract**: a `DiagnosticReport` never carries prompt / response / `overrides` / headers / request body, regardless of `PrivacyConfig` — safe to paste into a public issue tracker by construction.
  - `DiagnosticsRecorder` is an `AtomicReference` state machine with CAS-finalised terminal report; once terminal, every mutating method is a no-op and `lastReport()` is repeatable.
  - `AiluxClient` instruments `streamGenerate` end-to-end (`onStart`, `onFirstContent` anchored on the first `Token | Reasoning | ToolCallDelta | ToolCallReceived`, `onSuccess | onFailure | onCancel | onRetry`, archive on terminal). A 16-slot ring buffer keeps recent tasks queryable via `Ailux.createDiagnosticReport(includeRecentTasks = 5)`.
  - `LLMTask.lastDiagnostic()` default impl returns `null`; `DefaultLLMTask` delegates to its recorder.
- **Logging policy doc** (`docs/LOGGING.md` / `LOGGING-zh.md`). What gets logged by default, what is permanently redacted, how `PrivacyConfig` flags compose.
- **Diagnostics doc** (`docs/DIAGNOSTICS.md` / `DIAGNOSTICS-zh.md`). End-to-end walkthrough of `DiagnosticReport` — fields, redaction contract, share-text format, JSON schema.
- **GitHub issue templates** (`.github/ISSUE_TEMPLATE/bug_report.yml` + `bug_report_zh.yml`). Reference `Ailux.createDiagnosticReport()?.toShareableText()` and explicitly state the report is "redaction-safe by contract".
- Demo `samples/chat-demo` wires `AiluxLogger` + `PrivacyConfig` + diagnostics: the Debug Panel can copy a redacted report to the clipboard at runtime.

### Changed

- `ailux-core` exposes the logging SPI and privacy/diagnostics packages on its public API surface. No transitive Android dependency was introduced.
- `AiluxConfig.Builder` gains `setLogger(AiluxLogger)` and `setPrivacyConfig(PrivacyConfig)`; defaults remain `NoopAiluxLogger` + `PrivacyConfig.SECURE_DEFAULT` so existing apps see zero behaviour change.

### Out of scope (this version, on purpose)

- `B1-5` configurable endpoint — already shipped in earlier versions as `BackendProxyConfig.streamEndpoint / generateEndpoint / headers`; this version only documents it.
- Auto-shipping diagnostic reports to a remote endpoint, on-disk persistence, and `DiagnosticReport v2` (timeline + waterfall) — these are policy decisions, deferred to v1.0 evaluation per [ADR-0008](../ailux-docs/decisions/adr/0008-v04-redefine-governance.md).

---

## [0.2.4] - 2026-06-11

Theme: **`LLMRequest` extensibility paradigm + multimodal transport + idempotency.** Reshapes `LLMRequest` extensibility from "either add a strong-typed field or rewrite a mapper" into a three-tier model — strong-typed fields / `overrides` escape hatch / custom `RequestMapper` — and locks in the contract layer for multimodal, idempotency, and the Debug Panel before 1.0.

### Added

- **Three-tier extensibility paradigm.** New structured escape hatch `LLMRequest.overrides: JsonObject` is merged at the top level of the request body by every mapper and may override strong-typed fields. Both built-in mappers (OpenAI / Anthropic) consume it through a shared `applyOverrides()` convention, so adopters can inject any backend-specific field without rewriting a mapper or waiting for an SDK release.
- **Multimodal transport.** Flat data model `Attachment(source, mimeType)` paired with sealed `AttachmentSource{Url/Base64/LocalUri}`. `OpenAIRequestMapper` serializes to OpenAI content-parts; `AnthropicRequestMapper` serializes to Anthropic image blocks. New `ErrorCode.UNSUPPORTED_MODALITY` covers the case where a `BackendProxyProvider` receives an unconverted `LocalUri`. Inference always stays on the backend — the SDK only handles transport.
- **`Attachment.fromLocalUri` helper** (`ailux-android`). Reads `content://` / `file://` through `ContentResolver` and ships an embedded RFC 4648 Base64 encoder with a `maxBytes` cap (defaults to 20 MiB), so camera and gallery captures can flow through `BackendProxyProvider` in a single line.
- **Cross-protocol strong-typed field** `LLMRequest.stop: List<String>` — maps to OpenAI `stop` and Anthropic `stop_sequences`, the canonical example of what qualifies for the "high-frequency ∧ cross-protocol" inclusion bar.
- **Request idempotency.** `BackendProxyProvider` injects an `Idempotency-Key` HTTP header (defaulting to `LLMRequest.requestId`, reused across automatic retries). The header name is configurable via the new `BackendProxyConfig.idempotencyHeaderName` (set to `null` to disable injection).
- **Demo Debug Panel.** A Compose `ModalBottomSheet` runtime configuration panel under `samples/chat-demo/.../debug/`, fulfilling v0.2.2 §14.4.5: covers provider/model/context_mode/account/session/stop/overrides/attachments plus concurrency policy, stall detection, and i18n (CN/EN). All v0.2.2~v0.2.4 features can be exercised at runtime without a rebuild.
- **Tests.** `OpenAIRequestMapperTest` (22 cases), `AnthropicRequestMapperTest` extended with seven cases for stop / multimodal / overrides, and `AttachmentExtTest` covering the Base64 encoder and read-cap behavior.

### Changed

- **Renames (breaking).** `LLMRequest.extras: Map<String,String>` → `overrides: JsonObject`; `DefaultRequestMapper` → `OpenAIRequestMapper` (clearer protocol semantics); `ContextOverride` → `ContextPolicy` (disambiguates from the new `overrides` field).
- `ailux-core`'s dependency on `kotlinx-serialization-json` is promoted from `implementation` to `api` because `JsonObject` now lives on the public surface.
- Cancel semantics are now documented honestly: KDoc on `Ailux.cancel` / `LLMTask.cancel` / `BackendProxyProvider` makes it explicit that `cancel` disconnects the client but does **not** guarantee the upstream model stops generating or billing — the SDK refuses to mandate any "abort signal protocol", preserving the "any backend is acceptable" red line.

### Known gaps

- README three-tier extensibility guide and `extras → overrides` migration notes, a `BackendProxyProvider`-level idempotency unit test, and the `ailux-compose` sub-project roadmap update are still being landed (spec §11.2).

---

## [0.2.3] - 2026-06-10

Theme: **Concurrency coordination, stall detection, and per-request task handle.** Replaces the "single global `AiluxClient.cancel()`" model with a per-request `LLMTask` handle, and adds a heartbeat against silently stalled long streams.

### Added

- **`LLMTask` interface.** A handle for a single request, exposing `id` / `events: SharedFlow<LLMEvent>` / `state` / `cancel()`. `AiluxClient.streamGenerate()` now returns an `LLMTask` instead of a raw `Flow<LLMEvent>`.
- **Concurrency policy.** `ConcurrencyPolicy{ PARALLEL / CANCEL_PREVIOUS / ENQUEUE / REJECT }` plus a `ConcurrencyCoordinator` (`synchronized` + `Mutex` for FIFO ENQUEUE) selectable through `AiluxConfig.concurrencyPolicy`. `LLMTaskState` gains a `Queued` state.
- **Stall detection.** `StallDetection` Flow operator + `StallState` enum + `StallPhase`, attachable to any `Flow<LLMEvent>`. New non-terminal events `LLMEvent.StallDetected` and `LLMEvent.Connected` let UIs distinguish "still waiting for the first token" from "stuck mid-stream". Configured via `AiluxConfig.streamConfig.stallDetection`.
- **Three-tier consumption API** (`LLMTask` extensions). Level 1 `tokenFlow()` for plain text; Level 2 `handle { onToken / onReasoning / onToolCall / onDone / onError }` DSL; Level 3 raw `task.events.collect {}` for full control.
- **Android adapters.** `AiluxViewModel` / `AiluxClientDelegate` migrate to a `flatMapLatest` over `currentTask`, automatically tracking the most recent request. `LifecycleExt.collectState` becomes an extension on `LLMTask` rather than `AiluxClient`.

### Changed

- **Breaking.** `AiluxClient.streamGenerate()` return type changes from `Flow<LLMEvent>` to `LLMTask` (migration: `client.streamGenerate(req).collect { }` → `client.streamGenerate(req).events.collect { }`, or use the new `handle {}` DSL).
- `LLMException` is promoted from `ailux-provider-backend` into `ailux-core/error` so all providers share a single exception type.
- `LLMEvent` becomes a sealed class with two new branches (`Connected`, `StallDetected`); exhaustive `when` blocks need updating.
- Repository layout: `chat-demo` and `ailux-backend-sample` move under `samples/`; the demo package is renamed to `com.ailux.chatdemo`.

---

## [0.2.2] - 2026-06-09

Theme: **Backend reference implementation lands + runtime provider switching.** Upgrades "integrating with a production backend" from "read the KDoc and improvise" to "a Spring Boot sample you can boot end-to-end", and lets the demo flip between Mock and BackendProxy without recompiling.

### Added

- **`samples/ailux-backend-sample`.** A complete Spring Boot 3.3 + Java 17 + H2 + JPA + OkHttp blueprint: token authentication (three preset accounts free/pro/admin), `QuotaFilter` enforcing daily request/token caps, `ChatController/ChatService/LlmProxyService` proxying SSE streams, `ToolRouter`/`ToolExecutor` plus three server-side tools (`query_orders` / `get_order_detail` / `get_logistics`), transparent forwarding of unknown client-side tool calls, `ContextService` with sliding-window session storage, `GET /api/models` and `GET /api/quota` endpoints, parser negotiation through `X-Ailux-Parser`, and honest cancellation via `OkHttp Call.cancel()` on disconnect. Ships with 31 backend unit tests, `.env` + `.env.example`, and `bootRun` auto-loading the env file.
- **Runtime provider switching** (Demo App). New `ProviderMode` enum and `AiluxDemoApp.switchProvider()` allow flipping Mock ↔ BackendProxy from a TopAppBar toggle. `MainActivity` observes the `providerMode` `StateFlow` and rebuilds the ViewModel on demand. `local.properties` now carries the backend proxy defaults.

### Changed

- Context Manager hardening: `DefaultLLMContextManager` integrates `TrimAggressiveness` for an aggressive purge mode and refines the three-stage trim pipeline; `FcMessageProtector` rewrites the FC pair-protection logic; `IMessageProtector` gains a `TrimAggressiveness` parameter. New `DefaultLLMContextManagerTest` / `FcMessageProtectorTest` / etc. round out coverage.
- Backend Sample upgrades the Gradle Wrapper from 8.10.2 to 8.11.1.

### Fixed

- `ToolRouter` NPE when the incoming tool name is `null`.

---

## [0.2.1] - 2026-06-09

Theme: **Context Manager.** Surfaces the otherwise silent failure mode of "the backend trimmed your prefix" into a client-side, configurable, observable, and testable concern.

### Added

- **`LLMContextManager` contract layer.** `ITokenCounter` / `ITrimStrategy` / `IMessageProtector` are reorganized from their original `ailux-core` location into `ailux-core/context`, with crisply separated responsibilities: estimate tokens, trim against a budget, and protect "must-stay-paired" message pairs.
- **`DefaultLLMContextManager`** with a three-stage trim pipeline (identify protected pairs → sliding-window trim against the budget → restore pairs that crossed the boundary); `SlidingWindowStrategy` supports both character- and token-based budgets; `EstimatedTokenCounter` provides a protocol-agnostic approximation; `FcMessageProtector` keeps `tool_call` and the matching `tool_result` from being trimmed independently; `ModelRegistry` ships built-in context windows for popular models.
- **`AiluxClient` integration.** Configured through `AiluxConfig.context: ContextConfig(modelKey/maxInputTokens/aggressiveness)`, with per-request override via `LLMRequest.contextPolicy: ContextPolicy`. `LLMEvent.ContextTrimmed(originalCount, trimmedCount)` now exposes trimming as a first-class observable event.
- **Tests.** `DefaultLLMContextManagerTest` (237 lines), `FcMessageProtectorTest` (301 lines), `SlidingWindowStrategyTest` (208 lines), `EstimatedTokenCounterTest` (157 lines), `ModelRegistryTest` (127 lines).

### Changed

- `ContextConfig` slimmed down; `ModelConfig` gains context-window metadata.

---

## [0.2.0] - 2026-06-07

Theme: **Function Calling.** Turns the SDK into a "tool-using LLM client" and re-partitions `ailux-core`'s event and request model along clean responsibility lines for downstream extensibility.

### Added

- **End-to-end tool calling.** `ToolDefinition` / `ToolCall` land under `ailux-core/tool`; `LLMRequest` gains `tools` + `toolChoice`; `Message` adds `Tool` and `Assistant(toolCalls=)`; `LLMEvent` adds a `ToolCall` streaming event; `ToolCallAggregator` reassembles cross-chunk `tool_call` deltas into complete calls.
- **OpenAI / Anthropic dual parsers and mappers.** `OpenAIStreamResponseParser` and `AnthropicStreamResponseParser` both fully support incremental tool calls; `AnthropicRequestMapper` is added for the Messages API; `StreamResponseParser` is upgraded to carry tool events.
- **Finish reasons and observability.** A first-class `FinishReason` abstraction unifies backend-specific termination semantics (`stop` / `length` / `tool_calls` / `content_filter` / `error`); `UsageInfo` and `LLMTaskState` move to `ailux-core/state`, `response` for tighter packaging.
- **`MockProvider` upgrade.** Adds tool-calling rules and reasoning samples for offline FC demos.
- **Tests.** `ToolCallAggregatorTest`, `AnthropicRequestMapperTest`, `OpenAIStreamResponseParserTest`, `AnthropicStreamResponseParserTest`, and `MockProviderTest` ship together.

### Changed

- **Package layout (breaking).** `com.ailux.core.model.*` is decomposed into `com.ailux.core.{request, response, state, event, error}`; `ErrorCode` / `LLMError` / `LLMRequest` / `LLMResponse` / `UsageInfo` / `LLMTaskState` are relocated. `ailux-provider-backend`'s `AuthProvider` / `BackendProxyConfig` / `DirectCloudConfig` move into `auth/` / `config/`.
- README and `docs/API*.md` add FC usage docs (English + Chinese).
- Demo `ChatViewModel` rewritten to demonstrate FC integration with tool-result feedback.

---

## [0.1.0] - 2026-06-04

### Added

- New `ailux-provider-mock` module providing a zero-dependency `MockProvider`.
- `MockProvider.generate()` matches `MockRule`s by keyword and falls back gracefully when no keyword is provided.
- `MockProvider.streamGenerate()` emits a deterministic stream of events: `Reasoning* -> Token* -> Usage -> Done`.
- Demo App automatically falls back to `MockProvider` when `ailux.baseUrl` is not configured, so it runs without an API key.
- README adds MockProvider Quick Start, streaming-event examples, and custom-rule documentation.
- Unit tests cover rule matching, fallback behavior, event ordering, reasoning, and streaming token assembly.
- v0.1 demo screenshots and video assets, used by the README and the project site.
- Initial GitHub Actions CI draft, Issue templates, PR template, and contributing guide.
- Maven publishing scaffolding for `ailux-core`, `ailux-api`, `ailux-android`, `ailux-provider-backend`, and `ailux-provider-mock`.

### Changed

- Version roadmap and progress board synced with the actual delivery status of v0.1 MockProvider.
- Demo Chat UI surfaces `UsageInfo`, distinguishing server-reported usage from local estimates.

### Known gaps

- Maven Central coordinates, signing keys, and Central Repository accounts still need to be confirmed by the project owner before publishing.
- v0.1 focuses on MockProvider plus the local Demo loop; a production-ready backend reference implementation is deferred to v0.2.
