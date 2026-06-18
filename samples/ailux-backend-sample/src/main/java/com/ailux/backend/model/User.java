package com.ailux.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 64, nullable = false)
    private String name;

    @Column(length = 128, unique = true, nullable = false)
    private String token;

    @Column(name = "default_provider", length = 32)
    private String defaultProvider = "deepseek";

    @Column(name = "default_model", length = 64)
    private String defaultModel = "deepseek-v4-flash";

    @Column(name = "context_mode", length = 16)
    private String contextMode = "server";

    @Column(name = "daily_request_limit")
    private Integer dailyRequestLimit = 100;

    @Column(name = "daily_token_limit")
    private Integer dailyTokenLimit = 100000;

    @Column(name = "available_models")
    private String availableModels = "deepseek-v4-flash,deepseek-v4-pro,gpt-4o";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters and Setters

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public String getContextMode() { return contextMode; }
    public void setContextMode(String contextMode) { this.contextMode = contextMode; }

    public Integer getDailyRequestLimit() { return dailyRequestLimit; }
    public void setDailyRequestLimit(Integer dailyRequestLimit) { this.dailyRequestLimit = dailyRequestLimit; }

    public Integer getDailyTokenLimit() { return dailyTokenLimit; }
    public void setDailyTokenLimit(Integer dailyTokenLimit) { this.dailyTokenLimit = dailyTokenLimit; }

    public String getAvailableModels() { return availableModels; }
    public void setAvailableModels(String availableModels) { this.availableModels = availableModels; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /**
     * Check if user has access to the specified model.
     * A null or negative dailyRequestLimit means unlimited.
     */
    public boolean hasModelAccess(String model) {
        if (availableModels == null || availableModels.isEmpty()) return true;
        for (String m : availableModels.split(",")) {
            if (m.trim().equals(model)) return true;
        }
        return false;
    }
}
