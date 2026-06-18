package com.ailux.backend.service;

import com.ailux.backend.dto.ChatRequest;
import com.ailux.backend.model.QuotaUsage;
import com.ailux.backend.model.User;
import com.ailux.backend.repository.QuotaUsageRepository;
import com.ailux.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the chat flow: context management, LLM proxy, and quota tracking.
 *
 * <p>Provider/model resolution and access checks happen earlier in
 * {@link com.ailux.backend.controller.ChatController} via {@link ModelResolver};
 * this service receives the already-validated values, so it no longer emits an
 * in-stream {@code model_not_available} error.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ContextService contextService;
    private final LlmProxyService llmProxyService;
    private final UserRepository userRepository;
    private final QuotaUsageRepository quotaUsageRepository;

    /**
     * Bounded executor for SSE streaming work.
     *
     * <p>Replaces {@code Executors.newCachedThreadPool()} — which is unbounded and,
     * combined with long-lived SSE connections (120s timeout), can spawn threads
     * without limit until OOM under load. Here we cap the pool and the queue, and
     * use {@link ThreadPoolExecutor.AbortPolicy} so overload is surfaced as a clean
     * "service busy" response rather than silent thread exhaustion.
     */
    private final ThreadPoolExecutor executor;

    public ChatService(ContextService contextService,
                       LlmProxyService llmProxyService,
                       UserRepository userRepository,
                       QuotaUsageRepository quotaUsageRepository) {
        this.contextService = contextService;
        this.llmProxyService = llmProxyService;
        this.userRepository = userRepository;
        this.quotaUsageRepository = quotaUsageRepository;

        int cpu = Runtime.getRuntime().availableProcessors();
        int corePoolSize = Math.max(4, cpu * 2);
        int maxPoolSize = Math.max(8, cpu * 4);
        AtomicInteger threadSeq = new AtomicInteger(1);
        this.executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "ailux-chat-" + threadSeq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * Handle a chat completion request. Returns an SseEmitter that streams the response.
     *
     * @param request  the parsed chat request
     * @param userId   the authenticated user id (resolved in the controller)
     * @param resolved the validated provider/model pair
     */
    public SseEmitter handleChat(ChatRequest request, String userId, ModelResolver.Resolved resolved) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 minute timeout
        AtomicBoolean cancelled = new AtomicBoolean(false);

        // --- Cancel signal propagation (billing boundary) ---
        // When the mobile client disconnects (e.g. user taps "Stop"), Spring triggers
        // onCompletion/onError. The `cancelled` flag propagates to LlmProxyService which
        // then calls `call.cancel()` on the upstream LLM HTTP request, implementing the
        // "client-disconnect = abort upstream" pattern. This minimizes post-cancel billing
        // but cannot guarantee zero extra tokens (see LlmProxyService Javadoc).
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(e -> cancelled.set(true));

        try {
            executor.execute(() -> {
                try {
                    processChat(emitter, request, userId, resolved, cancelled);
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
        } catch (RejectedExecutionException rejected) {
            // Pool + queue saturated — fail fast with a clear, retryable signal.
            log.warn("Chat executor saturated, rejecting request for user {}", userId);
            try {
                emitter.send(SseEmitter.event().data(
                        "{\"error\":\"service_busy\",\"message\":\"Server is busy, please retry shortly\"}"));
                emitter.complete();
            } catch (Exception e) {
                // ignore
            }
        }

        return emitter;
    }

    private void processChat(SseEmitter emitter, ChatRequest request, String userId,
                             ModelResolver.Resolved resolved, AtomicBoolean cancelled) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }

        String provider = resolved.getProvider();
        String model = resolved.getModel();

        // Determine context mode
        String contextMode = request.getContextMode() != null ? request.getContextMode() : user.getContextMode();

        // Build messages
        List<Map<String, Object>> messages;
        String sessionId = request.getSessionId();

        if ("server".equals(contextMode)) {
            // Server context mode: load history + append new messages
            contextService.getOrCreateSession(sessionId, userId);

            // Save incoming user messages
            for (ChatRequest.MessageDTO msg : request.getMessages()) {
                contextService.saveMessage(sessionId, msg.getRole(), msg.getContent(),
                        null, msg.getToolCallId());
            }

            messages = contextService.buildMessages(sessionId, request.getMessages());
        } else {
            // Client context mode: use messages as-is
            messages = toRawMessages(request.getMessages());
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

    /**
     * Flatten {@link ChatRequest.MessageDTO} into the OpenAI-style raw {@code Map}
     * shape expected by {@link LlmProxyService#streamChat} / {@code generateChat}.
     *
     * <p>Public so the {@code /generate} non-streaming controller path can reuse the
     * same conversion as the streaming path — keeps the two paths in lockstep.
     */
    public static List<Map<String, Object>> toRawMessages(List<ChatRequest.MessageDTO> dtos) {
        if (dtos == null) return Collections.emptyList();
        List<Map<String, Object>> out = new ArrayList<>(dtos.size());
        for (ChatRequest.MessageDTO msg : dtos) {
            Map<String, Object> msgMap = new LinkedHashMap<>();
            msgMap.put("role", msg.getRole());
            if (msg.getContent() != null) msgMap.put("content", msg.getContent());
            if (msg.getToolCalls() != null) msgMap.put("tool_calls", msg.getToolCalls());
            if (msg.getToolCallId() != null) msgMap.put("tool_call_id", msg.getToolCallId());
            out.add(msgMap);
        }
        return out;
    }
}
