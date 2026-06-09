package com.ailux.backend.controller;

import com.ailux.backend.dto.ChatRequest;
import com.ailux.backend.service.ChatService;
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

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        chatController = new ChatController(chatService);
    }

    @Test
    @DisplayName("chatCompletions returns SseEmitter and sets parser header")
    void chatCompletionsReturnsEmitter() {
        SseEmitter expectedEmitter = new SseEmitter();
        when(chatService.handleChat(any())).thenReturn(expectedEmitter);

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
    @DisplayName("chatCompletions delegates to ChatService")
    void delegatesToChatService() {
        SseEmitter emitter = new SseEmitter();
        when(chatService.handleChat(any())).thenReturn(emitter);

        ChatRequest request = new ChatRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        chatController.chatCompletions(request, response);

        verify(chatService).handleChat(request);
    }
}
