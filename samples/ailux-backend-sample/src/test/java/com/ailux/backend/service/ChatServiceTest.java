package com.ailux.backend.service;

import com.ailux.backend.dto.ChatRequest;
import com.ailux.backend.model.User;
import com.ailux.backend.repository.QuotaUsageRepository;
import com.ailux.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ChatService - Chat orchestration")
class ChatServiceTest {

    private ChatService chatService;
    private ContextService contextService;
    private LlmProxyService llmProxyService;
    private UserRepository userRepository;
    private QuotaUsageRepository quotaUsageRepository;

    @BeforeEach
    void setUp() {
        contextService = mock(ContextService.class);
        llmProxyService = mock(LlmProxyService.class);
        userRepository = mock(UserRepository.class);
        quotaUsageRepository = mock(QuotaUsageRepository.class);

        chatService = new ChatService(contextService, llmProxyService,
                userRepository, quotaUsageRepository);
    }

    @Test
    @DisplayName("handleChat returns a non-null SseEmitter")
    void handleChatReturnsEmitter() {
        User user = createTestUser("user-1", "deepseek", "deepseek-chat");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(quotaUsageRepository.findByUserIdAndDate(eq("user-1"), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(llmProxyService.getServerToolDefinitions()).thenReturn(new ArrayList<>());
        when(llmProxyService.streamChat(any(), anyString(), anyString(), anyList(), anyList(), anyString(), any()))
                .thenReturn(new LlmProxyService.StreamResult("Hello!", null, "stop"));

        ChatRequest request = createRequest("user", "Hi", "client");
        ModelResolver.Resolved resolved = new ModelResolver.Resolved("deepseek", "deepseek-chat");

        SseEmitter emitter = chatService.handleChat(request, "user-1", resolved);

        assertNotNull(emitter);
    }

    @Test
    @DisplayName("handleChat returns emitter even when user not found (graceful handling)")
    void handleChatUserNotFound() {
        when(userRepository.findById("unknown-user")).thenReturn(Optional.empty());

        ChatRequest request = createRequest("user", "Hi", "client");
        ModelResolver.Resolved resolved = new ModelResolver.Resolved("deepseek", "deepseek-chat");

        SseEmitter emitter = chatService.handleChat(request, "unknown-user", resolved);
        // Should still return an emitter (processing no-ops when user missing)
        assertNotNull(emitter);
    }

    private User createTestUser(String id, String provider, String model) {
        User user = new User();
        user.setId(id);
        user.setName("Test User");
        user.setToken("test-token");
        user.setDefaultProvider(provider);
        user.setDefaultModel(model);
        user.setContextMode("client");
        user.setDailyRequestLimit(100);
        user.setAvailableModels("deepseek-chat,gpt-4o");
        return user;
    }

    private ChatRequest createRequest(String role, String content, String contextMode) {
        ChatRequest request = new ChatRequest();
        request.setContextMode(contextMode);
        ChatRequest.MessageDTO msg = new ChatRequest.MessageDTO();
        msg.setRole(role);
        msg.setContent(content);
        request.setMessages(List.of(msg));
        return request;
    }
}
