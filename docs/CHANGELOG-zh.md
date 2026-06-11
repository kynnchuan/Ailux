[English](CHANGELOG.md) | 中文

# Changelog

所有重要变更都会记录在这里。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [0.2.4] - 2026-06-11

主题：**`LLMRequest` 可扩展性范式 + 多模态传输 + 请求幂等**。把 `LLMRequest` 的扩展能力从"加字段还是改 mapper"二选一，重塑为「强类型字段 / `overrides` 逃生舱 / 自定义 Mapper」三层模型；为多模态、幂等、Debug Panel 等后续能力一次性把契约层收口在 0.x 窗口完成。

### Added

- **三层扩展范式**——新增结构化逃生舱 `LLMRequest.overrides: JsonObject`，由各 mapper 在请求体顶层 merge、允许覆盖强类型字段；OpenAI / Anthropic 两个内置 mapper 共用 `applyOverrides()` 约定，使用方添加任意后端字段不再需要重写 mapper 或等待 SDK 发版。
- **多模态传输**——`Attachment(source, mimeType)` 扁平化数据模型 + sealed `AttachmentSource{Url/Base64/LocalUri}` 三态来源；`OpenAIRequestMapper` 序列化为 OpenAI content-parts，`AnthropicRequestMapper` 序列化为 Anthropic image block；新增 `ErrorCode.UNSUPPORTED_MODALITY` 兜底"BackendProxy 收到未转 Base64 的 LocalUri"场景。推理永远归后端，SDK 只负责传输。
- **`Attachment.fromLocalUri` helper**（`ailux-android`）——通过 `ContentResolver` 读取 `content://` / `file://`，自带 RFC 4648 Base64 编码与 `maxBytes` 上限（默认 20 MiB），让相机/相册图片在 `BackendProxyProvider` 上一行接入。
- **跨协议核心字段** `LLMRequest.stop: List<String>`——映射 OpenAI `stop` 与 Anthropic `stop_sequences`，作为强类型字段纳入"高频 ∧ 跨协议一致"标准的典范。
- **请求幂等**——`BackendProxyProvider` 注入 `Idempotency-Key` HTTP header（默认值取 `LLMRequest.requestId`，重试时复用同 id），header 名称由新配置 `BackendProxyConfig.idempotencyHeaderName` 控制，传 `null` 关闭。
- **Demo Debug Panel**——Compose ModalBottomSheet 形态的运行时配置面板（`samples/chat-demo/.../debug/`），承接 v0.2.2 §14.4.5：覆盖 provider/model/context_mode/account/session/stop/overrides/attachments + concurrency policy + stall detection + i18n（CN/EN），无需重新编译即可现场切换全部 v0.2.2~v0.2.4 能力。
- **测试**——`OpenAIRequestMapperTest`（22 项）、`AnthropicRequestMapperTest` 扩展（+7 项 stop / 多模态 / overrides）、`AttachmentExtTest` 覆盖 Base64 编码与读取上限。

### Changed

- **重命名（破坏性）**：`LLMRequest.extras: Map<String,String>` → `overrides: JsonObject`；`DefaultRequestMapper` → `OpenAIRequestMapper`（协议语义更清晰）；`ContextOverride` → `ContextPolicy`（与新增的 `overrides` 字段消歧义）。
- `ailux-core` 对 `kotlinx-serialization-json` 的依赖由 `implementation` 提升为 `api`，因为 `JsonObject` 进入了公共 API 表面。
- 取消语义文档化：`Ailux.cancel` / `LLMTask.cancel` / `BackendProxyProvider` 的 KDoc 明确"cancel 断开客户端连接，但不保证上游模型停止生成与计费"——SDK 不强加任何"中止信号协议"以保持"任意后端可接入"的红线。

### Known gaps

- README 三层扩展模型指南与 `extras → overrides` 迁移指南、`BackendProxyProvider` 层幂等 header 单测、`ailux-compose` 子项目 roadmap 落地仍在收口中（spec §11.2）。

---

## [0.2.3] - 2026-06-10

主题：**并发协调、停滞检测、Per-Request Task Handle**。把"`AiluxClient.cancel()` 一刀切"的简单时代翻篇，引入按请求维度的 `LLMTask` 句柄；同时给"长流式无声卡死"装上心跳。

### Added

- **`LLMTask` 接口**——单次请求的句柄，暴露 `id` / `events: SharedFlow<LLMEvent>` / `state` / `cancel()`，`AiluxClient.streamGenerate()` 现返回 `LLMTask` 而非 `Flow<LLMEvent>`。
- **并发策略**——`ConcurrencyPolicy{ PARALLEL / CANCEL_PREVIOUS / ENQUEUE / REJECT }` 与 `ConcurrencyCoordinator`（`synchronized` + `Mutex` 实现 ENQUEUE 的 FIFO 队列），通过 `AiluxConfig.concurrencyPolicy` 选取；`LLMTaskState` 新增 `Queued` 状态。
- **停滞检测**——`StallDetection` Flow 算子 + `StallState` 枚举 + `StallPhase`，可独立挂在任意 `Flow<LLMEvent>` 上；新增非终结事件 `LLMEvent.StallDetected`、`LLMEvent.Connected` 用于上层 UI 区分"还没收到首 token"和"中途卡住"。配置走 `AiluxConfig.streamConfig.stallDetection`。
- **三档消费 API**（`LLMTask` 扩展）——Level 1 `tokenFlow()` 直接拿文本流；Level 2 `handle { onToken / onReasoning / onToolCall / onDone / onError }` DSL；Level 3 原生 `task.events.collect {}` 全控制。
- **Android 适配**——`AiluxViewModel` / `AiluxClientDelegate` 改为基于 `currentTask` 的 `flatMapLatest`，自动跟随最新一次请求；`LifecycleExt.collectState` 由 `AiluxClient` 扩展改为 `LLMTask` 扩展。

### Changed

- **破坏性**：`AiluxClient.streamGenerate()` 返回类型由 `Flow<LLMEvent>` 改为 `LLMTask`（迁移：`client.streamGenerate(req).collect { }` → `client.streamGenerate(req).events.collect { }` 或改用 `handle {}` DSL）。
- `LLMException` 从 `ailux-provider-backend` 上提到 `ailux-core/error`，全部 provider 共用同一异常类型。
- `LLMEvent` 升级为 sealed class 并加新分支 `Connected` / `StallDetected`；穷尽 `when` 需补分支。
- 仓库结构调整：`chat-demo` 与 `ailux-backend-sample` 移入 `samples/`，demo 包名规范为 `com.ailux.chatdemo`。

---

## [0.2.2] - 2026-06-09

主题：**Backend 样板真实落地 + 运行时 Provider 切换**。把"对接生产后端"从"自己看 KDoc 拼"升级为"开箱跑通的 Spring Boot 样板"，并让 Demo 一键在 Mock 与 BackendProxy 之间切换。

### Added

- **`samples/ailux-backend-sample`**——Spring Boot 3.3 + Java 17 + H2 + JPA + OkHttp 的端到端样板：Token 鉴权（3 个预设账号 free/pro/admin）+ `QuotaFilter` 日级请求/Token 限额 + `ChatController/ChatService/LlmProxyService` SSE 流式转发 + `ToolRouter`/`ToolExecutor` + 3 个服务端工具（`query_orders` / `get_order_detail` / `get_logistics`）+ 透传客户端未知 `tool_call` + `ContextService` 滑窗会话存储 + `GET /api/models` 与 `GET /api/quota` + `X-Ailux-Parser` 协商 header + 连接取消时 `OkHttp Call.cancel()` 真停后端。31 项后端单测、`.env` + `.env.example` 配置、`bootRun` 自动加载 env。
- **运行时 Provider 切换**（Demo App）——`ProviderMode` 枚举 + `AiluxDemoApp.switchProvider()`，TopAppBar 一键切 Mock ↔ BackendProxy；`MainActivity` 监听 `providerMode` `StateFlow` 并按需重建 ViewModel。`local.properties` 提供后端代理默认值。

### Changed

- `ContextManager` 增强：`DefaultLLMContextManager` 接入 `TrimAggressiveness` 支持激进清理模式，三阶段裁剪 pipeline 文档完善；`FcMessageProtector` 重构 FC 消息成对保护逻辑；`IMessageProtector` 接口加 `TrimAggressiveness` 参数。新增 `DefaultLLMContextManagerTest` / `FcMessageProtectorTest` 等大批单测。
- Backend Sample Gradle Wrapper 升级 8.10.2 → 8.11.1。

### Fixed

- `ToolRouter` 在 tool name 为 `null` 时的 NPE。

---

## [0.2.1] - 2026-06-09

主题：**Context Manager**。把"长对话被服务端裁掉前缀"这个隐性失败模式，移到客户端可感知、可配置、可测试的明面上。

### Added

- **`LLMContextManager` 契约层**——`ITokenCounter` / `ITrimStrategy` / `IMessageProtector` 三件套从 `ailux-core` 旧位置整理到 `ailux-core/context`，明确分工：估算 token、按预算裁剪、保护"不能被裁的成对消息"。
- **`DefaultLLMContextManager`** + 三阶段裁剪 pipeline（保护对识别 → 滑窗预算裁剪 → 越界保护回填）；`SlidingWindowStrategy` 实现按字符/Token 双策略的滑窗；`EstimatedTokenCounter` 提供与协议无关的近似计数；`FcMessageProtector` 保护 `tool_call` 与对应 `tool_result` 不被独立裁剪；`ModelRegistry` 维护内置常用模型上下文窗口。
- **`AiluxClient` 接入**——配置走 `AiluxConfig.context: ContextConfig(modelKey/maxInputTokens/aggressiveness)`，单次请求可经 `LLMRequest.contextPolicy: ContextPolicy` 覆盖；`LLMEvent` 新增 `ContextTrimmed(originalCount, trimmedCount)` 让 UI 透明感知裁剪行为。
- **测试**——`DefaultLLMContextManagerTest` 237 行、`FcMessageProtectorTest` 301 行、`SlidingWindowStrategyTest` 208 行、`EstimatedTokenCounterTest` 157 行、`ModelRegistryTest` 127 行。

### Changed

- `ContextConfig` 字段精简化；`ModelConfig` 增加上下文窗口元信息。

---

## [0.2.0] - 2026-06-07

主题：**Function Calling**。让 SDK 真正成为"会用工具的 LLM 客户端"，并把 `ailux-core` 的事件/请求模型按职责拆分以便后续扩展。

### Added

- **Tool 调用全链路**——`ToolDefinition` / `ToolCall` 在 `ailux-core/tool` 落地；`LLMRequest` 接入 `tools` + `toolChoice`；`Message` 增加 `Tool` 与 `Assistant(toolCalls=)` 表达；`LLMEvent` 新增 `ToolCall` 流式事件；`ToolCallAggregator` 把跨 chunk 的 `tool_call` 增量合并为完整调用。
- **OpenAI / Anthropic 双 Parser 与 Mapper**——`OpenAIStreamResponseParser` 与 `AnthropicStreamResponseParser` 完整支持工具调用增量；新增 `AnthropicRequestMapper` 走 Messages API；`StreamResponseParser` 协议升级承载工具事件。
- **完成原因与可观测性**——`FinishReason` 抽象统一不同后端的结束语义（`stop` / `length` / `tool_calls` / `content_filter` / `error`）；`UsageInfo` 与 `LLMTaskState` 移到 `ailux-core/state`、`response`，按职责分包。
- **`MockProvider` 升级**——支持工具调用规则与 reasoning 流式样例，便于离线演示 FC。
- **测试**——`ToolCallAggregatorTest`、`AnthropicRequestMapperTest`、`OpenAIStreamResponseParserTest`、`AnthropicStreamResponseParserTest`、`MockProviderTest` 一并补齐。

### Changed

- **包结构重构（破坏性）**——`com.ailux.core.model.*` 拆分为 `com.ailux.core.{request, response, state, event, error}`；`ErrorCode` / `LLMError` / `LLMRequest` / `LLMResponse` / `UsageInfo` / `LLMTaskState` 移动到对应子包。`ailux-provider-backend` 的 `AuthProvider` / `BackendProxyConfig` / `DirectCloudConfig` 也分别归位 `auth/` / `config/`。
- README、`docs/API*.md` 增补 FC 用法（中英）。
- Demo `ChatViewModel` 重写演示 FC 整合 + 工具结果回填。

---

## [0.1.0] - 2026-06-04

### Added

- 新增 `ailux-provider-mock` 模块，提供零依赖 `MockProvider`。
- `MockProvider.generate()` 支持按关键词命中 `MockRule`，并提供空 keyword fallback。
- `MockProvider.streamGenerate()` 支持确定性的流式事件：`Reasoning* -> Token* -> Usage -> Done`。
- Demo App 在未配置 `ailux.baseUrl` 时自动回退到 `MockProvider`，无需 API Key 即可运行。
- README 增加 MockProvider Quick Start、流式事件示例和自定义规则说明。
- 新增单元测试覆盖规则命中、fallback、事件顺序、reasoning 与流式拼接。
- 新增 v0.1 Demo 截图与视频资源，供 README 和站点展示。
- 新增 GitHub Actions CI 草案、Issue 模板、PR 模板与贡献指南。
- 新增 Maven 发布配置草案，覆盖 `ailux-core`、`ailux-api`、`ailux-android`、`ailux-provider-backend`、`ailux-provider-mock`。

### Changed

- 版本路线图与进度看板同步 v0.1 MockProvider 的真实完成状态。
- Demo Chat UI 展示 `UsageInfo`，区分服务端用量与本地估算用量。

### Known gaps

- Maven Central 坐标、签名密钥、中央仓库账号仍需发布前由项目所有者确认。
- v0.1 当前以 MockProvider 和本地 Demo 闭环为主，真实生产级后端样板继续放入 v0.2。
