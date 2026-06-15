package com.ailux.backend.controller;

import com.ailux.backend.config.SecurityContext;
import com.ailux.backend.dto.ChatRequest;
import com.ailux.backend.service.ChatService;
import com.ailux.backend.service.ModelResolver;
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
    private final ModelResolver modelResolver;

    public ChatController(ChatService chatService, ModelResolver modelResolver) {
        this.chatService = chatService;
        this.modelResolver = modelResolver;
    }

    @PostMapping(value = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatCompletions(@RequestBody ChatRequest request,
                                      HttpServletResponse response) {
        // Validate provider/model access BEFORE committing the SSE response.
        // On failure this throws and GlobalExceptionHandler returns HTTP 403/401 —
        // the body is already deserialized here, so we can read request.model
        // (which a pure Servlet Filter cannot do without consuming the stream).
        String userId = SecurityContext.getUserId();
        ModelResolver.Resolved resolved = modelResolver.resolveAndValidate(request, userId);

        // Set response headers for parser negotiation and streaming transport
        response.setHeader("X-Ailux-Parser", "openai");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        // Disable Nginx/reverse-proxy buffering to ensure token-by-token delivery.
        // Without this header, Nginx buffers SSE chunks and delivers them in bursts,
        // breaking the real-time streaming experience for the client.
        response.setHeader("X-Accel-Buffering", "no");

        return chatService.handleChat(request, userId, resolved);
    }
}
