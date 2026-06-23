package com.ailux.runtime

import com.ailux.core.config.LocalRuntimeConfig
import com.ailux.core.request.LLMRequest
import kotlinx.coroutines.flow.Flow

interface InferenceEngine {

    suspend fun load(config: LocalRuntimeConfig)

    fun streamGenerate(request: LLMRequest): Flow<EngineEvent>

    fun release()

    fun capabilities(): EngineCapabilities

    fun sizeInTokens(text: String): Int

}

sealed interface EngineEvent {

    data class Token(val text: String): EngineEvent

    data class Stop(val reason: EngineStopReason): EngineEvent

    data class Usage(val promptTokens: Int, val genTokens: Int): EngineEvent

}

enum class EngineStopReason {

    EOS,

    LENGTH,

    STOP_WORD,

    UNKNOWN

}