package com.ailux.core.event

/**
 * Reason why the model stopped generating tokens.
 *
 * Maps vendor-specific finish/stop reasons to a unified SDK-level enum.
 *
 * | SDK enum        | OpenAI `finish_reason`         | Anthropic `stop_reason`        |
 * |-----------------|-------------------------------|-------------------------------|
 * | [COMPLETE]      | `"stop"`                       | `"end_turn"`, `"stop_sequence"` |
 * | [TOOL_CALL]     | `"tool_calls"`, `"function_call"` (deprecated) | `"tool_use"` |
 * | [LENGTH]        | `"length"`                     | `"max_tokens"`                 |
 * | [CONTENT_FILTER]| `"content_filter"`             | `"refusal"`                    |
 * | [ERROR]         | (no direct mapping — inferred) | (no direct mapping — inferred) |
 */
enum class FinishReason {
    /** Model reached a natural stop point or matched a stop sequence. */
    COMPLETE,

    /** Model requested one or more tool/function calls. */
    TOOL_CALL,

    /** Generation stopped because it hit the max token limit. */
    LENGTH,

    /** Content was filtered/refused due to safety policy. */
    CONTENT_FILTER,

    /** An error occurred during generation (SDK-inferred, not from the model). */
    ERROR,
}