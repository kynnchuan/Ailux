package com.ailux.backend.service;

import com.ailux.backend.config.ProviderConfig;
import com.ailux.backend.config.SecurityContext;
import com.ailux.backend.dto.ChatRequest;
import com.ailux.backend.model.QuotaUsage;
import com.ailux.backend.model.User;
import com.ailux.backend.repository.QuotaUsageRepository;
import com.ailux.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
    private ProviderConfig providerConfig;

    @BeforeEach
    void setUp() {
        contextService = mock(ContextService.class);
        llmProxyService = mock(LlmProxyService.class);
        userRepository = mock(UserRepository.class);
        quotaUsageRepository = mock(QuotaUsageRepository.class);
        providerConfig = new ProviderConfig();

        // Setup provider config
        ProviderConfig.ProviderProperties deepseek = new ProviderConfig.ProviderProperties();
        deepseek.setBaseUrl("https://api.deepseek.com");
        deepseek.setApiKey("test-key");
        deepseek.setDefaultModel("deepseek-chat");
        Map<String, ProviderConfig.ProviderProperties> providers = new HashMap<>();
        providers.put("deepseek", deepseek);
        providerConfig.setProviders(providers);

        ProviderConfig.ContextConfig ctxConfig = new ProviderConfig.ContextConfig();
        ctxConfig.setMaxMessages(20);
        providerConfig.setContext(ctxConfig);

        chatService = new ChatService(contextService, llmProxyService,
                userRepository, quotaUsageRepository, providerConfig);
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("handleChat returns a non-null SseEmitter")
    void handleChatReturnsEmitter() {
        SecurityContext.setUserId("user-1");

        User user = createTestUser("user-1", "deepseek", "deepseek-chat");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(quotaUsageRepository.findByUserIdAndDate(eq("user-1"), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(llmProxyService.getServerToolDefinitions()).thenReturn(new ArrayList<>());
        when(llmProxyService.streamChat(any(), anyString(), anyString(), anyList(), anyList(), anyString(), any()))
                .thenReturn(new LlmProxyService.StreamResult("Hello!", null, "stop"));

        ChatRequest request = createRequest("user", "Hi", "client");

        SseEmitter emitter = chatService.handleChat(request);

        assertNotNull(emitter);
    }

    @Test
    @DisplayName("handleChat returns emitter even when user not found (graceful handling)")
    void handleChatUserNotFound() {
        SecurityContext.setUserId("unknown-user");
        when(userRepository.findById("unknown-user")).thenReturn(Optional.empty());

        ChatRequest request = createRequest("user", "Hi", "client");

        SseEmitter emitter = chatService.handleChat(request);
        // Should still return an emitter (error will be sent via SSE)
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
