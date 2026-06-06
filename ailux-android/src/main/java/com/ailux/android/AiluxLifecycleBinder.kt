package com.ailux.android

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ailux.api.AiluxClient

/**
 * Binds an [AiluxClient]'s resource management to an Android [LifecycleOwner].
 *
 * Performs cancel / release on the appropriate lifecycle events according to [policy].
 * In every policy, the observer is automatically detached on ON_DESTROY via [unbind]
 * so that no reference to a destroyed LifecycleOwner is retained.
 *
 * ## Usage
 *
 * ```kotlin
 * // Option 1: extension function (recommended)
 * val client = AiluxClient(config)
 *     .bindLifecycle(viewLifecycleOwner, AiluxLifecyclePolicy.CANCEL_ON_DESTROY)
 *
 * // Option 2: direct construction
 * AiluxLifecycleBinder(client, lifecycleOwner, AiluxLifecyclePolicy.RELEASE_ON_DESTROY)
 * ```
 *
 * @param client the AiluxClient instance whose lifecycle is bound.
 * @param owner the host LifecycleOwner (Activity / Fragment); the reference is kept for auto-unbind.
 * @param policy the lifecycle binding policy. Defaults to [AiluxLifecyclePolicy.DEFAULT].
 * @see AiluxLifecyclePolicy
 */
internal class AiluxLifecycleBinder(
    private val client: AiluxClient,
    private val owner: LifecycleOwner,
    private val policy: AiluxLifecyclePolicy = AiluxLifecyclePolicy.DEFAULT,
) : DefaultLifecycleObserver {

    private var bound: Boolean = false

    init {
        bind()
    }

    override fun onStop(owner: LifecycleOwner) {
        when (policy) {
            AiluxLifecyclePolicy.CANCEL_ON_STOP_RELEASE_ON_DESTROY,
            AiluxLifecyclePolicy.CANCEL_ON_STOP -> {
                client.cancel()
            }
            else -> { /* no-op */ }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        when (policy) {
            AiluxLifecyclePolicy.RELEASE_ON_DESTROY,
            AiluxLifecyclePolicy.CANCEL_ON_STOP_RELEASE_ON_DESTROY -> {
                client.release()
            }
            AiluxLifecyclePolicy.CANCEL_ON_DESTROY -> {
                client.cancel()
            }
            AiluxLifecyclePolicy.CANCEL_ON_STOP,
            AiluxLifecyclePolicy.OBSERVE_ONLY -> {
                /* no-op */
            }
        }
        unbind()
    }

    /**
     * Explicitly detach the lifecycle observer.
     *
     * Usually unnecessary; [onDestroy] performs the same cleanup automatically.
     * This method does **not** invoke `cancel()` / `release()`; it merely removes the observer.
     * Useful when the caller wants to break the lifecycle binding while reusing the Client.
     */
    fun unbind() {
        if (!bound) return
        owner.lifecycle.removeObserver(this)
        bound = false
    }

    private fun bind() {
        if (bound) return
        owner.lifecycle.addObserver(this)
        bound = true
    }
}
