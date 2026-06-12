package com.ailux.backend.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Base class that centralizes argument parsing and error wrapping so each
 * concrete tool only declares its schema and its business logic.
 */
public abstract class AbstractServerTool implements ServerTool {

    protected final ObjectMapper objectMapper;

    protected AbstractServerTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public final String execute(String arguments, String userId) {
        try {
            Map<String, Object> args = parseArgs(arguments);
            return doExecute(args, userId);
        } catch (Exception e) {
            return errorJson("Failed to execute " + name() + ": " + e.getMessage());
        }
    }

    /** Run the tool with parsed arguments. Throwing is fine — the base wraps it as an error JSON. */
    protected abstract String doExecute(Map<String, Object> args, String userId) throws Exception;

    @SuppressWarnings("unchecked")
    protected Map<String, Object> parseArgs(String arguments) throws Exception {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(arguments, Map.class);
    }

    protected String toJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    protected String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
