package com.ailux.core.session

import com.ailux.core.message.Message
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Persistable, **logical** snapshot of a [Session].
 *
 * Captures everything needed to logically reconstruct a session via
 * `LLMProvider.restoreSession(snapshot)`:
 * - the message history (system + user + assistant + tool messages),
 * - the system instruction (also kept as a separate field for fast lookup),
 * - the sampler defaults set when the session was opened,
 * - and an opaque `providerHint` blob.
 *
 * ## What is **not** captured
 *
 * The **native KV cache** is intentionally not part of the snapshot. Reasons:
 * - It is provider-/model-/device-specific and not portable across processes
 *   or app installs.
 * - Even on the same device, KV cache layout is opaque and may change
 *   between LiteRT-LM versions.
 *
 * On restore, the cache is **lazily rebuilt** from [messages] on the first
 * [Session.streamGenerate] call. This makes snapshots safe to persist to disk,
 * sync across devices, or attach to a backup.
 *
 * @property messages           Full conversation history in chronological order.
 * @property systemInstruction  Convenience copy of the system prompt; also
 *                              present as `messages.first()` when set at open time.
 * @property samplerOverrides   Sampler defaults captured at open time.
 * @property providerHint       Opaque provider-specific hint (echoed from [SessionConfig]).
 * @property createdAtEpochMs   When the source session was first opened. Useful
 *                              for ordering and TTL policies.
 * @property snapshotAtEpochMs  When this snapshot was taken.
 * @property schemaVersion      Snapshot schema version, bumped on breaking changes
 *                              to this data class.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SessionSnapshot(
    val messages: List<Message>,
    val systemInstruction: String? = null,
    val samplerOverrides: JsonObject = JsonObject(emptyMap()),
    val providerHint: JsonObject = JsonObject(emptyMap()),
    val createdAtEpochMs: Long,
    val snapshotAtEpochMs: Long,
    /**
     * Always emitted on encode (even when equal to [SCHEMA_VERSION]) so that
     * older readers can detect newer-format snapshots and trigger their
     * backward-compat path. Without [EncodeDefault], kotlinx.serialization
     * would skip the field whenever it equals its default — making the
     * forward-compat marker invisible on the wire.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = SCHEMA_VERSION
) {
    companion object {
        /** Current snapshot schema version. Bump on any breaking change to fields. */
        const val SCHEMA_VERSION: Int = 1
    }
}
