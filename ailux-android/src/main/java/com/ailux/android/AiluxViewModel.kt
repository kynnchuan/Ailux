package com.ailux.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ailux.api.AiluxClient
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.state.LLMTaskState
import com.ailux.core.task.LLMTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Base [ViewModel] with a built-in [AiluxClient].
 *
 * Subclasses do not need to release the Client manually — when the ViewModel
 * is cleared (Activity finished / Fragment detached), [onCleared] invokes
 * `client.release()` automatically.
 *
 * ## When to use this class vs. [AiluxClientDelegate]
 *
 * | Scenario | Recommended approach |
 * |---|---|
 * | The ViewModel has no other parent class | Extend `AiluxViewModel` (more concise) |
 * | The ViewModel already extends a custom base (e.g. `BaseViewModel`) | Compose with [AiluxClientDelegate] |
 * | The Client is a shared instance that must NOT be released when the ViewModel clears | Use [AiluxClientDelegate] with a custom `onClear` |
 *
 * ## Design principles
 *
 * - **Single responsibility**: only "hold the Client + release on clear"; no
 *   opinionated UI state-management strategy.
 * - **Open inheritance**: subclasses are free to choose `MutableStateFlow`,
 *   `LiveData` or Compose `State`.
 * - **Convenience delegation**: exposes [state], [streamGenerate], [generate]
 *   and [cancel] as convenience members to cut boilerplate in subclasses.
 *
 * ## Single-shot convenience over the Session API (v0.3.0b)
 *
 * Since v0.3.0b the only public LLM entry point on [AiluxClient] is the
 * Session API (see ADR-0009). This ViewModel keeps the historical
 * call shape by wrapping each [streamGenerate] / [generate] in an **anonymous
 * Session** that is opened on entry and closed when the call terminates.
 *
 * Subclasses that need cross-turn history should call
 * [AiluxClient.openSession] directly and reuse the session.
 *
 * ## Per-request handle model (v0.2.3)
 *
 * [streamGenerate] returns an [LLMTask] handle per request. Each task
 * carries its own [LLMTask.state] and [LLMTask.events]. This ViewModel
 * exposes a convenience [state] that automatically tracks the **latest**
 * task's state via `flatMapLatest`, so UI layers can simply
 * `collectAsState()` without managing task references.
 *
 * ## Usage example
 *
 * ```kotlin
 * class ChatViewModel(client: AiluxClient) : AiluxViewModel(client) {
 *
 *     private val _messages = MutableStateFlow<List<String>>(emptyList())
 *     val messages: StateFlow<List<String>> = _messages.asStateFlow()
 *
 *     fun send(prompt: String) {
 *         viewModelScope.launch {
 *             val task = streamGenerate(LLMRequest(prompt = prompt))
 *             task.events.collect { event ->
 *                 when (event) {
 *                     is LLMEvent.Token -> appendToken(event.text)
 *                     is LLMEvent.Error -> showError(event.error)
 *                     else -> {}
 *                 }
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param client A fully configured AiluxClient instance.
 * @see AiluxClientDelegate
 */
abstract class AiluxViewModel(
    protected val client: AiluxClient,
) : ViewModel() {

    /** Tracks the latest task created by [streamGenerate]; null when no task is active. */
    private val currentTask = MutableStateFlow<LLMTask?>(null)

    /**
     * Current task state — automatically follows the latest [LLMTask].
     *
     * When no task is active, emits [LLMTaskState.Idle].
     * Can be collected directly with `collectAsState` in Compose.
     */
    val state: StateFlow<LLMTaskState> = currentTask.flatMapLatest { task ->
        task?.state ?: flowOf(LLMTaskState.Idle)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = LLMTaskState.Idle
    )

    /**
     * Single-shot streaming generation.
     *
     * Opens an anonymous [com.ailux.core.session.Session] under the hood,
     * delegates to [com.ailux.core.session.Session.streamGenerateAsTask],
     * and arranges to close that session when the returned task's
     * [LLMTask.events] flow completes (normally, exceptionally or via
     * cancellation). The returned task is automatically tracked so that
     * [state] reflects this task's state.
     *
     * Subclasses that need cross-turn history should call
     * [AiluxClient.openSession] directly and reuse the session.
     *
     * @see AiluxClient.openSession
     * @see com.ailux.core.session.Session.streamGenerateAsTask
     */
    protected fun streamGenerate(request: LLMRequest): LLMTask {
        val task = AnonymousSessionTask.wrap(client, request)
        currentTask.value = task
        return task
    }

    /**
     * Single-shot non-streaming generation.
     *
     * Opens an anonymous [com.ailux.core.session.Session], delegates to
     * [com.ailux.core.session.Session.generate], and closes the session in
     * a `finally` block.
     *
     * @see AiluxClient.openSession
     * @see com.ailux.core.session.Session.generate
     */
    protected suspend fun generate(request: LLMRequest): LLMResponse {
        val session = client.openSession()
        return try {
            session.generate(request)
        } finally {
            session.close()
        }
    }

    /**
     * Cancel all in-flight requests, delegated to [client].
     *
     * @see AiluxClient.cancelAll
     */
    fun cancel() {
        client.cancelAll()
    }

    override fun onCleared() {
        client.release()
        super.onCleared()
    }
}
