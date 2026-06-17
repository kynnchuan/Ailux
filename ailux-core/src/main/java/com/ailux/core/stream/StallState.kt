package com.ailux.core.stream

/**
 * Carries stall detection state from the [stallDetection] operator to the
 * client's state reduction logic.
 *
 * @property stalled   `true` when a stall is detected; `false` when recovered.
 * @property idleMillis elapsed idle time at the moment of detection (0 on recovery).
 * @property phase     which timeout phase triggered the stall (null on recovery).
 */
data class StallState(
    val stalled: Boolean,
    val idleMillis: Long = 0L,
    val phase: StallPhase? = null
)
