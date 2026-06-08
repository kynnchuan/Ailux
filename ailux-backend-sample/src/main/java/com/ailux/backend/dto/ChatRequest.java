package com.ailux.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public class ChatRequest {

    @JsonProperty("session_id")
    private String sessionId;

    private String provider;

    private String model;

    private List<MessageDTO> messages;

    private List<Map<String, Object>> tools;

    @JsonProperty("context_mode")
    private String contextMode;

    // Getters and Setters

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<MessageDTO> getMessages() { return messages; }
    public void setMessages(List<MessageDTO> messages) { this.messages = messages; }

    public List<Map<String, Object>> getTools() { return tools; }
    public void setTools(List<Map<String, Object>> tools) { this.tools = tools; }

    public String getContextMode() { return contextMode; }
    public void setContextMode(String contextMode) { this.contextMode = contextMode; }

    public static class MessageDTO {
        private String role;
        private String content;
        @JsonProperty("tool_calls")
        private List<Map<String, Object>> toolCalls;
        @JsonProperty("tool_call_id")
        private String toolCallId;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public List<Map<String, Object>> getToolCalls() { return toolCalls; }
        public void setToolCalls(List<Map<String, Object>> toolCalls) { this.toolCalls = toolCalls; }

        public String getToolCallId() { return toolCallId; }
        public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    }
}
