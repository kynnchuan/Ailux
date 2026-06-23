package com.ailux.provider.local.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.ailux.runtime.EngineCapabilities

/**
 * Device-capability probe — runs **before** [com.ailux.runtime.InferenceEngine.load]
 * so we fail fast (saving the seconds-long cold-load cost) when the device
 * provably cannot run the engine.
 *
 * Spec §4.5. Two checks:
 *
 * - **ABI** (`UNSUPPORTED_ABI`): the device's [Build.SUPPORTED_ABIS] must overlap
 *   with the engine's [EngineCapabilities.supportAbis]. v0.3.0 ships `arm64-v8a`
 *   only — armeabi-v7a / x86 devices are friendly-rejected here, before any
 *   native `.so` load is attempted. Gradle `abiFilters arm64-v8a` is the
 *   build-time complement.
 *
 * - **RAM** (`INSUFFICIENT_MEMORY`): default-on check that compares the engine's
 *   self-estimated RAM (model size × coefficient + KV cache + activations) against
 *   the device's `ActivityManager.MemoryInfo.totalMem`. [ActivityManager.isLowRamDevice]
 *   is a hard floor regardless of estimate — even a 4GB device flagged "low-ram"
 *   by the OEM is rejected. Business may override via
 *   [com.ailux.core.config.LocalRuntimeConfig.minRamMb] (escape hatch, default null
 *   = use engine's self-estimate).
 *
 * Rationale (spec §4.5 footnote): "business has no way to guess an absolute RAM
 * threshold — the industry pattern is engine-self-estimate + system API floor.
 * Even Google's official guidance for Gemma3 1B only gives a coarse '≥4GB'."
 */
object DeviceProbe {

    /** Outcome of a single probe. Carries enough info for the caller to map into [com.ailux.core.error.LLMError]. */
    sealed interface Result {
        /** Probe passed — safe to proceed with [com.ailux.runtime.InferenceEngine.load]. */
        data object Ok : Result

        /**
         * No overlap between device ABIs and engine ABIs.
         * Caller should raise `ErrorCode.UNSUPPORTED_ABI`.
         */
        data class UnsupportedAbi(
            val deviceAbis: List<String>,
            val engineAbis: Set<String>,
        ) : Result {
            val message: String
                get() = "Device ABIs $deviceAbis have no overlap with engine ABIs $engineAbis"
        }

        /**
         * Device RAM is insufficient (low-RAM device, or below threshold).
         * Caller should raise `ErrorCode.INSUFFICIENT_MEMORY`.
         */
        data class InsufficientMemory(
            val totalMb: Long,
            val requiredMb: Int,
            val lowRamDevice: Boolean,
        ) : Result {
            val message: String
                get() = if (lowRamDevice) {
                    "Device is flagged as low-RAM by the OS (totalMem=${totalMb}MB)"
                } else {
                    "Device totalMem=${totalMb}MB below required=${requiredMb}MB"
                }
        }
    }

    /**
     * Run both checks against [capabilities].
     *
     * @param context     Android context, used to query [ActivityManager].
     * @param capabilities Engine self-description (ABIs + estimated RAM).
     * @param minRamMbOverride Business override for the RAM threshold. `null` = use
     *                         the engine's self-estimate ([EngineCapabilities.estimatedRamMb]).
     */
    fun check(
        context: Context,
        capabilities: EngineCapabilities,
        minRamMbOverride: Int? = null,
    ): Result {
        // 1) ABI check — cheap, do first.
        val deviceAbis = supportedAbis()
        if (capabilities.supportAbis.intersect(deviceAbis.toSet()).isEmpty()) {
            return Result.UnsupportedAbi(deviceAbis, capabilities.supportAbis)
        }

        // 2) RAM check — default-on. Override > engine self-estimate.
        val requiredMb = minRamMbOverride ?: capabilities.estimatedRamMb
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Result.Ok // No ActivityManager in test context — best-effort skip.

        val isLowRam = am.isLowRamDevice
        val totalMb = totalMemMb(am)
        if (isLowRam || totalMb < requiredMb) {
            return Result.InsufficientMemory(
                totalMb = totalMb,
                requiredMb = requiredMb,
                lowRamDevice = isLowRam,
            )
        }
        return Result.Ok
    }

    /** Visible-for-test seam — overridable via [supportedAbisProvider] in tests. */
    internal var supportedAbisProvider: () -> List<String> = {
        Build.SUPPORTED_ABIS?.toList() ?: emptyList()
    }

    private fun supportedAbis(): List<String> = supportedAbisProvider()

    private fun totalMemMb(am: ActivityManager): Long {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024L * 1024L)
    }
}
