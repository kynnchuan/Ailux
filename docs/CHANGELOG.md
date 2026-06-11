English | [中文](CHANGELOG-zh.md)

# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to Semantic Versioning.

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
