package com.ailux.backend.service;

import com.ailux.backend.tool.GetLogisticsTool;
import com.ailux.backend.tool.GetOrderDetailTool;
import com.ailux.backend.tool.QueryOrdersTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Executes server-side tools and returns results as JSON strings.
 */
@Component
public class ToolExecutor {

    private final Map<String, ServerTool> tools = new HashMap<>();

    public ToolExecutor(QueryOrdersTool queryOrdersTool,
                        GetOrderDetailTool getOrderDetailTool,
                        GetLogisticsTool getLogisticsTool) {
        tools.put("query_orders", queryOrdersTool);
        tools.put("get_order_detail", getOrderDetailTool);
        tools.put("get_logistics", getLogisticsTool);
    }

    /**
     * Execute a server-side tool.
     *
     * @param toolName the tool name
     * @param arguments JSON string of arguments
     * @param userId the current user ID
     * @return the tool result as a JSON string
     */
    public String execute(String toolName, String arguments, String userId) {
        ServerTool tool = tools.get(toolName);
        if (tool == null) {
            return "{\"error\": \"Unknown tool: " + toolName + "\"}";
        }
        return tool.execute(arguments, userId);
    }

    /**
     * Interface for server-side tool implementations.
     */
    public interface ServerTool {
        String execute(String arguments, String userId);
    }
}
