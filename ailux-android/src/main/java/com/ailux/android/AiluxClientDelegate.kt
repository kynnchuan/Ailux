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
 * ViewModel-side delegate for [AiluxClient]; manages the Client lifecycle by composition.
 *
 * Avoids the single-inheritance limitation imposed by [AiluxViewModel].
 * Business ViewModels can extend their own BaseViewModel while still holding
 * and using an AiluxClient through this delegate.
 *
 * ## Per-request handle model (v0.2.3)
 *
 * Since v0.2.3, [AiluxClient.streamGenerate] returns an [LLMTask] handle per request.
 * Each task carries its own [LLMTask.state] and [LLMTask.events].
 * This delegate exposes a convenience [state] that automatically tracks the
 * **latest** task's state via `flatMapLatest`, so UI layers can simply
 * `collectAsState()` without managing task references.
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
 *             val task = ailux.streamGenerate(LLMRequest(prompt = prompt))
 *             task.events.collect { event ->
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

    /** Tracks the latest task created by [streamGenerate]; null when no task is active. */
    private val currentTask = MutableStateFlow<LLMTask?>(null)

    /**
     * Current task state — automatically follows the latest [LLMTask].
     *
     * When no task is active, emits [LLMTaskState.Idle].
     * Can be `collectAsState` directly in Compose.
     */
    val state: StateFlow<LLMTaskState> = currentTask.flatMapLatest { task ->
        task?.state ?: flowOf(LLMTaskState.Idle)
    }.stateIn(
        scope = viewModel.viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = LLMTaskState.Idle
    )

    /**
     * Streaming generation, delegated to [client].
     *
     * The returned [LLMTask] is automatically tracked so that [state]
     * reflects this task's state.
     *
     * @see AiluxClient.streamGenerate
     */
    fun streamGenerate(request: LLMRequest): LLMTask {
        val task = client.streamGenerate(request)
        currentTask.value = task
        return task
    }

    /**
     * Non-streaming generation, delegated to [client].
     *
     * @see AiluxClient.generate
     */
    suspend fun generate(request: LLMRequest): LLMResponse =
        client.generate(request)

    /**
     * Cancel all in-flight requests, delegated to [client].
     *
     * @see AiluxClient.cancelAll
     */
    fun cancel() {
        client.cancelAll()
    }

    init {
        viewModel.addCloseable { onClear(client) }
    }
}
