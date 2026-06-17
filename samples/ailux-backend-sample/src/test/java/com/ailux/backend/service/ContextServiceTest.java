package com.ailux.backend.service;

import com.ailux.backend.config.ProviderConfig;
import com.ailux.backend.dto.ChatRequest;
import com.ailux.backend.model.ChatMessage;
import com.ailux.backend.model.Session;
import com.ailux.backend.repository.ChatMessageRepository;
import com.ailux.backend.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ContextService - Session and context management")
class ContextServiceTest {

    private ContextService contextService;
    private SessionRepository sessionRepository;
    private ChatMessageRepository chatMessageRepository;
    private ProviderConfig providerConfig;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        providerConfig = new ProviderConfig();
        ProviderConfig.ContextConfig ctxConfig = new ProviderConfig.ContextConfig();
        ctxConfig.setMaxMessages(5);
        providerConfig.setContext(ctxConfig);

        contextService = new ContextService(sessionRepository, chatMessageRepository,
                providerConfig, new ObjectMapper());
    }

    @Test
    @DisplayName("getOrCreateSession returns existing session")
    void getExistingSession() {
        Session existing = new Session();
        existing.setId("session-1");
        existing.setUserId("user-1");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(existing));

        Session result = contextService.getOrCreateSession("session-1", "user-1");

        assertEquals("session-1", result.getId());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("getOrCreateSession creates new session when not found")
    void createNewSession() {
        when(sessionRepository.findById("session-new")).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Session result = contextService.getOrCreateSession("session-new", "user-1");

        assertEquals("session-new", result.getId());
        assertEquals("user-1", result.getUserId());
        verify(sessionRepository).save(any());
    }

    @Test
    @DisplayName("buildMessages applies sliding window trim")
    void buildMessagesWithTrim() {
        // History has 4 messages, new has 2 → total 6 > maxMessages(5) → trim
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ChatMessage msg = new ChatMessage();
            msg.setRole(i == 0 ? "system" : "user");
            msg.setContent("msg-" + i);
            history.add(msg);
        }
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc("s1")).thenReturn(history);

        List<ChatRequest.MessageDTO> newMessages = new ArrayList<>();
        ChatRequest.MessageDTO dto1 = new ChatRequest.MessageDTO();
        dto1.setRole("user");
        dto1.setContent("new-1");
        ChatRequest.MessageDTO dto2 = new ChatRequest.MessageDTO();
        dto2.setRole("user");
        dto2.setContent("new-2");
        newMessages.add(dto1);
        newMessages.add(dto2);

        List<Map<String, Object>> result = contextService.buildMessages("s1", newMessages);

        // Should be trimmed to maxMessages(5), preserving system message
        assertTrue(result.size() <= 5);
        // System message should be preserved at first position
        assertEquals("system", result.get(0).get("role"));
    }

    @Test
    @DisplayName("buildMessages does not trim when under limit")
    void buildMessagesNoTrimNeeded() {
        List<ChatMessage> history = new ArrayList<>();
        ChatMessage msg = new ChatMessage();
        msg.setRole("user");
        msg.setContent("hello");
        history.add(msg);
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc("s1")).thenReturn(history);

        List<ChatRequest.MessageDTO> newMessages = new ArrayList<>();
        ChatRequest.MessageDTO dto = new ChatRequest.MessageDTO();
        dto.setRole("user");
        dto.setContent("world");
        newMessages.add(dto);

        List<Map<String, Object>> result = contextService.buildMessages("s1", newMessages);

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("saveMessage persists to repository")
    void saveMessagePersists() {
        contextService.saveMessage("s1", "assistant", "Hi there!", null, null);

        verify(chatMessageRepository).save(argThat(msg ->
                "s1".equals(msg.getSessionId()) &&
                "assistant".equals(msg.getRole()) &&
                "Hi there!".equals(msg.getContent())
        ));
    }
}
