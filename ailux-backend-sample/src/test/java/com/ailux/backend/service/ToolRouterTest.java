package com.ailux.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolRouter - Server/Client tool routing")
class ToolRouterTest {

    private ToolRouter toolRouter;

    @BeforeEach
    void setUp() {
        toolRouter = new ToolRouter();
    }

    @Test
    @DisplayName("query_orders is a server tool")
    void queryOrdersIsServerTool() {
        assertTrue(toolRouter.isServerTool("query_orders"));
    }

    @Test
    @DisplayName("get_order_detail is a server tool")
    void getOrderDetailIsServerTool() {
        assertTrue(toolRouter.isServerTool("get_order_detail"));
    }

    @Test
    @DisplayName("get_logistics is a server tool")
    void getLogisticsIsServerTool() {
        assertTrue(toolRouter.isServerTool("get_logistics"));
    }

    @Test
    @DisplayName("Unknown tool names are NOT server tools (client-side)")
    void unknownToolIsNotServerTool() {
        assertFalse(toolRouter.isServerTool("open_camera"));
        assertFalse(toolRouter.isServerTool("play_sound"));
        assertFalse(toolRouter.isServerTool(""));
    }

    @Test
    @DisplayName("Null tool name does not throw")
    void nullToolNameReturnsFalse() {
        assertFalse(toolRouter.isServerTool(null));
    }
}
