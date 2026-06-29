package com.ailux.runtime

/**
 * Runtime-discoverable capabilities of an [InferenceEngine] implementation.
 *
 * Capabilities reflect **physical / engine-side facts**, not user intent.
 * The Provider/Client layer combines these facts with the user-supplied
 * `SessionConcurrencyPolicy` / `MessageConcurrencyPolicy` to decide actual
 * runtime behaviour (with soft-degradation + warning when policy exceeds
 * capability — never throwing on policy/capability mismatch).
 *
 * @property supportAbis ABIs the engine's native code is compiled for.
 *   The Provider's [DeviceProbe] step refuses to load if the device's
 *   primary ABI is not in this set.
 *
 * @property estimatedRamMb engine's own estimate of resident memory required
 *   to load the configured model. Consumed by the device pre-check stage.
 *
 * @property gpuBackend GPU acceleration backend in use (or [GpuBackend.NONE]
 *   for CPU-only).
 *
 * @property supportsTools whether the engine can perform native function /
 *   tool calling (i.e. parse tool-call tokens directly, not via prompt
 *   templating in user code).
 *
 * @property supportsInterruptibleCancellation whether cancelling the
 *   collecting coroutine on a [InferenceEngine.streamGenerate] flow truly
 *   stops native work mid-token. When `false`, native generation continues
 *   to natural completion even after the consumer cancels — only
 *   [InferenceEngine.release] truly halts it. See spec §6.1.5.
 *
 * @property supportedModelExtensions extension tags the engine recognises
 *   on a model artefact (e.g. `"litertlm"`, `"gguf"`, `"task"`).
 *   **Declaration only — never used to pre-flight / reject a load** (spec
 *   §1.5 / Q11). A wrong format still surfaces via `engine.load()` failure →
 *   `MODEL_LOAD_FAILED`. demo UIs may read this to hint model pickers.
 *
 * @property maxConcurrentSessions hard upper bound on the number of
 *   [EngineSession]s that may execute concurrently on the same engine
 *   instance. **Since v0.3.0.**
 *
 *   - `1` — engine serialises all session work internally (e.g. a Wasm-bound
 *     LiteRT-LM build, or any GPU executor with a single inference pipeline
 *     under heavy memory pressure).
 *   - `n > 1` — up to `n` sessions may run in parallel.
 *   - [Int.MAX_VALUE] — no engine-side limit (typical for cloud transports
 *     and for engines whose only constraint is host RAM).
 *
 *   This value caps the user-facing `SessionConcurrencyPolicy.PARALLEL`
 *   intent: when the requested policy exceeds capability, the
 *   Provider/Client layer downgrades to ENQUEUE behaviour and emits a
 *   one-time warning. Engines MUST NOT lie — reporting a higher value than
 *   the engine can safely sustain risks GPU race conditions, KV-cache
 *   corruption, or OOM. When in doubt, report `1` and revisit after
 *   real-device profiling.
 *
 *   For engines where [supportsSessions] is `false`, this value is
 *   meaningless but should be set to `1` for clarity.
 *
 * @property supportsBatchedIngest whether the engine can ingest **multiple
 *   pre-existing turn messages into a session's context WITHOUT triggering a
 *   sampling pass** (i.e. has a true "prefill-only" / "batched-ingest" API).
 *
 *   Background: when a single turn contains more than one message (typical
 *   case: one [com.ailux.core.message.Message.User] followed by N
 *   [com.ailux.core.message.Message.Tool] replies from a parallel tool call),
 *   the Session adapter needs to feed all N+1 messages into the engine and
 *   then have the model generate exactly **one** answer that synthesises all
 *   tool results. The desirable wire shape is:
 *
 *   - **prefill** messages 1..N-1 into the KV cache (no sampling);
 *   - **prefill + sample** the final message so the streamed reply integrates
 *     everything above it.
 *
 *   Some engines expose this naturally (e.g. llama.cpp via `llama_decode` with
 *   `n_predict = 0`), and they should report `supportsBatchedIngest = true`.
 *
 *   LiteRT-LM 0.13.x does **not** expose such an API: both `sendMessage`
 *   (sync) and `sendMessageAsync` (streaming) trigger a full generation
 *   pass — the difference is only sync vs. streaming return. Calling
 *   `sendMessage` n-1 times to "just feed context" would (a) waste n-1
 *   inference passes, (b) write n-1 unwanted assistant replies into the
 *   native KV cache and pollute the final context, and (c) silently drop
 *   those middle replies' tokens. Engines in this category MUST report
 *   `supportsBatchedIngest = false`.
 *
 *   The Provider layer reads this flag and, when `false`, takes a degraded
 *   path that merges the non-final turn messages into the final message
 *   (see `LocalEngineSessionAdapter`) so the engine only sees one message
 *   and only generates once. The merge keeps source role markers so
 *   diagnostics can still distinguish user/tool segments. This is a
 *   workaround pending an upstream prefill-only API; once available, both
 *   the flag and the merge path should be removed.
 *
 *   Defaults to `false` — the safe assumption for engines that have not
 *   explicitly opted in.
 */
data class EngineCapabilities(
    val supportAbis: Set<String>,
    val estimatedRamMb: Int,
    val gpuBackend: GpuBackend,
    val supportsTools: Boolean,
    val supportsInterruptibleCancellation: Boolean,
    val supportedModelExtensions: Set<String>,
    val maxConcurrentSessions: Int = 1,
    val supportsBatchedIngest: Boolean = false,
)

enum class GpuBackend {
    NONE, VULKAN, GPU_DELEGATE
}
