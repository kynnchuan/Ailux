package com.ailux.backend.service;

import com.ailux.backend.config.ProviderConfig;
import com.ailux.backend.config.SecurityContext;
import com.ailux.backend.dto.ChatRequest;
import com.ailux.backend.model.QuotaUsage;
import com.ailux.backend.model.Session;
import com.ailux.backend.model.User;
import com.ailux.backend.repository.QuotaUsageRepository;
import com.ailux.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates the chat flow: context management, LLM proxy, and quota tracking.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ContextService contextService;
    private final LlmProxyService llmProxyService;
    private final UserRepository userRepository;
    private final QuotaUsageRepository quotaUsageRepository;
    private final ProviderConfig providerConfig;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ChatService(ContextService contextService,
                       LlmProxyService llmProxyService,
                       UserRepository userRepository,
                       QuotaUsageRepository quotaUsageRepository,
                       ProviderConfig providerConfig) {
        this.contextService = contextService;
        this.llmProxyService = llmProxyService;
        this.userRepository = userRepository;
        this.quotaUsageRepository = quotaUsageRepository;
        this.providerConfig = providerConfig;
    }

    /**
     * Handle a chat completion request. Returns an SseEmitter that streams the response.
     */
    public SseEmitter handleChat(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 minute timeout
        AtomicBoolean cancelled = new AtomicBoolean(false);

        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(e -> cancelled.set(true));

        String userId = SecurityContext.getUserId();

        executor.execute(() -> {
            try {
                processChat(emitter, request, userId, cancelled);
            } catch (Exception e) {
                log.error("Error processing chat", e);
                try {
                    emitter.send(SseEmitter.event().data(
                            "{\"error\":\"Internal server error: " + e.getMessage() + "\"}"));
                } catch (Exception ex) {
                    // ignore
                }
            } finally {
                if (!cancelled.get()) {
                    try {
                        emitter.complete();
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        });

        return emitter;
    }

    private void processChat(SseEmitter emitter, ChatRequest request, String userId, AtomicBoolean cancelled) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }

        // Resolve provider and model
        String provider = request.getProvider() != null ? request.getProvider() : user.getDefaultProvider();
        String model = request.getModel();
        if (model == null || model.isEmpty()) {
            ProviderConfig.ProviderProperties props = providerConfig.getProvider(provider);
            model = props != null ? props.getDefaultModel() : user.getDefaultModel();
        }

        // Check model access
        if (!user.hasModelAccess(model)) {
            try {
                emitter.send(SseEmitter.event().data(
                        "{\"error\":\"model_not_available\",\"message\":\"You don't have access to model: " + model + "\"}"));
                emitter.complete();
            } catch (Exception e) {
                // ignore
            }
            return;
        }

        // Determine context mode
        String contextMode = request.getContextMode() != null ? request.getContextMode() : user.getContextMode();

        // Build messages
        List<Map<String, Object>> messages;
        String sessionId = request.getSessionId();

        if ("server".equals(contextMode)) {
            // Server context mode: load history + append new messages
            Session session = contextService.getOrCreateSession(sessionId, userId);

            // Save incoming user messages
            for (ChatRequest.MessageDTO msg : request.getMessages()) {
                contextService.saveMessage(sessionId, msg.getRole(), msg.getContent(),
                        null, msg.getToolCallId());
            }

            messages = contextService.buildMessages(sessionId, request.getMessages());
        } else {
            // Client context mode: use messages as-is
            messages = new ArrayList<>();
            for (ChatRequest.MessageDTO msg : request.getMessages()) {
                Map<String, Object> msgMap = new LinkedHashMap<>();
                msgMap.put("role", msg.getRole());
                if (msg.getContent() != null) msgMap.put("content", msg.getContent());
                if (msg.getToolCalls() != null) msgMap.put("tool_calls", msg.getToolCalls());
                if (msg.getToolCallId() != null) msgMap.put("tool_call_id", msg.getToolCallId());
                messages.add(msgMap);
            }
        }

        // Merge tools: server tools + client tools
        List<Map<String, Object>> allTools = new ArrayList<>(llmProxyService.getServerToolDefinitions());
        if (request.getTools() != null) {
            allTools.addAll(request.getTools());
        }

        // Increment request count
        incrementRequestCount(userId);

        // Call LLM
        LlmProxyService.StreamResult result = llmProxyService.streamChat(
                emitter, provider, model, messages, allTools, userId, cancelled);

        // Save assistant response to history (server mode only)
        if ("server".equals(contextMode) && result != null && result.getContent() != null) {
            contextService.saveMessage(sessionId, "assistant", result.getContent(),
                    result.getToolCallsJson(), null);
        }
    }

    private void incrementRequestCount(String userId) {
        LocalDate today = LocalDate.now();
        QuotaUsage usage = quotaUsageRepository.findByUserIdAndDate(userId, today)
                .orElseGet(() -> {
                    QuotaUsage u = new QuotaUsage();
                    u.setUserId(userId);
                    u.setDate(today);
                    u.setRequestCount(0);
                    u.setTokenCount(0);
                    return u;
                });
        usage.setRequestCount(usage.getRequestCount() + 1);
        quotaUsageRepository.save(usage);
    }
}
