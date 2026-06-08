package com.ailux.api

import com.ailux.api.context.DefaultLLMContextManager
import com.ailux.api.context.EstimatedTokenCounter
import com.ailux.api.context.resolveContextWindow
import com.ailux.core.config.ContextConfig
import com.ailux.core.config.ModelConfig
import com.ailux.core.context.LLMContextManager
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.response.UsageInfo
import com.ailux.core.state.LLMTaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
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

        val messages = request.messages ?: return@flow

        _state.value = LLMTaskState.Connecting
        var tokenCount = 0
        var latestUsage: UsageInfo? = null

        try {
            coroutineScope {
                val job = coroutineContext[Job]
                activeJob.set(job)

                // Resolve the effective context manager, applying per-request overrides
                // from ContextOverride if present. Each non-null field in the override
                // replaces the corresponding component from the global config.
                val baseContextManager = config.contextManager
                val override = request.contextOverride
                val effectiveContextManager: LLMContextManager? = when {
                    override != null && baseContextManager is DefaultLLMContextManager -> {
                        DefaultLLMContextManager(
                            tokenCounter = override.tokenCounter ?: baseContextManager.tokenCounter,
                            trimStrategy = override.strategy ?: baseContextManager.trimStrategy,
                            protector = override.protector ?: baseContextManager.protector
                        )
                    }
                    override != null && baseContextManager != null -> {
                        // Non-default context manager: per-request overrides not applicable.
                        baseContextManager
                    }
                    else -> baseContextManager
                }

                val effectiveMessages: List<Message>

                if (effectiveContextManager != null && messages.isNotEmpty()) {
                    val budget = resolveBudget(config.modelConfig)
                    // Aggressiveness: request-level override > global config
                    val effectiveAggressiveness = override?.aggressiveness
                        ?: config.trimAggressiveness
                    val contextConfig = ContextConfig(
                        budget = budget,
                        aggressiveness = effectiveAggressiveness
                    )

                    val result = effectiveContextManager.process(messages, contextConfig)
                    effectiveMessages = result.messages

                    if (result.removed.isNotEmpty()) {
                        emit(LLMEvent.ContextTrimmed(
                            result.removed.size,
                            result.estimatedTokensSaved
                        ))
                    }
                } else {
                    effectiveMessages = messages
                }

                // Pre-check warning: even with contextManager disabled, warn if
                // estimated tokens exceed the model's context window.
                if (effectiveContextManager == null) {
                    val estimated = EstimatedTokenCounter().count(messages)
                    val window = resolveContextWindow(config.modelConfig)
                    if (estimated > window) {
                        emit(LLMEvent.ContextTrimmed(
                            removedCount = 0,
                            estimatedTokensSaved = 0
                        ))
                    }
                }

                config.provider.streamGenerate(request.copy(messages = effectiveMessages)).collect { result ->
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
                        is LLMEvent.ToolCallDelta -> {
                            // Function-calling: tool call delta, no state change
                        }
                        is LLMEvent.ToolCallReceived -> {
                            // Function-calling: tool call received, no state change
                        }
                        is LLMEvent.Done -> {
                            // Final state is set in the finally block below.
                        }
                        is LLMEvent.ContextTrimmed -> {
                            // Forwarded from provider (unlikely in practice); just re-emit.
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
            emit(LLMEvent.Done())
        } finally {
            activeJob.set(null)
        }
    }


    /**
     * Compute the token budget available for input messages.
     *
     * Formula: contextWindow - reserveForReply.
     * - contextWindow is resolved via [resolveContextWindow] (ModelConfig > ModelRegistry > 128K fallback).
     * - reserveForReply defaults to 4096 if not specified in [ModelConfig].
     *
     * @param modelConfig optional model configuration carrying explicit overrides.
     * @return the token budget that the context manager should trim to.
     */
    private fun resolveBudget(modelConfig: ModelConfig?): Int {
        val contextWindow = resolveContextWindow(modelConfig)
        val reserveForReply = modelConfig?.reserveForReply ?: 4096
        return contextWindow - reserveForReply
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
