package com.ailux.backend.service;

import com.ailux.backend.config.ProviderConfig;
import com.ailux.backend.dto.ChatRequest;
import com.ailux.backend.model.ChatMessage;
import com.ailux.backend.model.Session;
import com.ailux.backend.repository.ChatMessageRepository;
import com.ailux.backend.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages conversation sessions and context (message history).
 */
@Service
public class ContextService {

    private final SessionRepository sessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ProviderConfig providerConfig;
    private final ObjectMapper objectMapper;

    public ContextService(SessionRepository sessionRepository,
                          ChatMessageRepository chatMessageRepository,
                          ProviderConfig providerConfig,
                          ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.providerConfig = providerConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * Get or create a session.
     */
    public Session getOrCreateSession(String sessionId, String userId) {
        return sessionRepository.findById(sessionId).orElseGet(() -> {
            Session session = new Session();
            session.setId(sessionId);
            session.setUserId(userId);
            return sessionRepository.save(session);
        });
    }

    /**
     * Build the full message list for LLM call in server context mode.
     * Loads history from DB + appends new messages, then applies sliding window.
     */
    public List<Map<String, Object>> buildMessages(String sessionId, List<ChatRequest.MessageDTO> newMessages) {
        // Load existing history
        List<ChatMessage> history = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // Convert history to message maps
        List<Map<String, Object>> allMessages = new ArrayList<>();
        for (ChatMessage msg : history) {
            allMessages.add(toMessageMap(msg));
        }

        // Append new messages
        for (ChatRequest.MessageDTO dto : newMessages) {
            Map<String, Object> msgMap = new LinkedHashMap<>();
            msgMap.put("role", dto.getRole());
            if (dto.getContent() != null) {
                msgMap.put("content", dto.getContent());
            }
            if (dto.getToolCalls() != null) {
                msgMap.put("tool_calls", dto.getToolCalls());
            }
            if (dto.getToolCallId() != null) {
                msgMap.put("tool_call_id", dto.getToolCallId());
            }
            allMessages.add(msgMap);
        }

        // Apply sliding window
        return trimMessages(allMessages, providerConfig.getContext().getMaxMessages());
    }

    /**
     * Save a message to the session history.
     */
    public void saveMessage(String sessionId, String role, String content, String toolCalls, String toolCallId) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setToolCalls(toolCalls);
        msg.setToolCallId(toolCallId);
        chatMessageRepository.save(msg);
    }

    /**
     * Sliding window trim: keep the most recent N messages.
     * Always preserve the system message if present.
     */
    private List<Map<String, Object>> trimMessages(List<Map<String, Object>> messages, int maxMessages) {
        if (messages.size() <= maxMessages) {
            return messages;
        }

        Map<String, Object> systemMsg = null;
        if (!messages.isEmpty() && "system".equals(messages.get(0).get("role"))) {
            systemMsg = messages.get(0);
        }

        List<Map<String, Object>> trimmed;
        if (systemMsg != null) {
            trimmed = new ArrayList<>();
            trimmed.add(systemMsg);
            int startIdx = messages.size() - maxMessages + 1;
            trimmed.addAll(messages.subList(Math.max(1, startIdx), messages.size()));
        } else {
            int startIdx = messages.size() - maxMessages;
            trimmed = new ArrayList<>(messages.subList(startIdx, messages.size()));
        }
        return trimmed;
    }

    private Map<String, Object> toMessageMap(ChatMessage msg) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", msg.getRole());
        if (msg.getContent() != null) {
            map.put("content", msg.getContent());
        }
        if (msg.getToolCalls() != null && !msg.getToolCalls().isBlank()) {
            try {
                map.put("tool_calls", objectMapper.readValue(msg.getToolCalls(), List.class));
            } catch (Exception e) {
                // ignore parse error
            }
        }
        if (msg.getToolCallId() != null) {
            map.put("tool_call_id", msg.getToolCallId());
        }
        return map;
    }
}
