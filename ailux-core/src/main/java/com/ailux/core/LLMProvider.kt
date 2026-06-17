package com.ailux.core

import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.event.LLMEvent
import kotlinx.coroutines.flow.Flow

/**
 * Abstract interface for an LLM provider.
 *
 * Each integration approach (backend proxy, direct cloud connection, on-device inference)
 * implements its own [LLMProvider], wired into the [com.ailux.api] layer either via SPI
 * or by manual registration.
 *
 * For v0.1, only [streamGenerate] and [generate] are required.
 */
interface LLMProvider {

    /**
     * Streaming generation: emits [LLMEvent] events token by token.
     *
     * Event order: Token* -> Usage? -> Done | Error
     */
    fun streamGenerate(request: LLMRequest): Flow<LLMEvent>

    /**
     * Non-streaming generation: returns the full response in one shot.
     *
     * @throws Exception Implementations are expected to wrap network/auth/timeout
     * errors in [LLMError] before throwing.
     */
    suspend fun generate(request: LLMRequest): LLMResponse
}
