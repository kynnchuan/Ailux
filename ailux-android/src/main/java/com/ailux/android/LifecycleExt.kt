package com.ailux.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ailux.api.AiluxClient
import com.ailux.core.state.LLMTaskState
import com.ailux.core.task.LLMTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Bind this [AiluxClient] to the given [LifecycleOwner]'s lifecycle.
 *
 * Internally creates an [AiluxLifecycleBinder] that automatically manages the
 * Client on the corresponding lifecycle events according to [policy].
 * Returns this client to support call chaining.
 *
 * ```kotlin
 * val client = AiluxClient(config)
 *     .bindLifecycle(this, AiluxLifecyclePolicy.CANCEL_ON_DESTROY)
 * ```
 *
 * @param owner The host LifecycleOwner.
 * @param policy The lifecycle binding policy (defaults to [AiluxLifecyclePolicy.DEFAULT]).
 * @return The current AiluxClient instance (for chaining).
 */
fun AiluxClient.bindLifecycle(
    owner: LifecycleOwner,
    policy: AiluxLifecyclePolicy = AiluxLifecyclePolicy.DEFAULT,
): AiluxClient {
    AiluxLifecycleBinder(this, owner, policy)
    return this
}

/**
 * Lifecycle-safe collection of [LLMTask.state].
 *
 * Collection only happens when the [LifecycleOwner] is at [minActiveState] or
 * above, and is paused automatically below that state to avoid updating the
 * UI while in the background.
 *
 * Internally uses [repeatOnLifecycle], so collection restarts every time the
 * owner re-enters the active state.
 *
 * ```kotlin
 * // Inside Activity.onCreate or Fragment.onViewCreated
 * client.openSession().use { session ->
 *     val task = session.streamGenerateAsTask(request)
 *     task.collectState(this, lifecycleScope) { state ->
 *         when (state) {
 *             is LLMTaskState.Streaming -> updateUI(state.tokenCount)
 *             is LLMTaskState.Failed   -> showError(state.error)
 *             else -> {}
 *         }
 *     }
 * }
 * ```
 *
 * @param owner The host LifecycleOwner.
 * @param scope The CoroutineScope used to launch the collection coroutine
 *              (typically `lifecycleScope`).
 * @param minActiveState The minimum active state; collection is paused below
 *                       this state (defaults to STARTED).
 * @param collector Callback invoked for each emitted state.
 * @return The outer collection [Job], so callers can cancel it manually if needed.
 */
fun LLMTask.collectState(
    owner: LifecycleOwner,
    scope: CoroutineScope,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    collector: suspend (LLMTaskState) -> Unit,
): Job = scope.launch {
    owner.repeatOnLifecycle(minActiveState) {
        state.collect(collector)
    }
}
