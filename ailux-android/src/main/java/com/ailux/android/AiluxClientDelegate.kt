package com.ailux.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ailux.api.AiluxClient
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.session.Session
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
 * ## Single-shot convenience over the Session API (v0.3.0b)
 *
 * The pre-v0.3.0b shortcuts `client.streamGenerate(req)` / `client.generate(req)`
 * have been removed (see ADR-0009 — Session is now the single entry point).
 * This delegate keeps the old call shape alive by wrapping every call in an
 * **anonymous Session**: each [streamGenerate] / [generate] internally
 * `client.openSession()`s, runs the request, and `close()`s the session
 * (either eagerly after a non-streaming call, or when the streaming flow
 * completes / is cancelled). The pipeline (context trim, stall, diagnostics,
 * state machine) is unchanged because it lives on the Session.
 *
 * ## Per-request handle model (v0.2.3)
 *
 * [streamGenerate] returns an [LLMTask] handle per request — each task carries
 * its own [LLMTask.state] and [LLMTask.events]. This delegate exposes a
 * convenience [state] that automatically tracks the **latest** task's state
 * via `flatMapLatest`, so UI layers can simply `collectAsState()` without
 * managing task references.
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
     * Single-shot streaming generation.
     *
     * Opens an anonymous [Session] under the hood, delegates to
     * [Session.streamGenerateAsTask] for the full pipeline, and arranges to
     * close that session when the returned task's [LLMTask.events] flow
     * completes (normally, exceptionally or via cancellation). The returned
     * [LLMTask] is automatically tracked so that [state] reflects this
     * task's state.
     *
     * Hosts that want to keep history across multiple turns should call
     * [AiluxClient.openSession] directly and reuse the session — that path
     * benefits from native KV-cache reuse on local engines.
     *
     * @see AiluxClient.openSession
     * @see Session.streamGenerateAsTask
     */
    fun streamGenerate(request: LLMRequest): LLMTask {
        val task = AnonymousSessionTask.wrap(client, request)
        currentTask.value = task
        return task
    }

    /**
     * Single-shot non-streaming generation.
     *
     * Opens an anonymous [Session], delegates to [Session.generate], and
     * closes the session in a `finally` block.
     *
     * @see AiluxClient.openSession
     * @see Session.generate
     */
    suspend fun generate(request: LLMRequest): LLMResponse {
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

    init {
        viewModel.addCloseable { onClear(client) }
    }
}
