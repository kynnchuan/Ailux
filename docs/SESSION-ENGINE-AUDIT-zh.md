# Session / Engine 层并发与能力诚实度审计

> 范围：`ailux-core` / `ailux-provider-local` / `ailux-runtime-litertlm` 的 Session/Engine 实现。
> 评审输入：用户给出的 5 项问题（编号 1/3/4/5/6，缺 2）。
> 评审方式：阅源码 + 对实际 LiteRT-LM 0.13.1 AAR `javap` 反编译核实能力假设。
> 结论性质：**只研究/报告，未修改任何源码**；本文给出"实事是什么、要不要改、怎么改、有哪些边界"。

> **修订（v2）**：基于与用户的二轮讨论，问题 4 从 P2 降为 P5（问题 3 修完后真 cancel 已能覆盖"延迟"维度，maxTokens 仅留作引擎级失控护栏）；问题 6 增加 `LLMRequest.model` 对 native engine 不能真路由的语义澄清和失配校验；末尾新增"附录 A：maxTokens / contextWindowSize / budget 三字段关系"。

---

## TL;DR（v2）

| # | 优先级 | 问题 | 结论 |
|---|---|------|------|
| 1 | **P1** | snapshot/close 与 streamGenerate 锁不一致 | 真 bug。采用方案 D（一把 `ReentrantLock` 保护 history 的读写，in-session turn 串行仍由 `kotlinx.coroutines.sync.Mutex` 负责），不破坏 Session 接口签名 |
| 3 | **P1** | CANCEL_PREVIOUS 只是 best-effort | Session 层问题成立；**AAR 实测有 `Conversation.cancelProcess()`**，能力下传后真正中断 native，`supportsInterruptibleCancellation` 升级为 `true` |
| 5 | **P2** | sizeInTokens 对中文低估 ~3 倍 | CJK 分段计权（纯启发式无依赖）|
| 6 | **P2** | LLMResponse 缺少 model 名 | SessionConfig 绑定 modelId（路径 A）；同时澄清 `LLMRequest.model` 对 native engine 不能真路由（一个 Engine 实例 = 一个 loaded 模型），加失配校验 |
| 4 | **P5 / 可选** | 不可中断引擎缺少 maxTokens 软上限兜底 | 问题 3 修完后，**本地的延迟保护改由真 cancel 负责，maxTokens 仅作"失控生成的引擎级硬护栏"**；`LocalRuntimeConfig` 加 `maxOutputTokens`，LiteRT-LM `EngineConfig.maxNumTokens` 下传；per-request 维持消费端 LENGTH 判定。**云端 `LLMRequest.maxTokens` 必须真生效**（成本/延迟），需核实 BackendProxy 的 RequestMapper 链路已覆盖 |

> ⚠️ 用户跳过的"问题 2"在本审计中保持空缺；如需补审请告知。

---

## 问题 1 — snapshot/close 与 streamGenerate 锁不一致 ✅ 真 bug

### 现状核实

`StatelessProviderSession.kt`：

```kotlin
private val lock = Mutex()                       // 协程 Mutex，保护写入
...
override fun streamGenerate(...) = flow {
    lock.withLock { history.addAll(...) }        // (a) 协程锁
}
override fun snapshot(): SessionSnapshot {
    val historyCopy = synchronized(history) { history.toList() }   // (b) JVM monitor
}
override fun close() {
    synchronized(history) { history.clear() }    // (c) JVM monitor
}
```

`LocalEngineSessionAdapter.kt` 同样的 pattern（line 91 `lock.withLock`, line 184 / 199 `synchronized(history)`）。

`kotlinx.coroutines.sync.Mutex` 与 `synchronized` 完全独立，互不阻塞 → 现场是真互斥失效。

注释 line 135–136 还宣称"history mutation 由 lock 串行，snapshot 可无锁读" —— **这句话是错的**，因为读用的不是同一把锁，也不是真正的"无锁"（用了 monitor）。

### 影响触发条件

- `snapshot()` 调用频率：v0.3.x 当前 `samples/chat-demo`、`ailux-api/AiluxClient` 没把 snapshot 放在热路径上；
- 一旦上层接入"每回合自动持久化"（v0.3.2 fallback / 未来 persistence skill），triggers tag↗，`ConcurrentModificationException` 必现；
- `close()` 与 streamGenerate 同时：本就异常路径，但 race 时 `history.clear()` 触发 CME 仍可能。

### 推荐方案

**方案 A（推荐）：全程 `synchronized(history)`，移除 `Mutex`**
- ✅ 不需要改 `Session` 接口签名（snapshot 仍是非 suspend）
- ✅ 改动最小，PipelinedSession/调用点零影响
- ⚠️ 需要确认：`synchronized` 块包住 `streamGenerateRaw(...).collect { ... }` 整个流程吗？**不能**，因为：
  1. `synchronized` 块内不允许 suspend；
  2. `collect` 是 suspend 函数；
  3. 持有 monitor 阻塞调度线程对协程很糟。
- 因此**方案 A 实际不可行**，会破坏现有的"in-session 串行"语义。**否决。**

**方案 B（推荐）：snapshot 改 suspend，全程 Mutex**
- ✅ 真正修复 race
- ✅ snapshot 在等待 in-flight turn 完成后再读，语义反而更"读到最新一致状态"
- ❌ 需要改 `Session` 接口：`fun snapshot()` → `suspend fun snapshot()`
- 同步影响面：
  - `Session.kt` 接口（必改）
  - `PipelinedSession.kt` line 155 forward（一行改 suspend）
  - `LocalEngineSessionAdapter` / `StatelessProviderSession`（必改）
  - `ChatViewModel`、`DiagnosticsRecorder`、各测试（调用点 `.snapshot()` 加 `runBlocking` 或 `coroutineScope`）
- 这是一个对外 API breaking change，但在 v0.3 系列尚有窗口。

**方案 C：snapshot 保持非 suspend，但用 `kotlinx.coroutines.sync.Mutex.tryLock + 同步等待`**
- 反模式，会引入死锁风险，**不推荐**。

**方案 D（折中）：保持 snapshot 非 suspend，但 history 读写改用 `java.util.concurrent.locks.ReentrantLock` 这一把锁**
- snapshot/close 用 `lock.lock(); try { history.toList()/clear() } finally { lock.unlock() }`
- streamGenerate 内部把写 history 那一小段也用同一 `ReentrantLock`（不持锁穿越 collect，只在 addAll/最后写 assistant 时持有）
- ✅ 不改接口
- ✅ 一把锁，互斥成立
- ⚠️ `streamGenerate` 的"in-session 串行 turn"语义仍由独立的 `kotlinx.coroutines.sync.Mutex` 负责（这一把不变），只是 history 数据结构改用 ReentrantLock 保护
- 推荐 **方案 D**：风险最低、改动最局部、不破坏接口。

### 必须同时做的事

- 删除/重写 `StatelessProviderSession` line 135–136 那段误导性注释；
- 加并发测试：一个协程持续 streamGenerate、另一线程高频 snapshot，断言 100 轮内不抛 CME，且 snapshot.messages 永远满足"末尾要么是 User、要么是 User+Assistant 偶数对"的不变式。

---

## 问题 3 — CANCEL_PREVIOUS 只是 best-effort ⚠️ 部分成立 + 关键能力升级

### 现状核实

`StatelessProviderSession` line 97–124 / `LocalEngineSessionAdapter` line 87–134：
- 新 turn 进来 → 设 `@Volatile aborting = true`
- 然后 `lock.withLock { ... }` 排队等旧 turn 释放锁
- 旧 turn 在 `onEach { if (aborting) return@onEach }` 自觉跳出 collect 循环

→ 确实"语义更接近延迟 ENQUEUE 而非立刻打断"。Session 层判断完全成立。

### 但用户对引擎层的判断需要更新

**用户原话**："对本地引擎（LiteRT-LM supportsInterruptibleCancellation=false）而言，native 生成根本不会因为 aborting 而停"。

**实际核实**（反编译 `litertlm-android-0.13.1.aar`）：

```
public final class com.google.ai.edge.litertlm.Conversation implements AutoCloseable {
    ...
    public final void cancelProcess();        // ← 存在！
    public final BenchmarkInfo getBenchmarkInfo();
    public final int getTokenCount();
    ...
}

public final class com.google.ai.edge.litertlm.Session implements AutoCloseable {
    ...
    public final void cancelProcess();        // ← 存在！
    ...
}
```

LiteRT-LM 0.13.1 **公开 API 实际有 `Conversation.cancelProcess()`**。当前 `LiteRTLMEngine.capabilities().supportsInterruptibleCancellation = false` 和 `LiteRTLMSession` 没有暴露 cancel 方法，是 0.12.x 时代沿用下来的过时假设。

### 推荐方案（修订版，比用户原方案更激进）

**Session 层（用户原方案，保留）**：
1. `streamGenerate` 进入 `lock.withLock` 前用 `coroutineScope { launch { ... } }` 把内层 collect 包成可记录的 `Job`；
2. 把 `Job` 存到 `@Volatile private var previousJob: Job?`；
3. CANCEL_PREVIOUS 分支 `previousJob?.cancelAndJoin()` 后再 `lock.withLock`；
4. 取消路径**必须**回滚或不写入 assistant：把 `history.add(Message.Assistant(...))` 包进 `if (!aborting && emittedTokens.isNotEmpty())`，且这里 aborting 已经不再用 `@Volatile`，而是检查"当前 collecting Job 是否被取消"。

**Engine 层（新增建议）**：
5. `LiteRTLMSession` 暴露 `fun cancelProcess() { runCatching { conversation.cancelProcess() } }`；
6. `LiteRTLMEngine.streamGenerate(request, session)` 的 collect 用 `try { ... } finally { if (!completed) session.cancelProcess() }` 把协程取消信号传给 native；
7. `LiteRTLMEngine.capabilities().supportsInterruptibleCancellation` 改为 `true`；
8. `LocalEngineSessionAdapter` 在 `aborting` 路径上调用 `engineSession.cancelProcess()`（通过 EngineSession 接口新增方法，或者下沉到 LiteRTLMSession 实现）。
9. `LocalRuntimeProvider.streamGenerate` 的 KDoc（line 152–175）需要重写："LiteRT-LM 0.13.x 实际支持 cancelProcess，能力下传后真正中断 native"。

### 边界与必须做的事

- `EngineSession` SPI 当前没有 `cancel`/`abort` 方法 → 需要加一个 `fun cancel() = Unit`（默认空实现，向后兼容）；
- 取消语义同步更新 `Session.streamGenerate` / `MessageConcurrencyPolicy.CANCEL_PREVIOUS` 的 KDoc，把"best-effort"删掉；
- llama.cpp engine 未来上线时，沿用同一 `EngineSession.cancel()` 通过 abort callback 实现；
- 测试：第一条 turn 进行中（用 `gate.await()` 卡住 mock engine）发起 CANCEL_PREVIOUS，断言：
  - 第一条 collect 在 < 100ms 内被取消；
  - `history` 中没有第一条的 `Message.Assistant`；
  - 第二条 turn 正常完成。

---

## 问题 4 — maxTokens 软上限兜底 ⚠️ P5 / 可选（v2 修订）

### 修订动机

v1 把这一项放在 P2，理由是"本地引擎不可中断 → 没有 maxTokens 等于失控生成"。
v2 修订：问题 3 修完后，本地的 **延迟保护**改由真 cancel（`Conversation.cancelProcess()`）承担——超时/换 turn 都能秒断 native；所以本项的紧迫性下降为"防御失控生成的引擎级硬护栏"，可选实现。

### 调查结论（保留 v1）

反编译 `EngineConfig`：

```
public final class com.google.ai.edge.litertlm.EngineConfig {
    private final String modelPath;
    private final Backend backend;
    ...
    private final Integer maxNumTokens;       // ← 引擎级生成上限
    private final Integer maxNumImages;
    private final String cacheDir;
    ...
}
```

`ConversationConfig` / `SessionConfig` / `SamplerConfig` 都**没有** maxTokens 字段；`Conversation.sendMessage(...)` 的 `extraContext: Map<String, Object>` 参数没有公开 schema 不能依赖。

**结论**：LiteRT-LM 0.13.1 把 maxTokens 设为**引擎级**配置（影响整个 Engine 寿命的所有 Conversation），不存在 per-request 入口。

### 修订后方案（v2）

**本地（LocalRuntimeProvider + LiteRT-LM）**：
1. **`LocalRuntimeConfig` 新增 `maxOutputTokens: Int?` 字段**（默认 null = 不限制，命名避开和 `LLMRequest.maxTokens` 混淆）；
2. `LiteRTLMEngine.load(config)` 时把 `config.maxOutputTokens` 透传给 `EngineConfig.maxNumTokens`；
3. `LLMRequest.maxTokens` 在本地不下传，仅走现有 `translateFinishReason` 的"消费端事后判 LENGTH"逻辑；
4. KDoc 如实标注：`LiteRTLMEngine.streamGenerate` —— `request.maxTokens` 无法 per-request 下传 LiteRT-LM 0.13.x，仅作消费端 LENGTH 判定；引擎级硬护栏请走 `LocalRuntimeConfig.maxOutputTokens`；
5. `EngineCapabilities` 补一个 `supportsPerRequestMaxTokens: Boolean` 能力位（默认 false，llama.cpp 上线时改 true）。

**云端（BackendProxyProvider）**：
6. **核实**当前 `RequestMapper`（OpenAI/Anthropic chat-completions 路径）已把 `LLMRequest.maxTokens` 写到请求体（OpenAI: `max_tokens` / `max_completion_tokens`；Anthropic: `max_tokens`）；如未覆盖，补全。这一段对云端是**必需**而非可选——云端 maxTokens 是成本/延迟硬约束，不是"失控护栏"。

### 边界

- 消费端的事后 LENGTH 判定保留（已经是事后判定，不浪费算力，只是"native 还在跑"无法避免，但跟问题 3 修完后的真 cancel 路径互不冲突）；
- 不要试图通过 `sendMessage` 的 `extraContext` Map 偷传 maxTokens —— 公开 API 没声明，是 UB；
- 引擎级 maxNumTokens 改了意味着要 `release()` + 重新 `load()` —— 这是 LiteRT-LM 的限制，调用方需要知道；
- 这一调查结论本身要进 `LiteRTLMEngine.kt` 的 KDoc，供后续引擎对照。

---

## 问题 5 — sizeInTokens 中文低估 ✅ 真问题

### 现状

```kotlin
override fun sizeInTokens(text: String): Int {
    if (text.isEmpty()) return 0
    return (text.length + 3) / 4              // 英文经验值
}
```

中文实际约 1.5～2 字符/token，按 4 字符/token 估会**低估 2~3 倍**。

补充发现：AAR 有 `Conversation.getTokenCount()`，但那是"当前对话已累计的 token 数"，不是 "tokenize(text)" 长度。所以**没有 native tokenizer API 替代**，启发式回退是必须保留的。

### 推荐方案（CJK 分段计权）

```kotlin
override fun sizeInTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjkChars = 0
    var otherChars = 0
    for (c in text) {
        if (isCjk(c)) cjkChars++ else otherChars++
    }
    // CJK 取 1.5 字符/token（偏保守，宁可多估一点）
    // 非 CJK 取 4 字符/token
    return ((cjkChars * 2 + 1) / 3) + ((otherChars + 3) / 4)
}

private fun isCjk(c: Char): Boolean {
    val code = c.code
    // CJK 统一表意文字 + 扩展 A + 全角标点 + 假名 + 韩文音节
    return (code in 0x4E00..0x9FFF) ||      // CJK Unified Ideographs
           (code in 0x3400..0x4DBF) ||      // CJK Ext-A
           (code in 0x3000..0x303F) ||      // CJK Symbols & Punct (含中文全角标点)
           (code in 0x3040..0x309F) ||      // Hiragana
           (code in 0x30A0..0x30FF) ||      // Katakana
           (code in 0xAC00..0xD7AF) ||      // Hangul Syllables
           (code in 0xFF00..0xFFEF)         // Halfwidth & Fullwidth Forms
}
```

### 边界

- 仍是估算，仅用于"native usage 缺失时的回退"和"上层 context-management 裁剪预算"；不追求精确；
- 加注释说明"分语言启发式，待 LiteRT-LM 提供 native tokenizer 后替换"；
- 测试：
  - 纯英文 1000 字符 → 估算 ~250；
  - 纯中文 1000 字符 → 估算 ~667（而非旧实现的 250）；
  - 中英混排各 500 → 估算 ~(125 + 333) = ~458；
  - 断言中文不再被低估到 1/3。

---

## 问题 6 — LLMResponse 缺少 model 名 ✅ 真问题

### 现状

`SessionDefaults.collectToResponse` line 60：

```kotlin
return LLMResponse(
    text = text.toString(),
    usage = usage,
    model = null,    // Sessions don't know the underlying model name...
)
```

`PipelinedSession.generate` 走的是 `pipeline.runToResponse(request, delegate::streamGenerate)`，最终也是同一聚合逻辑，model 永远为 null。

### v2 新增：`LLMRequest.model` 的语义澄清

读源码（`ailux-provider-local` / `ailux-runtime-litertlm` 全文 `grep request\.model` 结果：**零引用**）核实：

- **`LLMRequest.model` 在 LocalRuntimeProvider → LiteRTLMEngine 这条链路上根本没人读它**，传什么都不生效；
- 这是架构上的硬约束，不是疏忽：LiteRT-LM 一个 `Engine` 实例 = 一个 loaded 模型（`.litertlm` 文件 + KV cache），换模型 = `engine.close()` + `Engine.initialize()`，~10 s 冷启动；
- → `LLMRequest.model` 对 native engine **物理上**做不到 per-request 路由（手机 RAM 也撑不住同时持多个 ~2GB 模型）。

所以 `LLMResponse.model` 的"权威来源"必须是 **provider 知道、request 不知道**的东西——这正是路径 A 的依据。

### 推荐方案（路径 A）

1. **`SessionConfig` 加 `val modelId: String? = null`**（provider 内部填，对外只读）；
2. **`LLMProvider.openSession(config)` 内由各 provider 决定 modelId**：
   - `BackendProxyProvider`：用 `config.providerHint?.get("model")` 或后端约定字段；后续 `LLMResponse.model` 优先用云端 HTTP response body 里的 `model`（OpenAI/Anthropic 都会回，可能跟 request.model 不一样——后端做了路由就回降级模型），fallback 到 SessionConfig.modelId；
   - `LocalRuntimeProvider`：用 `LocalRuntimeConfig.modelSource` 派生稳定 ID：`ModelSource.LocalPath("/data/.../qwen3-1.7b-int4.litertlm")` → `"local:qwen3-1.7b-int4"`（取文件名 stem，去 ext，避免泄露完整磁盘路径）；
   - `MockProvider`：写 `"mock"`；
3. **`Session` 接口加 `val modelId: String?`** 只读属性（默认 null 兼容老实现）；
4. **`SessionDefaults.collectToResponse` 改签名**：
   ```kotlin
   suspend fun collectToResponse(
       request: LLMRequest,
       modelId: String?,
       stream: (LLMRequest) -> Flow<LLMEvent>,
   ): LLMResponse
   ```
   调用点 `Session.generate` 传入 `this.modelId`；
5. **`LocalRuntimeProvider.openSession()` 加失配校验**：
   ```kotlin
   require(request.model.isEmpty() || request.model == this.boundModelId) {
       "LocalRuntimeProvider is bound to '$boundModelId'; per-request model routing is not supported. " +
           "Got request.model='${request.model}'."
   }
   ```
   失配早暴露而非静默忽略（v2 新增）；
6. **`LLMRequest.model` 的 KDoc** 加："对 cloud provider（BackendProxy）通过 HTTP body 真路由；对 local provider（LocalRuntimeProvider）会校验，要么为空、要么必须等于当前 loaded 模型 ID，否则抛 LLMException(INVALID_REQUEST)"；
7. **`LLMResponse.model` 的 KDoc** 改："实际处理这次请求的模型标识——云端取自 HTTP response，本地取自 SessionConfig.modelId。旧 provider 未填时仍允许 null。"。

### 边界

- 本地模型命名约定**不要泄露完整磁盘路径**（隐私 + 跨设备稳定性），固定用 "local:<stem>" 形态；
- 改动应保持 `LLMResponse.model` 仍允许为 null（旧 provider 未填时）；
- 备选路径 B（EngineEvent.Usage 带 model 字段）已否决——"Usage 语义" + "model 标识"耦合不合理，且改动面更大；
- 测试：
  - 断言经由 stateful engine 的 `generate(...)` 返回的 `LLMResponse.model == "local:qwen3-..."`，非空且为约定标识；
  - 断言 `LocalRuntimeProvider.openSession()` 在 `request.model="other-model"` 时抛 LLMException(INVALID_REQUEST)；
  - 断言 `request.model=""` 时透传，使用绑定的模型。

---

## 不在本次报告范围

- 用户跳过的"问题 2"——未审计。
- `LocalEngineSessionAdapter.collapseTurnForNonBatchedEngine` 的 turn 折叠策略本身是另一个独立设计点（与本次 5 项无关）。
- 实际 patch 的编写、单元测试的实现、`./gradlew test` 验证 —— 本报告未执行任何源码修改。

---

## 建议的执行顺序（v2）

1. **问题 1（方案 D）** —— 风险最低、最纯粹的并发 bug；
2. **问题 5** —— 纯函数改动，对中文产品收益立竿见影；
3. **问题 6（路径 A + LLMRequest.model 失配校验）** —— 对 diagnostics/计费有外溢价值；
4. **问题 4（仅本地引擎级 + 云端 RequestMapper 核实）** —— 失控护栏 + 云端能力核实；
5. **问题 3** —— 改动面最大，且依赖 Engine SPI 加 `cancel()`，同时把 LiteRTLMEngine 的 `supportsInterruptibleCancellation` 升级为 true。

每项都建议**独立 commit**，便于回滚和 review。

---

## 附录 A — `LLMRequest.maxTokens` / `ContextConfig.budget` / `ModelConfig.contextWindowSize` 三字段关系

经常被混淆的三个字段，在此一次说清。

| 字段 | 单位 | 描述谁 | 何时使用 | 由谁强制执行 |
|------|------|--------|----------|--------------|
| `ModelConfig.contextWindowSize` | tokens | **模型容量上限**（"这个模型最多吃多少 token"）| 模型注册元信息，client 启动时绑定 | 没人强制——是预算计算的输入 |
| `ModelConfig.reserveForReply` | tokens | **给生成预留多少 token 的位置** | 同上 | 同上 |
| `ContextConfig.budget` | tokens | **本次 trim 的 input 上限** = `contextWindowSize - reserveForReply` | 上层 trim/裁剪消息列表时 | `LLMContextManager.process` —— 在 client 端把 messages 砍到 budget 以内 |
| `LLMRequest.maxTokens` | tokens | **本次 response 的生成上限**（"这次回复别超过 N 个 token"）| 单次请求级 | **生产端** —— 云端 backend 真停；本地 LiteRT-LM 0.13.x 无 per-request 入口，仅消费端事后判 LENGTH |

**核心区别**：

- `reserveForReply` 是"我**计划**给生成留 4096"——这是 client 单方面的预算分配，模型并不知道；
- `LLMRequest.maxTokens` 是"我**告诉**模型最多生成 4096"——通过 wire protocol 传给生产端，由生产端在解码循环里真停止。
- 只设 `reserveForReply` 不设 `maxTokens`：模型理论上可以一直输出到 `contextWindowSize - prompt_tokens` 用完为止，**远超你给它留的 reserve**。`reserveForReply` 只防 input 把空间挤爆，不防 output 超出预期。

**关系图**：

```
ModelConfig.contextWindowSize ──┐
                                 ├── ContextConfig.budget = window - reserve
ModelConfig.reserveForReply  ──┘     │
                                      ↓
                            LLMContextManager 裁 input messages（client 侧）

LLMRequest.maxTokens
    ├─ 云端：写到 HTTP body → 生产端真停 → finish_reason=length（成本/延迟硬约束）
    └─ 本地：消费端事后判 LENGTH（LiteRT-LM 0.13.x 无真停能力）
                                      ↑
                  LocalRuntimeConfig.maxOutputTokens（引擎级硬护栏，可选）
```

**Sanity 不变式**（建议上层 client 校验）：

```
LLMRequest.maxTokens ≤ ModelConfig.reserveForReply
```

否则 client 给生成留的 reserve 不够，生产端按 maxTokens 真生成出来就会超出 context window。

---



_报告作者：阿团（基于源码审阅与 LiteRT-LM 0.13.1 AAR 反编译核实）_
