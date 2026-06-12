package com.ailux.backend.service;

import com.ailux.backend.tool.ServerTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolExecutor - Registry: routing, schema, dispatch")
class ToolExecutorTest {

    private ToolExecutor toolExecutor;

    /** Lightweight self-describing fake tools — no Spring, no DB. */
    private ServerTool fakeTool(String name, String result) {
        return new ServerTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc of " + name; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String execute(String arguments, String userId) { return result; }
        };
    }

    @BeforeEach
    void setUp() {
        toolExecutor = new ToolExecutor(List.of(
                fakeTool("query_orders", "{\"orders\":[]}"),
                fakeTool("get_order_detail", "{\"id\":\"ORD001\"}"),
                fakeTool("get_logistics", "{\"status\":\"in_transit\"}")
        ));
    }

    @Test
    @DisplayName("isServerTool recognizes registered tools")
    void isServerToolKnown() {
        assertTrue(toolExecutor.isServerTool("query_orders"));
        assertTrue(toolExecutor.isServerTool("get_order_detail"));
        assertTrue(toolExecutor.isServerTool("get_logistics"));
    }

    @Test
    @DisplayName("isServerTool returns false for unknown / null")
    void isServerToolUnknown() {
        assertFalse(toolExecutor.isServerTool("open_camera"));
        assertFalse(toolExecutor.isServerTool(""));
        assertFalse(toolExecutor.isServerTool(null));
    }

    @Test
    @DisplayName("Dispatches to the matching tool")
    void dispatch() {
        assertEquals("{\"orders\":[]}", toolExecutor.execute("query_orders", "{}", "user1"));
        assertEquals("{\"id\":\"ORD001\"}",
                toolExecutor.execute("get_order_detail", "{\"order_id\":\"ORD001\"}", "user1"));
        assertEquals("{\"status\":\"in_transit\"}",
                toolExecutor.execute("get_logistics", "{\"order_id\":\"ORD001\"}", "user1"));
    }

    @Test
    @DisplayName("Returns error JSON for unknown tool name")
    void unknownToolReturnsError() {
        String result = toolExecutor.execute("unknown_tool", "{}", "user1");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Unknown tool"));
    }

    @Test
    @DisplayName("getToolDefinitions derives OpenAI schema from each tool")
    void toolDefinitionsDerived() {
        List<Map<String, Object>> defs = toolExecutor.getToolDefinitions();
        assertEquals(3, defs.size());
        for (Map<String, Object> tool : defs) {
            assertEquals("function", tool.get("type"));
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) tool.get("function");
            assertNotNull(fn.get("name"));
            assertNotNull(fn.get("description"));
            assertNotNull(fn.get("parameters"));
        }
    }
}
