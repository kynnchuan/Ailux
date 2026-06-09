package com.ailux.backend.service;

import com.ailux.backend.tool.GetLogisticsTool;
import com.ailux.backend.tool.GetOrderDetailTool;
import com.ailux.backend.tool.QueryOrdersTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ToolExecutor - Server tool dispatch")
class ToolExecutorTest {

    private ToolExecutor toolExecutor;
    private QueryOrdersTool queryOrdersTool;
    private GetOrderDetailTool getOrderDetailTool;
    private GetLogisticsTool getLogisticsTool;

    @BeforeEach
    void setUp() {
        queryOrdersTool = mock(QueryOrdersTool.class);
        getOrderDetailTool = mock(GetOrderDetailTool.class);
        getLogisticsTool = mock(GetLogisticsTool.class);
        toolExecutor = new ToolExecutor(queryOrdersTool, getOrderDetailTool, getLogisticsTool);
    }

    @Test
    @DisplayName("Dispatches query_orders to QueryOrdersTool")
    void dispatchQueryOrders() {
        when(queryOrdersTool.execute("{}", "user1")).thenReturn("{\"orders\":[]}");

        String result = toolExecutor.execute("query_orders", "{}", "user1");

        assertEquals("{\"orders\":[]}", result);
        verify(queryOrdersTool).execute("{}", "user1");
    }

    @Test
    @DisplayName("Dispatches get_order_detail to GetOrderDetailTool")
    void dispatchGetOrderDetail() {
        when(getOrderDetailTool.execute("{\"order_id\":\"ORD001\"}", "user1"))
                .thenReturn("{\"id\":\"ORD001\"}");

        String result = toolExecutor.execute("get_order_detail", "{\"order_id\":\"ORD001\"}", "user1");

        assertEquals("{\"id\":\"ORD001\"}", result);
        verify(getOrderDetailTool).execute("{\"order_id\":\"ORD001\"}", "user1");
    }

    @Test
    @DisplayName("Dispatches get_logistics to GetLogisticsTool")
    void dispatchGetLogistics() {
        when(getLogisticsTool.execute("{\"order_id\":\"ORD001\"}", "user1"))
                .thenReturn("{\"status\":\"in_transit\"}");

        String result = toolExecutor.execute("get_logistics", "{\"order_id\":\"ORD001\"}", "user1");

        assertEquals("{\"status\":\"in_transit\"}", result);
    }

    @Test
    @DisplayName("Returns error JSON for unknown tool name")
    void unknownToolReturnsError() {
        String result = toolExecutor.execute("unknown_tool", "{}", "user1");

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Unknown tool"));
    }
}
