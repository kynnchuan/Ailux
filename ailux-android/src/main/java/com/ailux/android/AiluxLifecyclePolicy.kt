package com.ailux.android

/**
 * Lifecycle binding policy for [com.ailux.api.AiluxClient].
 *
 * Defines what an [AiluxLifecycleBinder] should do when the host
 * `LifecycleOwner` emits each lifecycle event. Different product
 * shapes should pick different policies.
 *
 * ## Choosing a policy
 *
 * | Product shape | Recommended policy | Rationale |
 * |---|---|---|
 * | Single-shot utility screen (translate, summarize) | [RELEASE_ON_DESTROY] | All state is discarded when the screen exits |
 * | Chat application | [CANCEL_ON_DESTROY] | Users expect to see the full conversation when they come back |
 * | Multi-tab application | [CANCEL_ON_DESTROY] | Switching tabs should not lose state |
 * | Resource-sensitive scenarios | [CANCEL_ON_STOP_RELEASE_ON_DESTROY] | Consume nothing while in the background |
 * | Long-running background tasks | [OBSERVE_ONLY] + manual management | Task lifecycle is decoupled from the UI |
 *
 * ## Behavior summary
 *
 * | Policy | ON_STOP | ON_DESTROY |
 * |---|---|---|
 * | [RELEASE_ON_DESTROY] | - | release() |
 * | [CANCEL_ON_STOP_RELEASE_ON_DESTROY] | cancel() | release() |
 * | [CANCEL_ON_DESTROY] | - | cancel() (do not release) |
 * | [CANCEL_ON_STOP] | cancel() | - (only unbind) |
 * | [OBSERVE_ONLY] | - | - (only unbind) |
 *
 * @see AiluxLifecycleBinder
 */
enum class AiluxLifecyclePolicy {

    /**
     * Do nothing on ON_STOP; release the Client on ON_DESTROY.
     *
     * All resources are released as soon as the screen is destroyed and the
     * Client cannot be reused. Suitable for simple cases where the Client's
     * lifecycle matches the screen's exactly.
     *
     * Equivalent to `cancelOnStop = false` in v0.1.
     */
    RELEASE_ON_DESTROY,

    /**
     * Cancel the active request on ON_STOP; release the Client on ON_DESTROY.
     *
     * The most aggressive resource-recovery policy: stop requests as soon as
     * the screen is no longer visible, and release the Client when it is
     * destroyed. Suitable for resource-sensitive scenarios such as live
     * streaming or real-time translation.
     *
     * Equivalent to `cancelOnStop = true` in v0.1.
     */
    CANCEL_ON_STOP_RELEASE_ON_DESTROY,

    /**
     * Do nothing on ON_STOP; only cancel the active request on ON_DESTROY
     * without releasing the Client.
     *
     * The Client can keep being reused and any already-produced data (e.g.
     * values held in a `StateFlow`) is preserved. Suitable for chat or
     * multi-tab applications that need to keep Client state across screens.
     */
    CANCEL_ON_DESTROY,

    /**
     * Cancel the active request on ON_STOP; do nothing on ON_DESTROY.
     *
     * Requests stop as soon as the screen is no longer visible, saving
     * resources, but the Client is neither cancelled nor released. Suitable
     * when screens are switched frequently and the Client is owned by a
     * higher-level container.
     */
    CANCEL_ON_STOP,

    /**
     * Perform no automatic action.
     *
     * The Binder only registers as a lifecycle observer; it never invokes
     * `cancel()` or `release()`. Suitable for advanced scenarios where the
     * business code fully controls the Client lifecycle, or when you only
     * want the observer to be automatically unbound on ON_DESTROY.
     */
    OBSERVE_ONLY;

    companion object {
        /**
         * Default policy: [RELEASE_ON_DESTROY].
         *
         * Matches the behavior of `cancelOnStop = false` in v0.1.
         */
        val DEFAULT: AiluxLifecyclePolicy = RELEASE_ON_DESTROY
    }
}
