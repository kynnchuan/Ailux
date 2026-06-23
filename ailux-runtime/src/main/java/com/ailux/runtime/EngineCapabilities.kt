package com.ailux.runtime

data class EngineCapabilities(
    val supportAbis: Set<String>,
    val estimatedRamMb: Int,
    val gpuBackend: GpuBackend,
    val supportsTools: Boolean,
    val supportsInterruptibleCancellation: Boolean,
    val supportsModelExtensions: Set<String>,
)

enum class GpuBackend {
    NONE, VULKAN, GPU_DELEGATE
}