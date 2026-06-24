package com.ailux.core.session

import com.ailux.core.error.LLMException
import com.ailux.core.event.LLMEvent
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.response.UsageInfo
import kotlinx.coroutines.flow.Flow

/**
 * Internal helpers backing the default implementations on [Session].
 *
 * Lives in `core` (not in `api`) on purpose — every [Session] implementation,
 * including those obtained directly from a `LLMProvider.openSession(...)`
 * call without the AiluxClient pipeline, gets a working `generate(...)`
 * non-streaming form and a minimal `streamGenerateAsTask(...)` task wrapper
 * for free.
 */
internal object SessionDefaults {

    /**
     * Collect [stream] for [request] and aggregate it into an [LLMResponse].
     *
     * Aggregation rules:
     * - Concatenate every [LLMEvent.Token].text into [LLMResponse.text].
     * - Keep the **last** [LLMEvent.Usage] seen (providers typically emit
     *   it once near the end; honour the latest value).
     * - If an [LLMEvent.Error] arrives before [LLMEvent.Done], rethrow it
     *   as an [LLMException] so non-streaming callers see the failure
     *   the same way they would see a thrown exception from a blocking
     *   API.
     *
     * The collection happens in the caller's coroutine context — cancellation
     * is propagated normally.
     *
     * @param modelId The stable model identifier provided by the [Session]
     *   (see [com.ailux.core.session.Session.modelId]). Echoed verbatim into
     *   [LLMResponse.model]. May be `null` for older [Session] implementations
     *   that don't expose one — in that case [LLMResponse.model] stays `null`,
     *   matching the legacy behaviour.
     */
    suspend fun collectToResponse(
        request: LLMRequest,
        modelId: String?,
        stream: (LLMRequest) -> Flow<LLMEvent>,
    ): LLMResponse {
        val text = StringBuilder()
        var usage: UsageInfo? = null
        var firstError: com.ailux.core.error.LLMError? = null

        stream(request).collect { ev ->
            when (ev) {
                is LLMEvent.Token -> text.append(ev.text)
                is LLMEvent.Usage -> usage = ev.info
                is LLMEvent.Error -> if (firstError == null) firstError = ev.error
                else -> Unit /* Connected / Reasoning / ToolCall* / Done / etc.: not aggregated */
            }
        }

        val capturedError = firstError
        if (capturedError != null) {
            throw LLMException(capturedError)
        }
        return LLMResponse(
            text = text.toString(),
            usage = usage,
            model = modelId,
        )
    }
}
