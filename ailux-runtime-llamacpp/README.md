# ailux-runtime-llamacpp

The **second on-device inference engine** for Ailux: a [llama.cpp](https://github.com/ggml-org/llama.cpp)
binding that runs **any GGUF open-weight model** behind the same
`InferenceEngine` SPI as LiteRT-LM. Introduced in **v0.3.1**.

> Why a second engine? It exists to *calibrate* the `InferenceEngine`
> abstraction with a deliberately different engine shape — native stop reasons,
> interruptible cancel, native usage, Vulkan — rather than letting LiteRT-LM's
> single shape define the contract. See `ailux-docs/specs/v0.3/v0.3.1-llamacpp.md`.

---

## What this module ships

| Layer | Artefact | Notes |
|---|---|---|
| Kotlin engine | `LlamaCppEngine` | implements `InferenceEngine`; maps native signals → `EngineEvent` |
| JNI seam | `LlamaNativeBridge` (`LlamaBridge` / `JniLlamaBridge`) | thin, testable; production binds to `libailux_llama.so` |
| Native | `src/main/cpp/{CMakeLists.txt, llama_jni.cpp}` | compiles llama.cpp + our bridge into `libailux_llama.so` |

**Model framework (`.so`) is SDK's job; model weights (GGUF) are the
business's job** (ADR-0005). This module ships the framework; you supply the
GGUF via `LocalRuntimeConfig(modelSource = ModelSource.LocalPath("..."))`.

---

## Device requirements

- **arm64-v8a only.** In 2026, effectively every device that can run an
  on-device LLM (≥ 4 GB RAM) is arm64. `armeabi-v7a` (32-bit) cannot carry the
  weights; `x86_64` emulators are intentionally not shipped (would double the
  `.aar` size). **Use an arm64 physical device.**
- A GGUF model file on the device (see "Getting a model" below).
- Optional: a Vulkan loader for GPU acceleration (the engine falls back to CPU
  and reports `gpuBackend = NONE` when unavailable).

---

## Building the native `.so`

The native layer is **gated behind a Gradle property** so the Kotlin layer +
unit tests build everywhere (including plain JVM CI with no NDK):

```bash
# Kotlin only (default) — no NDK needed; unit tests run.
./gradlew :ailux-runtime-llamacpp:testDebugUnitTest

# Real native build — needs Android NDK + CMake installed.
./gradlew :ailux-runtime-llamacpp:assembleRelease -Pailux.llamacpp.nativeBuild=true
```

### Minimal self-build (NDK + CMake)

1. **Install the NDK + CMake** via Android Studio's SDK Manager, or:
   ```bash
   sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"
   ```
2. **Point `local.properties`** at your SDK (usually auto-written by Studio):
   ```properties
   sdk.dir=/Users/you/Library/Android/sdk
   ```
3. **Get llama.cpp source.** Two options (configured in `src/main/cpp/CMakeLists.txt`):
   - **(A) FetchContent (default):** CMake downloads the pinned tag at configure
     time. Needs network on the build machine.
   - **(B) Vendored checkout (offline / reproducible):**
     ```bash
     git clone --depth 1 --branch b4585 \
       https://github.com/ggml-org/llama.cpp.git \
       ailux-runtime-llamacpp/src/main/cpp/llama.cpp
     ```
     then in `CMakeLists.txt` comment out the `FetchContent_*` block and
     uncomment the `add_subdirectory(...)` line.
4. **Build:**
   ```bash
   ./gradlew :ailux-runtime-llamacpp:assembleRelease -Pailux.llamacpp.nativeBuild=true
   ```
   The `.so` lands in the module's `build/intermediates/.../arm64-v8a/` and is
   packaged into the `.aar`.

### Vulkan (optional GPU)

Edit `build.gradle.kts` → `externalNativeBuild.cmake.arguments` and flip
`-DGGML_VULKAN=OFF` to `ON`, then construct the engine with `useVulkan = true`:

```kotlin
val engine = LlamaCppEngine(nGpuLayers = 99, useVulkan = true)
```

Vulkan is honoured only when the `.so` was built with `GGML_VULKAN=ON` **and** a
Vulkan loader exists on-device; otherwise the engine reports `gpuBackend = NONE`
and runs on CPU (honest capability — never faked).

---

## Usage

```kotlin
val engine = LlamaCppEngine(
    nGpuLayers = 0,   // 0 = pure CPU; >0 offloads layers to Vulkan (engine-private, ADR-0001)
    nThreads = 4,     // CPU eval threads (engine-private)
    useVulkan = false,
)

val config = LocalRuntimeConfig(
    modelSource = ModelSource.LocalPath("/data/.../qwen2.5-0.5b-instruct-q4_k_m.gguf"),
    contextLength = 4096,    // engine-agnostic → llama.cpp n_ctx (spec §4.2)
    verifySha256 = "…",      // optional integrity check (verified by the Provider)
)

// Typically you wrap this in LocalRuntimeProvider, which adds device pre-check,
// SHA-256 verification, EngineEvent → LLMEvent normalization, and capability
// bubble-up. The engine on its own:
engine.load(config)
engine.streamGenerate(LLMRequest(messages = listOf(Message.User("Hi"))))
    .collect { event -> /* EngineEvent.Token / Usage / Stop */ }
```

### What llama.cpp gives you that LiteRT-LM doesn't

| Capability | llama.cpp | LiteRT-LM |
|---|---|---|
| Native finish reason | ✅ `stopped_eos / limit / word` | UNKNOWN → token-count workaround |
| Interruptible cancel | ✅ aborts mid-token | ❌ runs to completion |
| Native usage | ✅ `n_p_eval / n_eval` | estimated via `sizeInTokens` |
| GPU backend | Vulkan | GPU delegate |
| Model format | `.gguf` | `.litertlm` / `.task` |

---

## Getting a model (demo / business concern, not the SDK's)

The SDK only takes a `LocalPath`. To get a GGUF onto the device:

- **Download (recommended for the demo):** pull from a China-stable mirror, e.g.
  ModelScope `lmstudio-community/gemma-3-1b-it-GGUF`, then verify with
  `LocalRuntimeConfig.verifySha256`. **First run needs network; subsequent runs
  are fully offline.**
- **SAF file picker:** let the user point at an existing local GGUF.
- **Bundle with the APK:** copy the file into `filesDir` and pass its
  `LocalPath` (not packaged by this module — it only ships the framework).

---

## Status / limitations (v0.3.1)

- **Stateless only.** KV-cache *session* reuse across turns is a later
  increment; multi-turn callers go through the Provider's
  `StatelessProviderSession`.
- **No native tool-calling** wired yet (`supportsTools = false`).
- The native `.so` real build + on-device inference must be done on a machine
  with the NDK and validated on an arm64 device — the JNI C++ is written against
  a pinned llama.cpp tag and may need small adjustments if you bump it.
