package com.ailux.api.concurrency

import com.ailux.core.concurrency.SessionConcurrencyPolicy
import com.ailux.core.logging.AiluxLogger
import com.ailux.core.logging.LogLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enforces the configured [SessionConcurrencyPolicy] across all sessions
 * (or stateless calls) on a single [com.ailux.api.AiluxClient].
 *
 * ## Two enforcement axes
 *
 * 1. **User policy** ([policy]): expresses *intent* — PARALLEL / CANCEL_PREVIOUS /
 *    ENQUEUE / REJECT.
 * 2. **Provider capability** ([maxConcurrentSessions]): expresses *fact* — the
 *    physical upper bound the provider can sustain. Defaults to
 *    [Int.MAX_VALUE] when unset.
 *
 * When the user opts into PARALLEL but the provider's capability cap is hit,
 * the coordinator silently degrades the *new* task to ENQUEUE behaviour
 * (wait for an in-flight task to finish) and emits a one-time WARN log via
 * [logger]. **Policy/capability mismatch never throws** — the goal is graceful
 * back-pressure rather than surprise failures.
 *
 * ## Thread-safety
 *
 * All mutable state is guarded by [lock] (a JVM intrinsic monitor). For
 * `ENQUEUE`, a coroutine [Mutex] provides fair FIFO ordering. For the
 * capability-cap fallback (`PARALLEL` over the provider cap), degraded tasks
 * wait on a small "ticket" [Channel] — each [onTaskEnd] sends one ticket,
 * each degraded waiter consumes one before entering the active set. This
 * keeps PARALLEL semantics intact for under-cap tasks while back-pressuring
 * over-cap tasks one-for-one.
 */
internal class ConcurrencyCoordinator(
    private val policy: SessionConcurrencyPolicy,
    private val maxConcurrentSessions: Int = Int.MAX_VALUE,
    private val logger: AiluxLogger? = null,
) {
    /** JVM monitor protecting [activeJobs]. */
    private val lock = Any()

    /** Fair coroutine mutex used by ENQUEUE only. */
    private val mutex = Mutex()

    /**
     * Capability-cap "ticket" channel. Each [onTaskEnd] from a non-rejected
     * task sends one ticket; a degraded PARALLEL task awaits one ticket
     * before entering the active set. Unlimited buffer so [onTaskEnd] never
     * blocks even when no waiter is currently parked.
     */
    private val capTickets = Channel<Unit>(capacity = Channel.UNLIMITED)

    /** Active (or queued) tasks keyed by request ID, in insertion order. */
    private val activeJobs = LinkedHashMap<String, Job>()

    /** One-shot warning latch — we log the cap-degradation message at most once per client. */
    private val capWarnEmitted = AtomicBoolean(false)

    /**
     * Called when a task is about to start. Applies the concurrency policy:
     * - PARALLEL: register immediately (subject to [maxConcurrentSessions] cap).
     * - CANCEL_PREVIOUS: cancel all existing tasks, then register.
     * - ENQUEUE: wait for the mutex (FIFO), then register.
     * - REJECT: return `false` if any task is already active.
     *
     * @param taskId   unique request ID for this task.
     * @param job      the coroutine [Job] backing this task (used for cancellation).
     * @param onQueued callback with the queue position (ENQUEUE / cap-degraded only).
     * @return `true` if the task is allowed to proceed; `false` if rejected.
     */
    suspend fun onTaskStart(taskId: String, job: Job, onQueued: (Int) -> Unit): Boolean {
        when (policy) {
            SessionConcurrencyPolicy.PARALLEL -> {
                if (activeCount() >= maxConcurrentSessions) {
                    // Cap reached: silently degrade — warn once, await a ticket
                    // from the next [onTaskEnd]. We may re-check the cap after
                    // receiving a ticket because multiple degraded waiters can
                    // race; the cheapest correct strategy is "consume tickets
                    // until activeCount falls below cap".
                    warnDegradeOnce()
                    onQueued(queueDepth() + 1)
                    while (activeCount() >= maxConcurrentSessions) {
                        capTickets.receive()
                    }
                }
                register(taskId, job)
            }
            SessionConcurrencyPolicy.CANCEL_PREVIOUS -> {
                cancelAllActive()
                register(taskId, job)
            }
            SessionConcurrencyPolicy.ENQUEUE -> {
                onQueued(queueDepth() + 1)
                mutex.lock(taskId)
                register(taskId, job)
            }
            SessionConcurrencyPolicy.REJECT -> {
                if (hasActive()) {
                    return false
                }
                register(taskId, job)
            }
        }
        return true
    }

    /**
     * Called when a task ends (success, failure, or cancellation).
     * Removes the task from [activeJobs] and releases the mutex if held.
     */
    fun onTaskEnd(taskId: String) {
        unregister(taskId)
        // ENQUEUE may have taken the mutex for this taskId; release iff held.
        if (mutex.holdsLock(taskId)) {
            mutex.unlock(taskId)
        }
        // Notify at most one waiting PARALLEL-degraded task that a slot opened.
        // trySend is safe because [capTickets] is UNLIMITED.
        capTickets.trySend(Unit)
    }

    /**
     * Cancel all active tasks immediately.
     * Used by [com.ailux.api.AiluxClient.cancelAll] and [com.ailux.api.AiluxClient.release].
     */
    fun cancelAll() {
        synchronized(lock) {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
        }
    }

    // ──────────────────── Private helpers ────────────────────

    private fun register(taskId: String, job: Job) {
        synchronized(lock) {
            activeJobs[taskId] = job
        }
    }

    private fun unregister(taskId: String) {
        synchronized(lock) {
            activeJobs.remove(taskId)
        }
    }

    private fun hasActive(): Boolean =
        synchronized(lock) { activeJobs.values.any { it.isActive } }

    private fun cancelAllActive() {
        synchronized(lock) {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
        }
    }

    private fun queueDepth(): Int =
        synchronized(lock) { activeJobs.size }

    private fun activeCount(): Int =
        synchronized(lock) { activeJobs.values.count { it.isActive } }

    private fun warnDegradeOnce() {
        if (capWarnEmitted.compareAndSet(false, true)) {
            logger?.log(
                level = LogLevel.WARN,
                tag = TAG,
                message = "SessionConcurrencyPolicy.PARALLEL exceeds provider capability " +
                    "(maxConcurrentSessions=$maxConcurrentSessions); new tasks will queue " +
                    "(soft-degraded to ENQUEUE). This warning is logged once per client.",
                throwable = null,
            )
        }
    }

    private companion object {
        private const val TAG = "Ailux/Coordinator"
    }
}
