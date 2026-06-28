package com.ailux.chatdemo

import android.content.Context
import com.ailux.chatdemo.drawer.ConversationItem
import com.ailux.chatdemo.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Multi-conversation persistence store.
 *
 * Manages a list of conversations stored as JSON files in the app's internal storage.
 * Each conversation has:
 * - A metadata entry in the index file (id, title, lastMessage, updatedAt)
 * - A separate messages file (conversation-{id}.json)
 *
 * Lifecycle:
 * - On new conversation: save current messages to the active conversation record
 * - On restore: load the selected conversation's messages
 * - On delete: remove both the index entry and the messages file
 */
internal class ConversationStore(context: Context) {

    private val appContext = context.applicationContext
    private val conversationsDir: File
        get() = File(appContext.filesDir, CONVERSATIONS_DIR).also { it.mkdirs() }
    private val indexFile: File
        get() = File(conversationsDir, INDEX_FILE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()

    /** Current active conversation ID. Null means no active conversation yet. */
    var activeConversationId: String? = null

    /**
     * Save current messages to the active conversation.
     * If no active conversation exists, creates a new one.
     * Returns the conversation ID.
     */
    suspend fun saveCurrentConversation(messages: List<ChatMessage>): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val id = activeConversationId ?: generateId()
            activeConversationId = id

            // Derive title from first user message
            val title = messages.firstOrNull { it.role == "user" }?.content?.take(40)
                ?: Strings.newChat
            val lastMsg = messages.lastOrNull()?.content?.take(60) ?: ""

            // Save messages file
            val messagesFile = File(conversationsDir, "conversation-$id.json")
            val payload = json.encodeToString(ConversationMessages(messages))
            writeAtomically(messagesFile, payload)

            // Update index
            val index = loadIndexInternal().toMutableList()
            val existingIdx = index.indexOfFirst { it.id == id }
            val record = ConversationRecord(
                id = id,
                title = title,
                lastMessage = lastMsg,
                updatedAt = System.currentTimeMillis(),
            )
            if (existingIdx >= 0) {
                index[existingIdx] = record
            } else {
                index.add(0, record)
            }
            writeAtomically(indexFile, json.encodeToString(ConversationIndex(index)))

            id
        }
    }

    /**
     * Load the list of all conversations (metadata only, no messages).
     * Sorted by updatedAt descending (most recent first).
     */
    suspend fun listConversations(): List<ConversationItem> = mutex.withLock {
        withContext(Dispatchers.IO) {
            loadIndexInternal().map { record ->
                ConversationItem(
                    id = record.id,
                    title = record.title,
                    lastMessage = record.lastMessage,
                    timestamp = record.updatedAt,
                    isActive = record.id == activeConversationId,
                )
            }
        }
    }

    /**
     * Load messages for a specific conversation.
     * Returns empty list if the conversation file doesn't exist.
     */
    suspend fun loadMessages(conversationId: String): List<ChatMessage> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = File(conversationsDir, "conversation-$conversationId.json")
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                json.decodeFromString<ConversationMessages>(file.readText()).messages
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Delete a conversation by ID. Removes both the index entry and messages file.
     */
    suspend fun deleteConversation(conversationId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Remove from index
            val index = loadIndexInternal().toMutableList()
            index.removeAll { it.id == conversationId }
            writeAtomically(indexFile, json.encodeToString(ConversationIndex(index)))

            // Delete messages file
            val file = File(conversationsDir, "conversation-$conversationId.json")
            file.delete()

            // If deleting the active conversation, clear active ID
            if (activeConversationId == conversationId) {
                activeConversationId = null
            }
        }
    }

    /**
     * Start a new conversation. Saves current messages first (if any),
     * then resets the active conversation ID.
     */
    suspend fun startNewConversation(currentMessages: List<ChatMessage>): String? {
        // Save existing conversation if it has messages
        val savedId = if (currentMessages.isNotEmpty()) {
            saveCurrentConversation(currentMessages)
        } else null

        // Reset active
        activeConversationId = null
        return savedId
    }

    /**
     * Switch to an existing conversation. Returns its messages.
     * Saves current messages before switching (creates a new conversation record
     * if activeConversationId is null).
     */
    suspend fun switchToConversation(
        conversationId: String,
        currentMessages: List<ChatMessage>,
    ): List<ChatMessage> {
        // Save current conversation first (if it has messages worth saving)
        if (currentMessages.isNotEmpty()) {
            saveCurrentConversation(currentMessages)
        }
        activeConversationId = conversationId
        return loadMessages(conversationId)
    }

    // ── Internal ──

    private fun loadIndexInternal(): List<ConversationRecord> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<ConversationIndex>(indexFile.readText()).conversations
        }.getOrDefault(emptyList())
    }

    private fun writeAtomically(target: File, payload: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(payload)
        if (!tmp.renameTo(target)) {
            target.writeText(payload)
            tmp.delete()
        }
    }

    private fun generateId(): String = UUID.randomUUID().toString().take(8)

    @Serializable
    private data class ConversationRecord(
        val id: String,
        val title: String,
        val lastMessage: String = "",
        val updatedAt: Long = 0L,
    )

    @Serializable
    private data class ConversationIndex(
        val conversations: List<ConversationRecord> = emptyList(),
    )

    @Serializable
    private data class ConversationMessages(
        val messages: List<ChatMessage> = emptyList(),
    )

    private companion object {
        private const val CONVERSATIONS_DIR = "conversations"
        private const val INDEX_FILE = "index.json"
    }
}
