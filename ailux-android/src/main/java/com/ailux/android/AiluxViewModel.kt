package com.ailux.android

import androidx.lifecycle.ViewModel
import com.ailux.api.AiluxClient
import com.ailux.core.event.LLMEvent
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.state.LLMTaskState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
 *             streamGenerate(LLMRequest(prompt = prompt)).collect { event ->
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

    /** Current task state; can be collected directly with `collectAsState` in Compose. */
    val state: StateFlow<LLMTaskState>
        get() = client.state

    /**
     * Streaming generation, delegated to [client].
     *
     * @see AiluxClient.streamGenerate
     */
    protected fun streamGenerate(request: LLMRequest): Flow<LLMEvent> =
        client.streamGenerate(request)

    /**
     * Non-streaming generation, delegated to [client].
     *
     * @see AiluxClient.generate
     */
    protected suspend fun generate(request: LLMRequest): LLMResponse =
        client.generate(request)

    /**
     * Cancel the current request, delegated to [client].
     *
     * @see AiluxClient.cancel
     */
    fun cancel() {
        client.cancel()
    }

    override fun onCleared() {
        client.release()
        super.onCleared()
    }
}
