package com.ailux.core.stream

import com.ailux.core.event.LLMEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Polling interval for the stall watcher coroutine. */
private const val WATCH_INTERVAL_MILLIS = 1_000L

/**
 * Provider-agnostic stall detection Flow operator.
 *
 * Wraps an existing [Flow] of [LLMEvent] and monitors for stream stalls:
 * - Progress events (Token / Reasoning / ToolCallDelta) reset the timer.
 * - Connected event marks "connection established"; TTFT is measured from this point.
 * - If no Connected is received, TTFT is measured from the start of collection (graceful degradation).
 * - When idle time exceeds the configured threshold, emits a one-shot [LLMEvent.StallDetected]
 *   (edge-triggered) and invokes [onStateUpdate] with stalled=true.
 * - When the next progress event arrives, invokes [onStateUpdate] with stalled=false
 *   (no separate StallResolved event).
 *
 * Non-terminal: this operator never ends the flow or throws. Terminal timeout is
 * handled by OkHttp's readTimeout as a separate safety net.
 *
 * @param config       stream health configuration (timeouts). If both are 0, passthrough.
 * @param onStateUpdate callback for the client to overlay stall state onto task StateFlow.
 * @return a new Flow that transparently passes all upstream events plus injected StallDetected.
 */
fun Flow<LLMEvent>.stallDetection(
    config: StreamConfig,
    onStateUpdate: (StallState) -> Unit
): Flow<LLMEvent> = channelFlow {
    if (config.firstTokenTimeoutMillis == 0L && config.stallTimeoutMillis == 0L) {
        collect { send(it) }
        return@channelFlow
    }

    var lastProgress = System.currentTimeMillis()
    var firstTokenSeen = false
    var stalled = false

    val watcher = launch {
        while (isActive) {
            delay(WATCH_INTERVAL_MILLIS)
            val idle = System.currentTimeMillis() - lastProgress
            val threshold = if (firstTokenSeen) {
                config.stallTimeoutMillis
            } else {
                config.firstTokenTimeoutMillis
            }
            if (threshold > 0 && idle >= threshold && !stalled) {
                stalled = true
                val phase = if (firstTokenSeen) StallPhase.INTER_TOKEN
                else StallPhase.WAITING_FIRST_TOKEN
                send(LLMEvent.StallDetected(phase, idle))
                onStateUpdate(StallState(stalled = true, idleMillis = idle, phase = phase))
            }
        }
    }

    collect { event ->
        when (event) {
            is LLMEvent.Connected -> lastProgress = System.currentTimeMillis()

            is LLMEvent.Token, is LLMEvent.Reasoning, is LLMEvent.ToolCallDelta -> {
                lastProgress = System.currentTimeMillis()
                firstTokenSeen = true
                if (stalled) {
                    stalled = false
                    onStateUpdate(StallState(stalled = false))
                }
            }

            else -> {}
        }
        send(event)
    }
    watcher.cancel()
}
