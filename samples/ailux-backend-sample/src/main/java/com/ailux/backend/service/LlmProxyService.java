package com.ailux.backend.service;

import com.ailux.backend.config.ProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Proxies requests to LLM providers (OpenAI/DeepSeek) via OkHttp,
 * reads the SSE stream and forwards events to the client via SseEmitter.
 * Handles server-side function calling loops.
 */
@Service
public class LlmProxyService {

    private static final Logger log = LoggerFactory.getLogger(LlmProxyService.class);

    private final OkHttpClient httpClient;
    private final ProviderConfig providerConfig;
    private final ToolRouter toolRouter;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    public LlmProxyService(ProviderConfig providerConfig,
                           ToolRouter toolRouter,
                           ToolExecutor toolExecutor,
                           ObjectMapper objectMapper) {
        this.providerConfig = providerConfig;
        this.toolRouter = toolRouter;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Stream chat completions from LLM to client.
     * Handles server-side FC loops transparently.
     *
     * @param emitter     SSE emitter to send events to client
     * @param provider    LLM provider name (openai/deepseek)
     * @param model       model name
     * @param messages    full message list for LLM
     * @param tools       tool definitions (server + client tools)
     * @param userId      current user ID
     * @param cancelled   cancellation flag
     * @return accumulated assistant content (for saving to history)
     */
    public StreamResult streamChat(SseEmitter emitter,
                                   String provider,
                                   String model,
                                   List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools,
                                   String userId,
                                   AtomicBoolean cancelled) {
        // Max FC loops to prevent infinite recursion
        int maxLoops = 10;
        List<Map<String, Object>> currentMessages = new ArrayList<>(messages);

        for (int loop = 0; loop < maxLoops && !cancelled.get(); loop++) {
            CallResult result = callLlm(emitter, provider, model, currentMessages, tools, cancelled);

            if (result == null || cancelled.get()) {
                return new StreamResult(null, null, null);
            }

            // If LLM returned tool calls
            if (result.toolCalls != null && !result.toolCalls.isEmpty()) {
                // Check if all tool calls are server-side
                boolean allServer = result.toolCalls.stream()
                        .allMatch(tc -> {
                            Map<String, Object> function = (Map<String, Object>) tc.get("function");
                            return function != null && toolRouter.isServerTool((String) function.get("name"));
                        });

                if (allServer) {
                    // Execute server-side tools and continue loop
                    // Add assistant message with tool_calls
                    Map<String, Object> assistantMsg = new LinkedHashMap<>();
                    assistantMsg.put("role", "assistant");
                    assistantMsg.put("content", null);
                    assistantMsg.put("tool_calls", result.toolCalls);
                    currentMessages.add(assistantMsg);

                    // Execute each tool and add tool results
                    for (Map<String, Object> toolCall : result.toolCalls) {
                        String toolCallId = (String) toolCall.get("id");
                        Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
                        String toolName = (String) function.get("name");
                        String arguments = (String) function.get("arguments");

                        String toolResult = toolExecutor.execute(toolName, arguments, userId);

                        Map<String, Object> toolMsg = new LinkedHashMap<>();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", toolCallId);
                        toolMsg.put("content", toolResult);
                        currentMessages.add(toolMsg);
                    }
                    // Continue loop - call LLM again with tool results
                    continue;
                } else {
                    // Some/all tool calls are for client - already forwarded via SSE
                    String toolCallsJson = null;
                    try {
                        toolCallsJson = objectMapper.writeValueAsString(result.toolCalls);
                    } catch (Exception e) {
                        // ignore
                    }
                    return new StreamResult(result.content, toolCallsJson, "tool_calls");
                }
            }

            // Normal text response - already streamed to client
            return new StreamResult(result.content, null, "stop");
        }

        return new StreamResult(null, null, null);
    }

    /**
     * Make a single LLM API call. Streams response to client if it's a text response
     * or client FC. Accumulates tool_calls silently for server FC.
     */
    private CallResult callLlm(SseEmitter emitter,
                               String provider,
                               String model,
                               List<Map<String, Object>> messages,
                               List<Map<String, Object>> tools,
                               AtomicBoolean cancelled) {
        ProviderConfig.ProviderProperties props = providerConfig.getProvider(provider);
        if (props == null) {
            try {
                emitter.send(SseEmitter.event().data("{\"error\":\"Unknown provider: " + provider + "\"}"));
                emitter.complete();
            } catch (IOException e) {
                // ignore
            }
            return null;
        }

        // Build request body
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("stream", true);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            log.error("Failed to serialize LLM request", e);
            return null;
        }

        String url = props.getBaseUrl() + "/chat/completions";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + props.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        Call call = httpClient.newCall(request);

        // Register cancellation
        // Note: We rely on the cancelled flag being set by SseEmitter callbacks
        // The caller is responsible for calling call.cancel() if needed

        StringBuilder contentBuilder = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        // Track tool call deltas for accumulation
        Map<Integer, Map<String, Object>> toolCallAccumulator = new LinkedHashMap<>();
        boolean hasToolCalls = false;
        boolean isServerFcCall = false;

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("LLM API error: {} - {}", response.code(), errorBody);
                try {
                    emitter.send(SseEmitter.event().data(
                            "{\"error\":\"LLM API returned " + response.code() + "\"}"));
                    emitter.complete();
                } catch (IOException e) {
                    // ignore
                }
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()));
            String line;
            while ((line = reader.readLine()) != null && !cancelled.get()) {
                if (line.isEmpty()) continue;
                if (!line.startsWith("data: ")) continue;

                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) {
                    // Forward [DONE] to client only if not doing server FC
                    if (!isServerFcCall) {
                        try {
                            emitter.send(SseEmitter.event().data("[DONE]"));
                        } catch (IOException e) {
                            cancelled.set(true);
                        }
                    }
                    break;
                }

                try {
                    Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                    if (choices == null || choices.isEmpty()) continue;

                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                    String finishReason = (String) choice.get("finish_reason");

                    if (delta != null) {
                        // Accumulate content
                        String contentPart = (String) delta.get("content");
                        if (contentPart != null) {
                            contentBuilder.append(contentPart);
                        }

                        // Accumulate tool calls
                        List<Map<String, Object>> deltaToolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
                        if (deltaToolCalls != null) {
                            hasToolCalls = true;
                            for (Map<String, Object> tc : deltaToolCalls) {
                                int index = tc.get("index") != null ? ((Number) tc.get("index")).intValue() : 0;
                                Map<String, Object> accumulated = toolCallAccumulator
                                        .computeIfAbsent(index, k -> new LinkedHashMap<>());

                                if (tc.get("id") != null) {
                                    accumulated.put("id", tc.get("id"));
                                }
                                if (tc.get("type") != null) {
                                    accumulated.put("type", tc.get("type"));
                                }
                                Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                                if (fn != null) {
                                    Map<String, Object> accFn = (Map<String, Object>) accumulated
                                            .computeIfAbsent("function", k -> new LinkedHashMap<>());
                                    if (fn.get("name") != null) {
                                        accFn.put("name", fn.get("name"));
                                    }
                                    if (fn.get("arguments") != null) {
                                        String existingArgs = (String) accFn.getOrDefault("arguments", "");
                                        accFn.put("arguments", existingArgs + fn.get("arguments"));
                                    }
                                }
                            }

                            // Check if this is server FC - if so, don't stream to client
                            // We determine this once we have the first tool name
                            if (!isServerFcCall) {
                                // Check the first accumulated tool call
                                for (Map<String, Object> acc : toolCallAccumulator.values()) {
                                    Map<String, Object> fn = (Map<String, Object>) acc.get("function");
                                    if (fn != null && fn.get("name") != null) {
                                        if (toolRouter.isServerTool((String) fn.get("name"))) {
                                            isServerFcCall = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Forward to client if not server FC
                    if (!isServerFcCall) {
                        try {
                            emitter.send(SseEmitter.event().data(data));
                        } catch (IOException e) {
                            cancelled.set(true);
                            call.cancel();
                            break;
                        }
                    }

                } catch (Exception e) {
                    // If we can't parse a chunk, still forward it (best effort)
                    if (!isServerFcCall) {
                        try {
                            emitter.send(SseEmitter.event().data(data));
                        } catch (IOException ex) {
                            cancelled.set(true);
                            call.cancel();
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (!cancelled.get()) {
                log.error("Error reading LLM stream", e);
            }
            return null;
        }

        // Build final tool calls list
        if (hasToolCalls) {
            toolCalls = new ArrayList<>(toolCallAccumulator.values());
        }

        return new CallResult(
                contentBuilder.length() > 0 ? contentBuilder.toString() : null,
                hasToolCalls ? toolCalls : null
        );
    }

    /**
     * Build the server-side tool definitions to inject into LLM requests.
     */
    public List<Map<String, Object>> getServerToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // query_orders
        Map<String, Object> queryOrders = new LinkedHashMap<>();
        queryOrders.put("type", "function");
        Map<String, Object> qoFn = new LinkedHashMap<>();
        qoFn.put("name", "query_orders");
        qoFn.put("description", "查询用户的订单列表，可按状态筛选");
        Map<String, Object> qoParams = new LinkedHashMap<>();
        qoParams.put("type", "object");
        Map<String, Object> qoProps = new LinkedHashMap<>();
        Map<String, Object> statusProp = new LinkedHashMap<>();
        statusProp.put("type", "string");
        statusProp.put("description", "订单状态筛选: pending(待发货), shipped(已发货), delivered(已签收)");
        statusProp.put("enum", List.of("pending", "shipped", "delivered"));
        qoProps.put("status", statusProp);
        qoParams.put("properties", qoProps);
        qoParams.put("required", List.of());
        qoFn.put("parameters", qoParams);
        queryOrders.put("function", qoFn);
        tools.add(queryOrders);

        // get_order_detail
        Map<String, Object> getOrderDetail = new LinkedHashMap<>();
        getOrderDetail.put("type", "function");
        Map<String, Object> godFn = new LinkedHashMap<>();
        godFn.put("name", "get_order_detail");
        godFn.put("description", "查询指定订单的详细信息");
        Map<String, Object> godParams = new LinkedHashMap<>();
        godParams.put("type", "object");
        Map<String, Object> godProps = new LinkedHashMap<>();
        Map<String, Object> orderIdProp = new LinkedHashMap<>();
        orderIdProp.put("type", "string");
        orderIdProp.put("description", "订单ID");
        godProps.put("order_id", orderIdProp);
        godParams.put("properties", godProps);
        godParams.put("required", List.of("order_id"));
        godFn.put("parameters", godParams);
        getOrderDetail.put("function", godFn);
        tools.add(getOrderDetail);

        // get_logistics
        Map<String, Object> getLogistics = new LinkedHashMap<>();
        getLogistics.put("type", "function");
        Map<String, Object> glFn = new LinkedHashMap<>();
        glFn.put("name", "get_logistics");
        glFn.put("description", "查询订单的物流状态和配送进度");
        Map<String, Object> glParams = new LinkedHashMap<>();
        glParams.put("type", "object");
        Map<String, Object> glProps = new LinkedHashMap<>();
        Map<String, Object> glOrderIdProp = new LinkedHashMap<>();
        glOrderIdProp.put("type", "string");
        glOrderIdProp.put("description", "订单ID");
        glProps.put("order_id", glOrderIdProp);
        glParams.put("properties", glProps);
        glParams.put("required", List.of("order_id"));
        glFn.put("parameters", glParams);
        getLogistics.put("function", glFn);
        tools.add(getLogistics);

        return tools;
    }

    // Internal result types

    private static class CallResult {
        final String content;
        final List<Map<String, Object>> toolCalls;

        CallResult(String content, List<Map<String, Object>> toolCalls) {
            this.content = content;
            this.toolCalls = toolCalls;
        }
    }

    public static class StreamResult {
        private final String content;
        private final String toolCallsJson;
        private final String finishReason;

        public StreamResult(String content, String toolCallsJson, String finishReason) {
            this.content = content;
            this.toolCallsJson = toolCallsJson;
            this.finishReason = finishReason;
        }

        public String getContent() { return content; }
        public String getToolCallsJson() { return toolCallsJson; }
        public String getFinishReason() { return finishReason; }
    }
}
