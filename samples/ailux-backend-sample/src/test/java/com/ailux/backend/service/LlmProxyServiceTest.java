package com.ailux.backend.service;

import com.ailux.backend.config.ProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("LlmProxyService - Tool definitions and structure")
class LlmProxyServiceTest {

    private LlmProxyService llmProxyService;
    private ProviderConfig providerConfig;
    private ToolRouter toolRouter;
    private ToolExecutor toolExecutor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        providerConfig = new ProviderConfig();
        ProviderConfig.ProviderProperties deepseek = new ProviderConfig.ProviderProperties();
        deepseek.setBaseUrl("https://api.deepseek.com");
        deepseek.setApiKey("test-key");
        deepseek.setDefaultModel("deepseek-chat");
        providerConfig.setProviders(Map.of("deepseek", deepseek));

        toolRouter = new ToolRouter();
        toolExecutor = mock(ToolExecutor.class);
        objectMapper = new ObjectMapper();

        llmProxyService = new LlmProxyService(providerConfig, toolRouter, toolExecutor, objectMapper);
    }

    @Test
    @DisplayName("getServerToolDefinitions returns 3 tools")
    void serverToolDefinitionsCount() {
        List<Map<String, Object>> tools = llmProxyService.getServerToolDefinitions();
        assertEquals(3, tools.size());
    }

    @Test
    @DisplayName("Server tools include query_orders")
    void hasQueryOrdersTool() {
        List<Map<String, Object>> tools = llmProxyService.getServerToolDefinitions();
        boolean found = tools.stream().anyMatch(t -> {
            Map<String, Object> fn = (Map<String, Object>) t.get("function");
            return fn != null && "query_orders".equals(fn.get("name"));
        });
        assertTrue(found);
    }

    @Test
    @DisplayName("Server tools include get_order_detail")
    void hasGetOrderDetailTool() {
        List<Map<String, Object>> tools = llmProxyService.getServerToolDefinitions();
        boolean found = tools.stream().anyMatch(t -> {
            Map<String, Object> fn = (Map<String, Object>) t.get("function");
            return fn != null && "get_order_detail".equals(fn.get("name"));
        });
        assertTrue(found);
    }

    @Test
    @DisplayName("Server tools include get_logistics")
    void hasGetLogisticsTool() {
        List<Map<String, Object>> tools = llmProxyService.getServerToolDefinitions();
        boolean found = tools.stream().anyMatch(t -> {
            Map<String, Object> fn = (Map<String, Object>) t.get("function");
            return fn != null && "get_logistics".equals(fn.get("name"));
        });
        assertTrue(found);
    }

    @Test
    @DisplayName("Each tool definition has correct structure")
    void toolDefinitionStructure() {
        List<Map<String, Object>> tools = llmProxyService.getServerToolDefinitions();
        for (Map<String, Object> tool : tools) {
            assertEquals("function", tool.get("type"), "Tool type must be 'function'");
            Map<String, Object> fn = (Map<String, Object>) tool.get("function");
            assertNotNull(fn, "Function definition must be present");
            assertNotNull(fn.get("name"), "Function name is required");
            assertNotNull(fn.get("description"), "Function description is required");
            assertNotNull(fn.get("parameters"), "Function parameters are required");

            Map<String, Object> params = (Map<String, Object>) fn.get("parameters");
            assertEquals("object", params.get("type"), "Parameters type must be 'object'");
            assertNotNull(params.get("properties"), "Parameters properties are required");
        }
    }

    @Test
    @DisplayName("StreamResult getters work correctly")
    void streamResultGetters() {
        LlmProxyService.StreamResult result = new LlmProxyService.StreamResult("content", "[{\"id\":\"1\"}]", "stop");

        assertEquals("content", result.getContent());
        assertEquals("[{\"id\":\"1\"}]", result.getToolCallsJson());
        assertEquals("stop", result.getFinishReason());
    }

    @Test
    @DisplayName("StreamResult handles nulls")
    void streamResultNulls() {
        LlmProxyService.StreamResult result = new LlmProxyService.StreamResult(null, null, null);

        assertNull(result.getContent());
        assertNull(result.getToolCallsJson());
        assertNull(result.getFinishReason());
    }
}
