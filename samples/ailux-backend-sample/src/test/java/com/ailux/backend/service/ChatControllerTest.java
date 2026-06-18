package com.ailux.backend.controller;

import com.ailux.backend.dto.ChatRequest;
import com.ailux.backend.service.ChatService;
import com.ailux.backend.service.LlmProxyService;
import com.ailux.backend.service.ModelResolver;
import com.ailux.backend.web.ModelAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ChatController - SSE streaming endpoint")
class ChatControllerTest {

    private ChatController chatController;
    private ChatService chatService;
    private LlmProxyService llmProxyService;
    private ModelResolver modelResolver;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        llmProxyService = mock(LlmProxyService.class);
        modelResolver = mock(ModelResolver.class);
        chatController = new ChatController(chatService, llmProxyService, modelResolver);
    }

    @Test
    @DisplayName("chatCompletions validates model, returns SseEmitter and sets parser header")
    void chatCompletionsReturnsEmitter() {
        SseEmitter expectedEmitter = new SseEmitter();
        ModelResolver.Resolved resolved = new ModelResolver.Resolved("deepseek", "deepseek-v4-flash");
        when(modelResolver.resolveAndValidate(any(), any())).thenReturn(resolved);
        when(chatService.handleChat(any(), any(), eq(resolved))).thenReturn(expectedEmitter);

        ChatRequest request = new ChatRequest();
        ChatRequest.MessageDTO msg = new ChatRequest.MessageDTO();
        msg.setRole("user");
        msg.setContent("Hello");
        request.setMessages(List.of(msg));

        MockHttpServletResponse response = new MockHttpServletResponse();

        SseEmitter result = chatController.chatCompletions(request, response);

        assertSame(expectedEmitter, result);
        assertEquals("openai", response.getHeader("X-Ailux-Parser"));
        assertEquals("no-cache", response.getHeader("Cache-Control"));
        assertEquals("keep-alive", response.getHeader("Connection"));
    }

    @Test
    @DisplayName("chatCompletions delegates to ChatService with resolved model")
    void delegatesToChatService() {
        SseEmitter emitter = new SseEmitter();
        ModelResolver.Resolved resolved = new ModelResolver.Resolved("deepseek", "deepseek-v4-flash");
        when(modelResolver.resolveAndValidate(any(), any())).thenReturn(resolved);
        when(chatService.handleChat(any(), any(), eq(resolved))).thenReturn(emitter);

        ChatRequest request = new ChatRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        chatController.chatCompletions(request, response);

        verify(chatService).handleChat(eq(request), any(), eq(resolved));
    }

    @Test
    @DisplayName("Disallowed model throws before any SSE response is committed")
    void disallowedModelThrows() {
        when(modelResolver.resolveAndValidate(any(), any()))
                .thenThrow(new ModelAccessException("gpt-4o"));

        ChatRequest request = new ChatRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ModelAccessException.class,
                () -> chatController.chatCompletions(request, response));
        verifyNoInteractions(chatService);
    }
}
