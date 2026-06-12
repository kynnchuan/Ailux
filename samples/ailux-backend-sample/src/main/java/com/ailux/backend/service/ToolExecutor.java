package com.ailux.backend.service;

import com.ailux.backend.tool.ServerTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry for server-side tools.
 *
 * <p>This is now the <b>single source of truth</b> for everything tool-related:
 * <ul>
 *   <li><b>Routing</b> — {@link #isServerTool(String)} replaces the old {@code ToolRouter}.</li>
 *   <li><b>Schema</b> — {@link #getToolDefinitions()} builds the OpenAI function specs
 *       from each tool's self-description, replacing the hand-written list that used to
 *       live in {@code LlmProxyService}.</li>
 *   <li><b>Execution</b> — {@link #execute(String, String, String)} dispatches to the tool.</li>
 * </ul>
 *
 * <p>All {@link ServerTool} beans are auto-discovered via Spring component scanning,
 * so adding a new tool is a one-file change: drop a {@code @Component} implementing
 * {@link ServerTool} and it is automatically routed, advertised, and executed.
 */
@Component
public class ToolExecutor {

    /** name -> tool, preserving discovery order for a stable schema list. */
    private final Map<String, ServerTool> tools = new LinkedHashMap<>();

    public ToolExecutor(List<ServerTool> serverTools) {
        for (ServerTool tool : serverTools) {
            tools.put(tool.name(), tool);
        }
    }

    /**
     * Returns true if the named tool is executed on the server.
     * Tools not in the registry are forwarded to the client.
     */
    public boolean isServerTool(String toolName) {
        if (toolName == null) return false;
        return tools.containsKey(toolName);
    }

    /**
     * Execute a server-side tool.
     *
     * @param toolName  the tool name
     * @param arguments JSON string of arguments
     * @param userId    the current user ID
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
     * Build the OpenAI function-calling definitions for every registered tool,
     * derived from each tool's self-description (name/description/parameters).
     */
    public List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (ServerTool tool : tools.values()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.parameters());

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "function");
            entry.put("function", function);
            definitions.add(entry);
        }
        return definitions;
    }
}
