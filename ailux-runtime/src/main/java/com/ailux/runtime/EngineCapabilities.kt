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
 * @property supportsModelExtensions extension tags the engine recognises
 *   on a model artefact (e.g. `"litertlm"`, `"gguf"`, `"task"`).
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
 */
data class EngineCapabilities(
    val supportAbis: Set<String>,
    val estimatedRamMb: Int,
    val gpuBackend: GpuBackend,
    val supportsTools: Boolean,
    val supportsInterruptibleCancellation: Boolean,
    val supportsModelExtensions: Set<String>,
    val maxConcurrentSessions: Int = 1,
)

enum class GpuBackend {
    NONE, VULKAN, GPU_DELEGATE
}
