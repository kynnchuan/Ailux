[English](CHANGELOG.md) | 中文

# Changelog

所有重要变更都会记录在这里。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [Unreleased] · 0.2.6 进行中

主题：**`BackendProxyProvider` 生产化加固**。v0.2.0~v0.2.5 把 SDK 在架构层面拉到了相当成熟的水位；v0.2.6 收口传输层真实的生产缺口——非流式解析对称化、退避重试、`OkHttpClient` 定制注入、流式 usage 显式申请、取消/计费边界文档化、配置三分法重构、鉴权失效闭环。方案：[`v0.2.6-backendproxy-hardening`](../ailux-docs/specs/v0.2/v0.2.6-backendproxy-hardening.md)。

> 状态：**8 个阶段全部落地**（H-1 / H-2 / H-3 / H-4 / H-6 / H-7 / H-X / 配置三分法重构）。

### Added

- **鉴权失效闭环（H-7）**——HTTP 401 的完整刷新-重放路径，按**机制而非策略**设计（刷新策略仍由业务决定）：
  - `AuthProvider.onUnauthorized(): Boolean` —— SAM 接口上的默认方法（零破坏：旧 lambda 实现继续可编译）。刷新成功返回 `true`，Provider 会**每任务最多重放一次**，复用同一条 `RetryPolicy` 通道（不另起平行重试管线）。
  - `ErrorCode.AUTH_EXPIRED`（可重试）—— 当 `onUnauthorized()` 返回 `true` 但重放仍失败时浮出，让 UI 区分「悄默尝试过登录」与「凭证彻底无效」。
  - **重放预算每任务 1 次，独立于 `RetryPolicy.maxRetries`**——即使 `RetryPolicy.NONE` 也生效。流式与非流式共用同一套逻辑；恢复成功的 401 不会向 collector 漏出任何 `LLMEvent.Error`。
  - **`RequestSigner` + `SignableRequest`**（`provider-backend/auth`）—— 为需要请求级完整性签名的后端（body HMAC、防重放时间戳、AWS SigV4 风格）单独提供 hook。运行在 auth / 自定义 header / `Idempotency-Key` 之**后**，签名可故意覆盖以上任意一项。和 `AuthProvider` 正交分离，因为 `getAuthToken()` 看不到请求体。
  - **`CachingAuthProvider` 示例**——`Mutex` 单飞刷新：16 个并发 401 只触发一次 IdP 调用。
  - **新增 18 个测试**（5 示例 + 13 经 MockWebServer 端到端），覆盖 happy path、刷新后二次 401、刷新拒绝、刷新抛异常、未配置 AuthProvider、`RetryPolicy.NONE` 互操作、重放预算上界、签名 header 覆盖、签名空 map、以及对应的流式分支。**Backend 模块 153 项测试全部通过。**


- **`NonStreamResponseParser` 扩展点**（`provider-backend`）——新增 `fun interface NonStreamResponseParser`，内置 `OpenAINonStreamResponseParser` 对齐 `choices[0].message.content`，`AnthropicNonStreamResponseParser` 对齐最新 Messages API。与流式扩展点对称，并移除原先自造的 `{"text": ...}` 信封（**破坏性**，见 Changed）。
- **`RetryPolicy`**（`provider-backend/config`）——指数退避 + 去相关 jitter + 尊重 `Retry-After`，流式/非流式统一。**移除** `BackendProxyConfig.retryCount: Int`，**新增** `BackendProxyConfig.retryPolicy: RetryPolicy`；只以 `ErrorCode.retriable` 作为输入；新增 `ErrorCode.SERVER_ERROR`（可重试），HTTP 5xx 现在会真正走重试。
- **`HttpClientConfig`**（`provider-backend/config`）——真实传输栈的注入点：
  - `baseHttpClient: OkHttpClient?` —— 从已调好的 client 派生（证书锁定 / 代理 / DNS / 拦截器 / **跨实例连接池复用** / mTLS via `sslSocketFactory` + `X509TrustManager`）；
  - `customizer: (OkHttpClient.Builder) -> Unit` —— 收尾定制，运行在 `baseHttpClient.newBuilder()` 之后；
  - `connectTimeoutMs` / `readTimeoutMs` / `writeTimeoutMs` 从 `BackendProxyConfig` 迁入。
- **`ProtocolConfig`**（`provider-backend/config`）——把协议层扩展点集中归位：`requestMapper`、`streamResponseParser`、`nonStreamResponseParser`、`errorMapper`，外加 **`includeUsageInStream: Boolean`（默认 true）**——它会把 `stream_options.include_usage` 注入到 OpenAI 出站请求体，SDK 这才会真正收到末帧的 `choices:[] + usage`。
- **取消语义与计费边界**，诚实文档化：
  - `LLMTask.cancel()` / `AiluxClient.cancelAll()` / `BackendProxyProvider.streamGenerate()` / `generate()` 的 KDoc 明确写明：cancel 断开客户端连接，**但不保证上游模型停止生成与计费**；
  - `samples/ailux-backend-sample` 通过 `OkHttp Call.cancel()` 演示「断连即 abort」模式，并在 SSE 响应里加 `X-Accel-Buffering: no` 头打掉反代缓冲；
  - 新增 `CancellationSemanticsTest`（5 项），覆盖流式/非流式取消、退避中取消、取消后无残余事件。
- **测试**——`NonStreamResponseParser`：OpenAI（~260 行）+ Anthropic（~183 行）；`DefaultErrorMapperTest`（14 项）补齐 4xx/5xx 映射矩阵；`OpenAIRequestMapperTest` 扩展 `stream_options.include_usage`。**重构后 136 项测试全部通过**。

### Changed

- **破坏性**——`BackendProxyProvider` 构造方法改为三个配置：`BackendProxyProvider(config: BackendProxyConfig, httpConfig: HttpClientConfig = HttpClientConfig(), protocolConfig: ProtocolConfig = ProtocolConfig())`。`BackendProxyConfig` 保留"连接谁"（baseUrl、endpoints、auth、retry、headers），`HttpClientConfig` 拿走传输栈，`ProtocolConfig` 拿走 mapper/parser/errorMapper。默认值保持旧行为，但所有显式传 timeout 或自定义 parser 的调用点都需迁到新分组。
- **破坏性**——`BackendProxyConfig.retryCount: Int` **移除**，由 `BackendProxyConfig.retryPolicy: RetryPolicy` 替代（默认不重试，按需 `RetryPolicy(maxAttempts = 3)` 或细粒度构造）。
- **破坏性**——非流式响应现在走 `NonStreamResponseParser`。原先那套自造的 `{"text": ...}` 信封已经彻底移除；调用方需让后端遵循 OpenAI / Anthropic 非流式约定（或自实现 `NonStreamResponseParser`）。该旧格式实际无任何后端在用，影响面接近零。
- `provider-backend/parser/` 重组为 `parser/stream/` 与 `parser/nonstream/`，import 需同步更新。
- `DirectCloudConfig` 返回 `Pair<BackendProxyConfig, HttpClientConfig>` 以适配三分法。

### Fixed

- `OpenAINonStreamResponseParser` 原先读取了错误的 usage 字段名（`input_tokens` / `output_tokens` 而非 `prompt_tokens` / `completion_tokens`），导致非流式 usage 一直读到 0/0。
- `DefaultErrorMapper` 现在正确把 HTTP 5xx 映射为 `ErrorCode.SERVER_ERROR`（可重试）——原先错归到不可重试桶，导致真实服务端错误从不重试。
- Demo `samples/chat-demo` 不再泄漏先前那个永远生效不了的 `includeUsageInStream` 配置字段——现在通过 `ProtocolConfig` 真正传入默认的 `OpenAIRequestMapper`。

### Out of scope（本版有意不做）

- **#5 弱网"恢复"（部分结果续写 / 断点续传 / 自动降级）**：主动否决——恢复是策略，不是传输层机制。SDK 的义务是**不吞信息**（errorCode / `StallDetected` / `requestId` / `Idempotency-Key`），而不是把恢复协议化。详见 [ADR-0006](../ailux-docs/decisions/adr/0006-fallback-predicate-and-best-effort.md) 与 v0.2.6 spec §1.3 Q1。
- **SSE 乱序/丢失重排（P1-2）**、**HTTP 413 / Context 溢出前置校验（P1-1/P1-7）**、**`TtftTimeoutPolicy`（P1-6）**、**`DiagnosticReport v2` timeline/waterfall（P1-8）**：延后至 v0.4（重定性后的裁决见 [ADR-0008](../ailux-docs/decisions/adr/0008-v04-redefine-governance.md)）。

---

## [0.2.5] - 2026-06-11

主题：**生产接入完善（扩展性指南 + 隐私诊断奠基）**。把原 v0.2.5（扩展点指南）与原 v0.2.6（隐私 + 诊断）合并发版：线 1 —— 四个扩展点决策树 + 编译可检的活示例；线 2 —— `AiluxLogger` SPI + Secure-by-default `PrivacyConfig` + 默认脱敏的 `DiagnosticReport`。方案：[`v0.2.5-extensibility-and-privacy`](../ailux-docs/specs/v0.2/v0.2.5-extensibility-and-privacy.md)。

### Added

- **扩展点决策树**（`docs/EXTENSIBILITY-zh.md` 第二部分）——`BackendProxyConfig` 四个 `fun interface` 扩展点（`RequestMapper` / `StreamResponseParser` / `ErrorMapper` / `AuthProvider`）的并列决策树，重心是 *when*——什么时候才该写自定义实现——而不是 *how*。配套一站式装配示例与反模式表。
- **4 个活示例**，作为单测置于 `ailux-provider-backend/src/test/.../examples/`，编译检查，永不腐烂：
  - `AcmeRequestMapperExampleTest`——虚构企业内部协议（`messages → chat_history`、`role → speaker`、系统消息 hoist 成顶层 `directives`），末尾仍调 `applyOverrides()` 让逃生舱可叠加；
  - `AcmeStreamResponseParserExampleTest`——自定义 SSE `event: delta/metrics/finish`，终结 reason 翻译，未知 event/reason 容错；
  - `BizCodeErrorMapperExampleTest`——企业网关 HTTP 200 + body 业务码信封，含 IOException/Timeout/畸形 JSON 兜底，自动落到 `retriable=true`；
  - `OAuthClientCredentialsAuthProviderExampleTest`——OAuth2 client_credentials + `Mutex` 单飞刷新（16 协程并发只一次网络往返）。
- **`AiluxLogger` SPI**（`ailux-core/logging`）——`fun interface`，带 `v/d/i/w/e` 默认方法 + `LogLevel` + `NoopAiluxLogger`。宿主代码直接 `logger.d(...)` 调。`ailux-android/logging/AndroidAiluxLogger` 桥接 `android.util.Log`，含 23 字符 tag 截断与按级别过滤——但 `ailux-core` 自身**零 Android 依赖**。
- **`RedactingLogSink`**（`ailux-core/logging/internal`）——SDK 内部中介层，在每一处日志点强制执行 `PrivacyConfig`，业务代码不直接接触 raw logger。
- **`PrivacyConfig`**（`ailux-core/privacy`）——Secure-by-default 的 data class，五个开关（`logPrompt` / `logResponse` / `logOverrides` / `logHeaders` / `logRequestBody`）+ `redactionMask`（默认 `***`）+ `maxLoggedBodyLength`（默认 2048）。
  - `PrivacyConfig.SECURE_DEFAULT`（生产）与 `PrivacyConfig.DEBUG_VERBOSE`（短调试）两个工厂值；
  - 即使 `logHeaders = true`，`RedactingLogSink` 也会**永久脱敏** `Authorization` / `Proxy-Authorization` / `Cookie` / `Set-Cookie` / `X-Api-Key`（大小写无关）；
  - `logRequestBody` / `logResponseBody` 超过 `maxLoggedBodyLength` 时截断并标注截断标记；
  - 通过 `AiluxConfig.Builder.setLogger(...)` / `setPrivacyConfig(...)` 装配——默认值保持安全（`NoopAiluxLogger` + `SECURE_DEFAULT`）。
- **`DiagnosticReport`**（`ailux-core/diagnostics`）——新增 `DiagnosticReport`、sealed `Outcome`、`TimingMetrics`、`RetryAttempt`、`PrivacyConfigSnapshot`。
  - `toShareableText()` 纯 ASCII；`toJson()` 手写，**不引入 `kotlinx.serialization` 依赖**；
  - **字段级契约**：报告**永远不含** prompt / response / `overrides` / headers / request body，与 `PrivacyConfig` 无关——构造上即可粘到公开 Issue tracker；
  - `DiagnosticsRecorder` 是基于 `AtomicReference` 的状态机，CAS 终结报告；终结后所有写入方法都是 no-op，`lastReport()` 可重复读；
  - `AiluxClient` 在 `streamGenerate` 全链路埋点（`onStart`、`onFirstContent`——锚定首个 `Token | Reasoning | ToolCallDelta | ToolCallReceived`、`onSuccess | onFailure | onCancel | onRetry`、终结归档）。16 槽位环形缓冲保存最近任务，`Ailux.createDiagnosticReport(includeRecentTasks = 5)` 可一次性查询；
  - `LLMTask.lastDiagnostic()` 默认实现返回 `null`；`DefaultLLMTask` 委托给自己的 recorder。
- **日志策略文档**（`docs/LOGGING.md` / `LOGGING-zh.md`）——默认记什么、永久脱敏什么、`PrivacyConfig` 各开关如何叠加。
- **诊断文档**（`docs/DIAGNOSTICS.md` / `DIAGNOSTICS-zh.md`）——`DiagnosticReport` 端到端走读：字段、脱敏契约、share-text 格式、JSON schema。
- **GitHub Issue 模板**（`.github/ISSUE_TEMPLATE/bug_report.yml` + `bug_report_zh.yml`）——直接引用 `Ailux.createDiagnosticReport()?.toShareableText()`，并明确写出"该报告 by-contract 脱敏安全"。
- Demo `samples/chat-demo` 已接入 `AiluxLogger` + `PrivacyConfig` + 诊断：Debug Panel 可在运行时把脱敏报告复制到剪贴板。

### Changed

- `ailux-core` 公共 API 表面增加 logging SPI 与 privacy/diagnostics 包，**未引入任何 Android 传递依赖**。
- `AiluxConfig.Builder` 增加 `setLogger(AiluxLogger)` 与 `setPrivacyConfig(PrivacyConfig)`，默认值仍为 `NoopAiluxLogger` + `PrivacyConfig.SECURE_DEFAULT`，现有 app 行为零变化。

### Out of scope（本版有意不做）

- `B1-5` 配置化 endpoint —— 早在更早版本就以 `BackendProxyConfig.streamEndpoint / generateEndpoint / headers` 形式落地，本版仅做文档化。
- 诊断报告自动上传到远端、磁盘持久化、`DiagnosticReport v2`（timeline + waterfall）—— 这些是策略决策，按 [ADR-0008](../ailux-docs/decisions/adr/0008-v04-redefine-governance.md) 延后至 v1.0 再评估。

---

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
