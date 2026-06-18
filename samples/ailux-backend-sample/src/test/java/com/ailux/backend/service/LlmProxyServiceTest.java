package com.ailux.backend.service;

import com.ailux.backend.config.ProviderConfig;
import com.ailux.backend.tool.ServerTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LlmProxyService - Tool definitions and structure")
class LlmProxyServiceTest {

    private LlmProxyService llmProxyService;

    private ServerTool fakeTool(String name) {
        return new ServerTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc of " + name; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String execute(String arguments, String userId) { return "{}"; }
        };
    }

    @BeforeEach
    void setUp() {
        ProviderConfig providerConfig = new ProviderConfig();
        ProviderConfig.ProviderProperties deepseek = new ProviderConfig.ProviderProperties();
        deepseek.setBaseUrl("https://api.deepseek.com");
        deepseek.setApiKey("test-key");
        deepseek.setDefaultModel("deepseek-v4-flash");
        providerConfig.setProviders(Map.of("deepseek", deepseek));

        ToolExecutor toolExecutor = new ToolExecutor(List.of(
                fakeTool("query_orders"),
                fakeTool("get_order_detail"),
                fakeTool("get_logistics")
        ));
        ObjectMapper objectMapper = new ObjectMapper();

        llmProxyService = new LlmProxyService(providerConfig, toolExecutor, objectMapper);
    }

    @Test
    @DisplayName("getServerToolDefinitions returns 3 tools (derived from registry)")
    void serverToolDefinitionsCount() {
        List<Map<String, Object>> tools = llmProxyService.getServerToolDefinitions();
        assertEquals(3, tools.size());
    }

    @Test
    @DisplayName("Server tools include query_orders")
    void hasQueryOrdersTool() {
        assertTrue(containsTool("query_orders"));
    }

    @Test
    @DisplayName("Server tools include get_order_detail")
    void hasGetOrderDetailTool() {
        assertTrue(containsTool("get_order_detail"));
    }

    @Test
    @DisplayName("Server tools include get_logistics")
    void hasGetLogisticsTool() {
        assertTrue(containsTool("get_logistics"));
    }

    @SuppressWarnings("unchecked")
    private boolean containsTool(String name) {
        return llmProxyService.getServerToolDefinitions().stream().anyMatch(t -> {
            Map<String, Object> fn = (Map<String, Object>) t.get("function");
            return fn != null && name.equals(fn.get("name"));
        });
    }

    @Test
    @DisplayName("Each tool definition has correct structure")
    void toolDefinitionStructure() {
        List<Map<String, Object>> tools = llmProxyService.getServerToolDefinitions();
        for (Map<String, Object> tool : tools) {
            assertEquals("function", tool.get("type"), "Tool type must be 'function'");
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) tool.get("function");
            assertNotNull(fn, "Function definition must be present");
            assertNotNull(fn.get("name"), "Function name is required");
            assertNotNull(fn.get("description"), "Function description is required");
            assertNotNull(fn.get("parameters"), "Function parameters are required");

            @SuppressWarnings("unchecked")
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
