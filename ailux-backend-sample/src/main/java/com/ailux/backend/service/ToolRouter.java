package com.ailux.backend.service;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Determines whether a tool_call should be executed on the server side
 * or transparently forwarded to the client.
 */
@Component
public class ToolRouter {

    private static final Set<String> SERVER_TOOLS = Set.of(
            "query_orders",
            "get_order_detail",
            "get_logistics"
    );

    /**
     * Returns true if the tool should be executed on the server.
     * Tools not in the registry are forwarded to the client.
     */
    public boolean isServerTool(String toolName) {
        if (toolName == null) return false;
        return SERVER_TOOLS.contains(toolName);
    }
}
