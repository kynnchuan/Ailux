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
 * ViewModel-side delegate for [AiluxClient]; manages the Client lifecycle by composition.
 *
 * Avoids the single-inheritance limitation imposed by [AiluxViewModel].
 * Business ViewModels can extend their own BaseViewModel while still holding
 * and using an AiluxClient through this delegate.
 *
 * ## onClear behavior
 *
 * When the host ViewModel is cleared, the [onClear] callback is invoked.
 * The caller decides whether to release, cancel, or do nothing —
 * keeping the Client lifecycle driven by business semantics rather than forced by the SDK.
 *
 * ## Usage
 *
 * ### Basic usage (Client lifecycle == ViewModel lifecycle)
 *
 * ```kotlin
 * class ChatViewModel(client: AiluxClient) : MyBaseViewModel() {
 *
 *     private val ailux = AiluxClientDelegate(
 *         client = client,
 *         viewModel = this,
 *         onClear = { it.release() },
 *     )
 *
 *     val taskState: StateFlow<LLMTaskState> get() = ailux.state
 *
 *     fun send(prompt: String) {
 *         viewModelScope.launch {
 *             ailux.streamGenerate(LLMRequest(prompt = prompt)).collect { event ->
 *                 // handle event
 *             }
 *         }
 *     }
 *
 *     fun stop() = ailux.cancel()
 * }
 * ```
 *
 * ### Shared Client (do not release on ViewModel clear)
 *
 * ```kotlin
 * class SharedToolViewModel(
 *     sharedClient: AiluxClient,
 * ) : MyBaseViewModel() {
 *
 *     private val ailux = AiluxClientDelegate(
 *         client = sharedClient,
 *         viewModel = this,
 *         onClear = { /* do not release; the Client is shared */ },
 *     )
 * }
 * ```
 *
 * @param client a fully configured AiluxClient instance.
 * @param viewModel the host ViewModel; used to attach the onCleared callback.
 * @param onClear callback invoked when the ViewModel is cleared. Defaults to [AiluxClient.release].
 */
class AiluxClientDelegate(
    val client: AiluxClient,
    viewModel: ViewModel,
    private val onClear: (AiluxClient) -> Unit = { it.release() },
) {

    /** Current task state; can be `collectAsState` directly in Compose. */
    val state: StateFlow<LLMTaskState>
        get() = client.state

    /**
     * Streaming generation, delegated to [client].
     *
     * @see AiluxClient.streamGenerate
     */
    fun streamGenerate(request: LLMRequest): Flow<LLMEvent> =
        client.streamGenerate(request)

    /**
     * Non-streaming generation, delegated to [client].
     *
     * @see AiluxClient.generate
     */
    suspend fun generate(request: LLMRequest): LLMResponse =
        client.generate(request)

    /**
     * Cancel the in-flight request, delegated to [client].
     *
     * @see AiluxClient.cancel
     */
    fun cancel() {
        client.cancel()
    }

    init {
        viewModel.addCloseable { onClear(client) }
    }
}
