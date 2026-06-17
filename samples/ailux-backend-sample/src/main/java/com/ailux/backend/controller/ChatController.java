package com.ailux.backend.controller;

import com.ailux.backend.config.SecurityContext;
import com.ailux.backend.dto.ChatRequest;
import com.ailux.backend.service.ChatService;
import com.ailux.backend.service.LlmProxyService;
import com.ailux.backend.service.ModelResolver;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Core chat endpoint. Handles both SSE streaming and non-streaming chat completions.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final LlmProxyService llmProxyService;
    private final ModelResolver modelResolver;

    public ChatController(ChatService chatService,
                          LlmProxyService llmProxyService,
                          ModelResolver modelResolver) {
        this.chatService = chatService;
        this.llmProxyService = llmProxyService;
        this.modelResolver = modelResolver;
    }

    /**
     * Streaming chat completions via SSE.
     */
    @PostMapping(value = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatCompletions(@RequestBody ChatRequest request,
                                      HttpServletResponse response) {
        String userId = SecurityContext.getUserId();
        ModelResolver.Resolved resolved = modelResolver.resolveAndValidate(request, userId);

        // Set response headers for parser negotiation and streaming transport
        response.setHeader("X-Ailux-Parser", "openai");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        // Disable Nginx/reverse-proxy buffering to ensure token-by-token delivery.
        response.setHeader("X-Accel-Buffering", "no");

        return chatService.handleChat(request, userId, resolved);
    }

    /**
     * Non-streaming chat generation (v0.2.6).
     *
     * <p>Returns a standard OpenAI Chat Completions JSON response. The upstream
     * LLM is called with {@code "stream": false}, and the full response is
     * proxied back to the client as-is.
     *
     * <p>This endpoint is the non-streaming counterpart to {@code /completions}.
     * The Ailux SDK's {@code BackendProxyProvider.generate()} calls this path
     * and parses the response via {@code NonStreamResponseParser}.
     */
    @PostMapping(value = "/generate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> chatGenerate(@RequestBody ChatRequest request) {
        String userId = SecurityContext.getUserId();
        ModelResolver.Resolved resolved = modelResolver.resolveAndValidate(request, userId);

        String responseBody = llmProxyService.generateChat(
                resolved.provider(),
                resolved.model(),
                request.getMessages(),
                request.getTools()
        );

        return ResponseEntity.ok()
                .header("X-Ailux-Parser", "openai")
                .body(responseBody);
    }
}
