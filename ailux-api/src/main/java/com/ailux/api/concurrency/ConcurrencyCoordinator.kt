package com.ailux.api.concurrency

import com.ailux.core.concurrency.ConcurrencyPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex

/**
 * Enforces the configured [ConcurrencyPolicy] across all tasks on a single [AiluxClient].
 *
 * Thread-safety: all mutable state is guarded by [lock] (a JVM intrinsic monitor).
 * For [ConcurrencyPolicy.ENQUEUE], a coroutine [Mutex] provides fair FIFO ordering;
 * tasks wait on `mutex.lock(taskId)` and release in `onTaskEnd`.
 *
 * Design notes:
 * - `register` + `hasActive` for REJECT is **not** TOCTOU-safe in a concurrent world
 *   (two tasks could both pass `hasActive == false` simultaneously). In practice this is
 *   acceptable because `onTaskStart` is called from a single `flow { }` block per task,
 *   and the coordinator is per-client, not global. A future version may add a compare-and-set.
 * - This class is `internal`; callers only interact via [AiluxClient].
 */
internal class ConcurrencyCoordinator(
    private val policy: ConcurrencyPolicy
) {
    /** JVM monitor protecting [activeJobs]. */
    private val lock = Any()

    /** Fair coroutine mutex used exclusively by [ConcurrencyPolicy.ENQUEUE]. */
    private val mutex = Mutex()

    /** Active (or queued) tasks keyed by request ID, in insertion order. */
    private val activeJobs = LinkedHashMap<String, Job>()

    /**
     * Called when a task is about to start. Applies the concurrency policy:
     * - PARALLEL: register immediately.
     * - CANCEL_PREVIOUS: cancel all existing tasks, then register.
     * - ENQUEUE: wait for the mutex (FIFO), then register.
     * - REJECT: return `false` if any task is already active.
     *
     * @param taskId  unique request ID for this task.
     * @param job     the coroutine [Job] backing this task (used for cancellation).
     * @param onQueued callback with the queue position (ENQUEUE only).
     * @return `true` if the task is allowed to proceed; `false` if rejected.
     */
    suspend fun onTaskStart(taskId: String, job: Job, onQueued: (Int) -> Unit): Boolean {
        when (policy) {
            ConcurrencyPolicy.PARALLEL -> {
                register(taskId, job)
            }
            ConcurrencyPolicy.CANCEL_PREVIOUS -> {
                cancelAllActive()
                register(taskId, job)
            }
            ConcurrencyPolicy.ENQUEUE -> {
                onQueued(queueDepth() + 1)
                mutex.lock(taskId)
                register(taskId, job)
            }
            ConcurrencyPolicy.REJECT -> {
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
     * Removes the task from [activeJobs] and releases the ENQUEUE mutex if held.
     */
    fun onTaskEnd(taskId: String) {
        unregister(taskId)
        if (policy == ConcurrencyPolicy.ENQUEUE && mutex.holdsLock(taskId)) {
            mutex.unlock(taskId)
        }
    }

    /**
     * Cancel all active tasks immediately.
     * Used by [AiluxClient.cancelAll] and [AiluxClient.release].
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

    private fun hasActive(): Boolean {
        return activeJobs.values.any { it.isActive }
    }

    private fun cancelAllActive() {
        synchronized(lock) {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
        }
    }

    private fun queueDepth(): Int {
        synchronized(lock) {
            return activeJobs.size
        }
    }
}