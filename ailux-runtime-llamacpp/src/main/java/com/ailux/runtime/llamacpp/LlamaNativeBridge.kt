package com.ailux.runtime.llamacpp

/**
 * Thin JNI seam between [LlamaCppEngine] (Kotlin policy) and the llama.cpp
 * native layer (`libailux_llama.so`).
 *
 * ## Why a separate seam instead of `external fun`s on the engine
 *
 * Two reasons:
 *
 * 1. **Testability.** All `external` declarations live here behind the
 *    [LlamaBridge] interface. Unit tests inject a pure-JVM fake so the engine's
 *    event-mapping / capability / parameter-translation logic can be tested on
 *    a normal JVM runner *without* loading any `.so` (mirrors how
 *    `:ailux-runtime-litertlm` extracted `CharClassTokenEstimator` to dodge the
 *    JNI class-load problem on JDK-17 test runners).
 *
 * 2. **Honest native contract.** The interface is the exact, minimal surface the
 *    C++ side must implement — see `src/main/cpp/llama_jni.cpp`. It is shaped to
 *    expose llama.cpp's *native* signals (`stopped_eos/limit/word`, abort
 *    callback, `n_p_eval`/`n_eval` usage) rather than a pre-digested
 *    `LLMEvent`-like blob, so the engine stays the place that maps native →
 *    [com.ailux.runtime.EngineEvent] (spec §四 / §六).
 *
 * The default production implementation is [JniLlamaBridge].
 */
internal interface LlamaBridge {

    /**
     * Load a GGUF model and build a context.
     *
     * @param modelPath   absolute path to the `.gguf` file.
     * @param nCtx        context length (llama.cpp `n_ctx`); `<= 0` lets llama.cpp
     *                    pick the model's training default.
     * @param nGpuLayers  number of transformer layers to offload to GPU
     *                    (Vulkan); `0` = pure CPU.
     * @param nThreads    CPU threads for the eval; `<= 0` lets llama.cpp pick.
     * @param useVulkan   request the Vulkan backend (only honoured if the `.so`
     *                    was built with `GGML_VULKAN=ON` and a loader exists).
     * @return an opaque native handle (`> 0`) owning the model + context, or
     *         throws on failure (mapped by the engine to `MODEL_LOAD_FAILED`).
     */
    fun loadModel(
        modelPath: String,
        nCtx: Int,
        nGpuLayers: Int,
        nThreads: Int,
        useVulkan: Boolean,
    ): Long

    /** Whether the loaded context's backend is actually Vulkan (vs. CPU fallback). */
    fun isVulkanActive(handle: Long): Boolean

    /** Number of tokens [text] encodes to, via `llama_tokenize`. */
    fun tokenCount(handle: Long, text: String): Int

    /**
     * Run a full generation pass for [prompt].
     *
     * The native side drives a decode loop, invoking [sink].onToken for each
     * produced piece. Between tokens it consults [sink].isAborted; when that
     * returns `true` it stops the loop *mid-token* (this is the interruptible
     * cancel that llama.cpp supports and LiteRT-LM does not).
     *
     * On loop exit it calls [sink].onStop with the native stop reason and the
     * native usage counters, then returns.
     *
     * This call blocks the calling thread for the duration of generation; the
     * engine runs it on its dedicated single-thread dispatcher.
     */
    fun generate(
        handle: Long,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        stopWords: Array<String>,
        sink: TokenSink,
    )

    /** Release the model + context. Must be idempotent for a given handle. */
    fun release(handle: Long)

    /**
     * Callback surface the native decode loop drives. The C++ side looks these
     * methods up by name via JNI (kept by `consumer-rules.pro`).
     */
    interface TokenSink {
        /** One produced piece of text. */
        fun onToken(text: String)

        /**
         * Polled by native between tokens. Return `true` to abort the loop
         * promptly (interruptible cancel). The engine flips this when its
         * collecting coroutine is cancelled.
         */
        fun isAborted(): Boolean

        /**
         * Loop finished.
         *
         * @param nativeStopReason one of [NATIVE_STOP_EOS], [NATIVE_STOP_LIMIT],
         *   [NATIVE_STOP_WORD], or [NATIVE_STOP_ABORT].
         * @param promptTokens     llama.cpp `n_p_eval` (prompt eval count); `< 0`
         *                         if unavailable.
         * @param genTokens        llama.cpp `n_eval` (generation count); `< 0` if
         *                         unavailable.
         */
        fun onStop(nativeStopReason: Int, promptTokens: Int, genTokens: Int)
    }

    companion object {
        // Native stop-reason codes. Kept in sync with llama_jni.cpp.
        const val NATIVE_STOP_EOS = 0      // llama.cpp stopped_eos
        const val NATIVE_STOP_LIMIT = 1    // llama.cpp stopped_limit (n_predict / n_ctx)
        const val NATIVE_STOP_WORD = 2     // llama.cpp stopped_word
        const val NATIVE_STOP_ABORT = 3    // aborted via TokenSink.isAborted()
    }
}

/**
 * Production [LlamaBridge] backed by `libailux_llama.so`.
 *
 * The `.so` is produced by this module's CMake build (see
 * `src/main/cpp/CMakeLists.txt`) when assembled with
 * `-Pailux.llamacpp.nativeBuild=true`, or supplied prebuilt by the consumer in
 * `jniLibs/arm64-v8a/`. [System.loadLibrary] is invoked lazily by the engine
 * (so that a process that never touches llama.cpp pays no native cost).
 */
internal object JniLlamaBridge : LlamaBridge {

    @Volatile
    private var loaded = false

    /** Idempotently loads the native library. Throws [UnsatisfiedLinkError] if absent. */
    fun ensureNativeLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("ailux_llama")
            loaded = true
        }
    }

    external override fun loadModel(
        modelPath: String,
        nCtx: Int,
        nGpuLayers: Int,
        nThreads: Int,
        useVulkan: Boolean,
    ): Long

    external override fun isVulkanActive(handle: Long): Boolean

    external override fun tokenCount(handle: Long, text: String): Int

    external override fun generate(
        handle: Long,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        stopWords: Array<String>,
        sink: LlamaBridge.TokenSink,
    )

    external override fun release(handle: Long)
}
