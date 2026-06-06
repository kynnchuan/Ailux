package com.ailux.api

import com.ailux.core.model.ErrorCode
import com.ailux.core.model.LLMError
import com.ailux.core.model.LLMRequest
import com.ailux.core.model.LLMResponse
import com.ailux.core.model.LLMEvent
import com.ailux.core.model.LLMTaskState
import com.ailux.core.model.UsageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Instantiable LLM client.
 *
 * Holds an [AiluxConfig] and routes inference requests to the configured
 * [com.ailux.core.LLMProvider]. Supports multiple coexisting instances,
 * dependency injection, and unit-test mocking.
 *
 * Lifecycle:
 * - After construction, [streamGenerate] / [generate] can be called immediately.
 * - Use [cancel] to cancel the in-flight request.
 * - Call [release] when the client is no longer needed.
 *
 * State observation:
 * - [state] exposes a `StateFlow<LLMTaskState>` that the UI layer can collect directly.
 *
 * ```kotlin
 * val client = AiluxClient(config)
 *
 * // Streaming generation
 * client.streamGenerate(LLMRequest(prompt = "Hello")).collect { result ->
 *     when (result) {
 *         is LLMEvent.Token -> append(result.text)
 *         is LLMEvent.Usage -> showUsage(result.info)
 *         is LLMEvent.Error -> showError(result.error)
 *         LLMEvent.Done     -> hideLoading()
 *     }
 * }
 *
 * // Non-streaming generation
 * val response = client.generate(LLMRequest(prompt = "Hello"))
 * ```
 */
class AiluxClient(
    val config: AiluxConfig,
) {
    private val _state = MutableStateFlow<LLMTaskState>(LLMTaskState.Idle)

    /** Current task state. The UI layer can collect this Flow for reactive rendering. */
    val state: StateFlow<LLMTaskState> = _state.asStateFlow()

    /** Reference to the active request's Job, used by cancel. */
    private val activeJob = AtomicReference<Job?>(null)

    /** Whether the Client has been released. */
    @Volatile
    private var released = false

    /**
     * Streaming generation: emits [LLMEvent]s token by token.
     *
     * Automatically updates [state]: Idle -> Connecting -> Streaming -> Completed / Failed.
     * The stream can be terminated either by the collector cancelling the coroutine or by [cancel].
     *
     * @param request inference request.
     * @return a cold flow; the request is dispatched only when collection starts.
     * @throws IllegalStateException if the Client has been [release]d.
     */
    fun streamGenerate(request: LLMRequest): Flow<LLMEvent> = flow {
        checkNotReleased()

        _state.value = LLMTaskState.Connecting
        var tokenCount = 0
        var latestUsage: UsageInfo? = null

        try {
            coroutineScope {
                val job = coroutineContext[Job]
                activeJob.set(job)

                config.provider.streamGenerate(request).collect { result ->
                    when (result) {
                        is LLMEvent.Token -> {
                            tokenCount++
                            _state.value = LLMTaskState.Streaming(tokenCount)
                        }
                        is LLMEvent.Reasoning -> {
                            tokenCount++
                            _state.value = LLMTaskState.Streaming(tokenCount)
                        }
                        is LLMEvent.Usage -> {
                            latestUsage = result.info
                        }
                        is LLMEvent.Error -> {
                            _state.value = LLMTaskState.Failed(result.error)
                        }
                        LLMEvent.Done -> {
                            // Final state is set in the finally block below.
                        }
                    }
                    emit(result)
                }

                // Stream ended normally.
                _state.value = LLMTaskState.Completed(latestUsage)
            }
        } catch (e: CancellationException) {
            // Cancelled either by cancel() or by the collector.
            _state.value = LLMTaskState.Idle
            throw e
        } catch (e: Exception) {
            val error = LLMError(
                code = ErrorCode.UNKNOWN,
                message = e.message ?: "Unknown error",
                cause = e,
            )
            _state.value = LLMTaskState.Failed(error)
            emit(LLMEvent.Error(error))
            emit(LLMEvent.Done)
        } finally {
            activeJob.set(null)
        }
    }

    /**
     * Non-streaming generation: waits for the full response and returns it once.
     *
     * Updates [state] in the same way as streaming, suitable for cases where
     * token-by-token rendering is not required.
     *
     * @param request inference request.
     * @return the complete response.
     * @throws Exception any exception thrown by the Provider (network/auth/timeout, etc.).
     * @throws IllegalStateException if the Client has been [release]d.
     */
    suspend fun generate(request: LLMRequest): LLMResponse {
        checkNotReleased()

        _state.value = LLMTaskState.Connecting

        return try {
            coroutineScope {
                val job = coroutineContext[Job]
                activeJob.set(job)

                val response = config.provider.generate(request)
                _state.value = LLMTaskState.Completed(response.usage)
                response
            }
        } catch (e: CancellationException) {
            _state.value = LLMTaskState.Idle
            throw e
        } catch (e: Exception) {
            val error = LLMError(
                code = ErrorCode.UNKNOWN,
                message = e.message ?: "Unknown error",
                cause = e,
            )
            _state.value = LLMTaskState.Failed(error)
            throw e
        } finally {
            activeJob.set(null)
        }
    }

    /**
     * Cancel the in-flight request.
     *
     * State transition: <current state> -> Cancelling -> Idle.
     * No-op if there is no active request.
     */
    fun cancel() {
        val job = activeJob.getAndSet(null) ?: return
        _state.value = LLMTaskState.Cancelling
        job.cancel()
        _state.value = LLMTaskState.Idle
    }

    /**
     * Release resources held by this client.
     *
     * After release, the client is unusable; [streamGenerate] / [generate] will throw.
     * Any active request is cancelled first.
     */
    fun release() {
        released = true
        cancel()
    }

    /** Verify that the Client has not been released. */
    private fun checkNotReleased() {
        check(!released) { "AiluxClient has been release()d and can no longer be used." }
    }
}
