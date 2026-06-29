# ailux-runtime-llamacpp — native build
#
# Produces libailux_llama.so (arm64-v8a) = our thin JNI bridge linked against
# upstream llama.cpp. This is the first Ailux module that owns native
# compilation (spec v0.3.1 §1.3).
#
# Driven by AGP externalNativeBuild ONLY when assembled with
#   -Pailux.llamacpp.nativeBuild=true
# (see ../../build.gradle.kts for the gate and the reason).
#
# ── How llama.cpp source is obtained ────────────────────────────────────────
# Two supported ways; pick one:
#
#  (A) FetchContent (default below): CMake downloads a pinned llama.cpp tag at
#      configure time. Requires network on the build machine.
#
#  (B) git submodule / manual checkout: clone llama.cpp into
#      `src/main/cpp/llama.cpp/` (gitignored) and comment out the FetchContent
#      block, uncommenting the add_subdirectory line. Best for reproducible /
#      offline builds.
#
# ── Vulkan ──────────────────────────────────────────────────────────────────
# Pass -DGGML_VULKAN=ON (forwarded from build.gradle.kts cmake.arguments) to
# enable the Vulkan backend. Default OFF (pure CPU) since not every NDK/driver
# combo ships the Vulkan headers.

cmake_minimum_required(VERSION 3.22.1)
project(ailux_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pin the upstream llama.cpp revision for reproducibility. Bump deliberately.
set(AILUX_LLAMA_CPP_TAG "b4585" CACHE STRING "Upstream llama.cpp git tag/commit")

# ── Option (A): FetchContent ────────────────────────────────────────────────
include(FetchContent)
FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG ${AILUX_LLAMA_CPP_TAG}
        GIT_SHALLOW TRUE
)

# Build llama.cpp as a static lib subset; we don't need its examples/tests/server.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
# GGML_VULKAN is set (ON/OFF) from build.gradle.kts cmake.arguments.

FetchContent_MakeAvailable(llama_cpp)

# ── Option (B): vendored checkout (uncomment to use, comment out FetchContent) ─
# add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp ${CMAKE_BINARY_DIR}/llama.cpp)

# ── Our JNI bridge ──────────────────────────────────────────────────────────
add_library(ailux_llama SHARED
        llama_jni.cpp
)

target_link_libraries(ailux_llama
        PRIVATE
        llama
        ggml
        android
        log
)

# Strip the release .so to keep the .aar small.
target_link_options(ailux_llama PRIVATE
        $<$<CONFIG:Release>:-Wl,--gc-sections -Wl,--strip-all>
)
