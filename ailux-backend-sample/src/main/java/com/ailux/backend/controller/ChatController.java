package com.ailux.backend.controller;

import com.ailux.backend.dto.ChatRequest;
import com.ailux.backend.service.ChatService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Core chat endpoint. Handles SSE streaming for chat completions.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatCompletions(@RequestBody ChatRequest request,
                                      HttpServletResponse response) {
        // Set response headers for parser negotiation
        response.setHeader("X-Ailux-Parser", "openai");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        return chatService.handleChat(request);
    }
}
