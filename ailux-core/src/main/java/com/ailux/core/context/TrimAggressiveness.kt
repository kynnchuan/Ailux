package com.ailux.core.context

/**
 * Controls how the context manager treats digested function-calling groups during trimming.
 *
 * A "digested" tool group is one where the model has already produced a summarizing
 * assistant reply after the tool results.
 *
 * - [CONSERVATIVE]: Digested tool groups are **protected** — they will not be trimmed
 *   even when the token budget is exceeded. This preserves full context fidelity so
 *   the model retains access to raw tool results for follow-up questions.
 * - [AGGRESSIVE]: Digested tool groups are **not protected** — they can be freely
 *   trimmed to maximize room for recent messages. The model relies solely on its
 *   earlier summary of the tool results.
 *
 * This is the primary behavioral difference between the two modes, and the reason
 * this enum exists.
 */
enum class TrimAggressiveness {
    /** Protect digested tool groups — preserve full tool-call context. */
    CONSERVATIVE,

    /** Allow discarding digested tool groups — maximize recency. */
    AGGRESSIVE
}
