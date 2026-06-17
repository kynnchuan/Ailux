package com.ailux.backend.tool;

import java.util.Map;

/**
 * A self-describing server-side tool.
 *
 * <p>Each implementation is the <b>single source of truth</b> for one tool:
 * its name, its OpenAI function-calling JSON schema, and its execution logic
 * all live together. Adding a new server tool is therefore a one-file change —
 * no separate routing table or hand-written schema list to keep in sync.
 *
 * <p>The registry ({@code ToolExecutor}) discovers every implementation via
 * Spring component scanning, so dropping a new {@code @Component} that
 * implements this interface is enough to register it for routing, schema
 * injection, and execution.
 */
public interface ServerTool {

    /** Unique function name, e.g. {@code "query_orders"}. Must match the schema name. */
    String name();

    /** Human-readable description sent to the LLM. */
    String description();

    /**
     * The JSON-schema {@code parameters} object for this tool, e.g.
     * <pre>{ "type":"object", "properties":{...}, "required":[...] }</pre>
     * Returned as a {@link Map} so it serializes straight into the LLM request body.
     */
    Map<String, Object> parameters();

    /**
     * Execute the tool.
     *
     * @param arguments JSON string of the call arguments (may be empty/blank)
     * @param userId    the authenticated user id (for ownership checks)
     * @return the tool result serialized as a JSON string
     */
    String execute(String arguments, String userId);
}
