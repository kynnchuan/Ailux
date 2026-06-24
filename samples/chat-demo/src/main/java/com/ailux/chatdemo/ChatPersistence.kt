package com.ailux.chatdemo

import android.content.Context
import com.ailux.chatdemo.model.ChatMessage
import com.ailux.core.session.SessionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disk-backed persistence for the demo's single-session chat.
 *
 * Two artefacts are stored side by side under the app's `filesDir`:
 *
 * - `chat-session.json` — the SDK's [SessionSnapshot], the **authoritative**
 *   record of the conversation (system instruction + full message history +
 *   sampler defaults + schemaVersion). On app restart, this is what feeds
 *   `AiluxClient.restoreSession(...)` so the next turn continues with the
 *   same logical state. Note that the native KV cache is intentionally NOT
 *   in the snapshot — by design it is lazily rebuilt from `messages` on the
 *   first `streamGenerate` call. Slower first response, fully expected.
 *
 * - `chat-ui.json` — the UI message list ([UiHistory]) used purely as a
 *   render cache so chat bubbles can be painted immediately on startup
 *   without waiting for snapshot decode + session restore. Only the
 *   snapshot is the source of truth; this file is best-effort.
 *
 * Both artefacts are written together at the **end of every completed turn**
 * (post stream-end, after the SDK has folded the assistant reply into the
 * session history). They are read together at ViewModel init. They are
 * cleared together on `newConversation()`.
 *
 * Failures (corrupt file, unknown schema, IO error) silently fall back to
 * "no history" — losing chat history is annoying but never fatal.
 */
internal class ChatPersistence(context: Context) {

    private val appContext = context.applicationContext

    private val snapshotFile: File
        get() = File(appContext.filesDir, SNAPSHOT_FILE)

    private val uiFile: File
        get() = File(appContext.filesDir, UI_FILE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Save snapshot + UI history together. Called at the natural end of a
     * turn — when the assistant stream is done and the Session has folded
     * the reply into its own history. Must run off the main thread.
     */
    suspend fun save(snapshot: SessionSnapshot, uiMessages: List<ChatMessage>) {
        withContext(Dispatchers.IO) {
            runCatching {
                writeAtomically(snapshotFile, json.encodeToString(snapshot))
                writeAtomically(uiFile, json.encodeToString(UiHistory(uiMessages)))
            }
        }
    }

    /**
     * Load snapshot + UI history. Returns `null` when:
     * - either file is missing,
     * - either file is unreadable,
     * - JSON is malformed,
     * - or the snapshot's `schemaVersion` doesn't match the current build.
     *
     * Mismatched / corrupt artefacts are deleted so we don't trip over them
     * on every subsequent launch.
     */
    suspend fun load(): Restored? = withContext(Dispatchers.IO) {
        val snapText = runCatching { snapshotFile.takeIf { it.exists() }?.readText() }
            .getOrNull() ?: return@withContext null
        val uiText = runCatching { uiFile.takeIf { it.exists() }?.readText() }
            .getOrNull()

        val snapshot = runCatching { json.decodeFromString<SessionSnapshot>(snapText) }
            .getOrElse {
                clear()
                return@withContext null
            }

        if (snapshot.schemaVersion != SessionSnapshot.SCHEMA_VERSION) {
            // Future / older format we don't understand. Drop and start clean
            // rather than risk feeding it back into restoreSession().
            clear()
            return@withContext null
        }

        val ui = uiText
            ?.let { runCatching { json.decodeFromString<UiHistory>(it).messages }.getOrNull() }
            .orEmpty()

        Restored(snapshot, ui)
    }

    /** Wipe both artefacts. Called by newConversation() and on irrecoverable errors. */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            runCatching { snapshotFile.delete() }
            runCatching { uiFile.delete() }
        }
    }

    /**
     * Atomic write: write to a sibling `.tmp` and rename, so a process kill
     * mid-write can never leave a half-written snapshot on disk.
     */
    private fun writeAtomically(target: File, payload: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(payload)
        if (!tmp.renameTo(target)) {
            // Fallback: if rename fails (rare; e.g. cross-FS), overwrite directly.
            target.writeText(payload)
            tmp.delete()
        }
    }

    /** Logical bundle returned by [load]. */
    data class Restored(
        val snapshot: SessionSnapshot,
        val uiMessages: List<ChatMessage>,
    )

    @Serializable
    private data class UiHistory(val messages: List<ChatMessage>)

    private companion object {
        private const val SNAPSHOT_FILE = "chat-session.json"
        private const val UI_FILE = "chat-ui.json"
    }
}
