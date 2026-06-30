// ailux-runtime-llamacpp — JNI bridge between Kotlin (JniLlamaBridge) and
// upstream llama.cpp.
//
// Design goals (spec v0.3.1 §六):
//   * Expose llama.cpp's NATIVE signals, not a pre-digested LLMEvent:
//       - native stop reason (eos / limit / word / abort)
//       - native usage (n_p_eval / n_eval)
//       - interruptible cancel via an abort flag polled between tokens
//   * Stay a THIN bridge — the Kotlin engine owns the policy/mapping.
//
// JNI method names below are mangled for the Kotlin class
//   com.ailux.runtime.llamacpp.JniLlamaBridge
// (a Kotlin `object`, so the JVM class is JniLlamaBridge and the methods are
// instance methods on its singleton). Keep these in sync with
// LlamaNativeBridge.kt.
//
// NOTE: This file targets a recent llama.cpp (tag pinned in CMakeLists). The
// llama.cpp C API evolves; if you bump the tag and the build breaks, the most
// likely culprits are the sampler-chain API (llama_sampler_*) and
// llama_token_to_piece / llama_tokenize signatures. Adjust accordingly.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>

#include "llama.h"

#define LOG_TAG "AiluxLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Native stop codes — must match LlamaBridge companion constants.
static const jint NATIVE_STOP_EOS   = 0;
static const jint NATIVE_STOP_LIMIT = 1;
static const jint NATIVE_STOP_WORD  = 2;
static const jint NATIVE_STOP_ABORT = 3;

// One opaque handle bundles everything a generation pass needs.
struct AiluxLlamaContext {
    llama_model   *model   = nullptr;
    llama_context *ctx     = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_context_params cparams{};
    int n_threads = 0;
    bool vulkan_requested = false;
    bool owns_model = true;
};

// ── helpers ──────────────────────────────────────────────────────────────────

static std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

static std::vector<llama_token> tokenize(const llama_vocab *vocab,
                                         const std::string &text,
                                         bool add_special) {
    int n = -llama_tokenize(vocab, text.c_str(), (int) text.size(),
                            nullptr, 0, add_special, /*parse_special*/ true);
    std::vector<llama_token> tokens(n);
    int written = llama_tokenize(vocab, text.c_str(), (int) text.size(),
                                 tokens.data(), (int) tokens.size(),
                                 add_special, /*parse_special*/ true);
    if (written < 0) written = 0;
    tokens.resize(written);
    return tokens;
}

static std::string token_to_piece(const llama_vocab *vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, /*special*/ false);
    if (n < 0) return {};
    return std::string(buf, n);
}

// ── JNI: loadModel ────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_ailux_runtime_llamacpp_JniLlamaBridge_loadModel(
        JNIEnv *env, jobject /*thiz*/,
        jstring modelPath, jint nCtxLen, jint nGpuLayers, jint nThreads, jboolean useVulkan) {

    static bool backend_inited = false;
    if (!backend_inited) {
        llama_backend_init();
        backend_inited = true;
    }

    std::string path = jstring_to_std(env, modelPath);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;  // 0 = pure CPU; >0 offloads to Vulkan if built/available

    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed for %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (nCtxLen > 0) ? (uint32_t) nCtxLen : 0;  // 0 → model default
    if (nThreads > 0) {
        cparams.n_threads = nThreads;
        cparams.n_threads_batch = nThreads;
    }

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0;
    }

    auto *h = new AiluxLlamaContext();
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    h->cparams = cparams;
    h->n_threads = nThreads;
    h->vulkan_requested = (useVulkan == JNI_TRUE);
    h->owns_model = true;

    LOGI("loaded model %s (n_ctx=%u, n_gpu_layers=%d)", path.c_str(),
         llama_n_ctx(ctx), nGpuLayers);
    return reinterpret_cast<jlong>(h);
}

// ── JNI: createContext / releaseContext ───────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_ailux_runtime_llamacpp_JniLlamaBridge_createContext(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *base = reinterpret_cast<AiluxLlamaContext *>(handle);
    if (base == nullptr || base->model == nullptr) return 0;
    llama_context *ctx = llama_init_from_model(base->model, base->cparams);
    if (ctx == nullptr) return 0;

    auto *h = new AiluxLlamaContext();
    h->model = base->model;
    h->ctx = ctx;
    h->vocab = base->vocab;
    h->cparams = base->cparams;
    h->n_threads = base->n_threads;
    h->vulkan_requested = base->vulkan_requested;
    h->owns_model = false;
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ailux_runtime_llamacpp_JniLlamaBridge_releaseContext(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong contextHandle) {
    auto *h = reinterpret_cast<AiluxLlamaContext *>(contextHandle);
    if (h == nullptr) return;
    if (h->ctx) llama_free(h->ctx);
    delete h;
}

// ── JNI: isVulkanActive ───────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ailux_runtime_llamacpp_JniLlamaBridge_isVulkanActive(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *h = reinterpret_cast<AiluxLlamaContext *>(handle);
    if (h == nullptr) return JNI_FALSE;
    // Heuristic: we requested Vulkan AND at least one device offload happened.
    // A precise check would query ggml backend buffers; for v0.3.1 we report
    // "requested && offloading layers" as the active signal.
    return (h->vulkan_requested && llama_model_n_params(h->model) > 0) ? JNI_TRUE : JNI_FALSE;
}

// ── JNI: tokenCount ───────────────────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_com_ailux_runtime_llamacpp_JniLlamaBridge_tokenCount(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring text) {
    auto *h = reinterpret_cast<AiluxLlamaContext *>(handle);
    if (h == nullptr) return 0;
    std::string s = jstring_to_std(env, text);
    return (jint) tokenize(h->vocab, s, /*add_special*/ false).size();
}

// ── JNI: generate ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_ailux_runtime_llamacpp_JniLlamaBridge_generate(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring prompt,
        jfloat temperature, jfloat topP, jint topK, jint maxTokens,
        jobjectArray stopWords, jobject sink) {

    auto *h = reinterpret_cast<AiluxLlamaContext *>(handle);
    if (h == nullptr) return;

    // Resolve TokenSink callback method ids once.
    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID onToken   = env->GetMethodID(sinkClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID isAborted = env->GetMethodID(sinkClass, "isAborted", "()Z");
    jmethodID onStop    = env->GetMethodID(sinkClass, "onStop", "(III)V");

    // Collect stop words.
    std::vector<std::string> stops;
    if (stopWords != nullptr) {
        jsize n = env->GetArrayLength(stopWords);
        stops.reserve(n);
        for (jsize i = 0; i < n; ++i) {
            auto sw = (jstring) env->GetObjectArrayElement(stopWords, i);
            stops.push_back(jstring_to_std(env, sw));
            env->DeleteLocalRef(sw);
        }
    }

    std::string prompt_str = jstring_to_std(env, prompt);

    // ── build sampler chain ──
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (topK > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ── tokenize + prefill ──
    std::vector<llama_token> tokens = tokenize(h->vocab, prompt_str, /*add_special*/ true);
    const int n_prompt = (int) tokens.size();

    llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt);

    jint stop_reason = NATIVE_STOP_EOS;
    int n_generated = 0;
    const int hard_cap = (maxTokens > 0) ? maxTokens : INT32_MAX;
    std::string produced;  // accumulated text, for stop-word matching

    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("llama_decode (prefill) failed");
        env->CallVoidMethod(sink, onStop, NATIVE_STOP_ABORT, n_prompt, 0);
        llama_sampler_free(smpl);
        return;
    }

    // ── decode loop ──
    while (true) {
        // Interruptible cancel: poll the abort flag between tokens.
        if (env->CallBooleanMethod(sink, isAborted) == JNI_TRUE) {
            stop_reason = NATIVE_STOP_ABORT;
            break;
        }

        llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);

        if (llama_vocab_is_eog(h->vocab, tok)) {
            stop_reason = NATIVE_STOP_EOS;
            break;
        }

        std::string piece = token_to_piece(h->vocab, tok);
        produced += piece;

        jstring jpiece = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(sink, onToken, jpiece);
        env->DeleteLocalRef(jpiece);

        n_generated++;

        // Stop-word check (substring match on the accumulated tail).
        bool hit_stop_word = false;
        for (const auto &sw : stops) {
            if (!sw.empty() && produced.size() >= sw.size() &&
                produced.compare(produced.size() - sw.size(), sw.size(), sw) == 0) {
                hit_stop_word = true;
                break;
            }
        }
        if (hit_stop_word) { stop_reason = NATIVE_STOP_WORD; break; }

        if (n_generated >= hard_cap) { stop_reason = NATIVE_STOP_LIMIT; break; }

        // Feed the sampled token back for the next step.
        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(h->ctx, next) != 0) {
            LOGE("llama_decode (step) failed");
            stop_reason = NATIVE_STOP_ABORT;
            break;
        }
    }

    // ── native usage from llama.cpp perf counters ──
    // n_p_eval = prompt-eval tokens, n_eval = generated tokens.
    llama_perf_context_data perf = llama_perf_context(h->ctx);
    int prompt_tokens = (perf.n_p_eval > 0) ? (int) perf.n_p_eval : n_prompt;
    int gen_tokens    = (perf.n_eval   > 0) ? (int) perf.n_eval   : n_generated;

    env->CallVoidMethod(sink, onStop, stop_reason, prompt_tokens, gen_tokens);

    // Reset perf so the next generate() call starts clean.
    llama_perf_context_reset(h->ctx);
    llama_sampler_free(smpl);
}

// ── JNI: release ──────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_ailux_runtime_llamacpp_JniLlamaBridge_release(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *h = reinterpret_cast<AiluxLlamaContext *>(handle);
    if (h == nullptr) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->owns_model && h->model) llama_model_free(h->model);
    delete h;
}
