package com.ailux.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class QuotaResponse {

    @JsonProperty("daily_requests")
    private UsageInfo dailyRequests;

    @JsonProperty("daily_tokens")
    private UsageInfo dailyTokens;

    @JsonProperty("available_models")
    private List<String> availableModels;

    public QuotaResponse(UsageInfo dailyRequests, UsageInfo dailyTokens, List<String> availableModels) {
        this.dailyRequests = dailyRequests;
        this.dailyTokens = dailyTokens;
        this.availableModels = availableModels;
    }

    public UsageInfo getDailyRequests() { return dailyRequests; }
    public UsageInfo getDailyTokens() { return dailyTokens; }
    public List<String> getAvailableModels() { return availableModels; }

    public static class UsageInfo {
        private int used;
        private int limit;

        public UsageInfo(int used, int limit) {
            this.used = used;
            this.limit = limit;
        }

        public int getUsed() { return used; }
        public int getLimit() { return limit; }
    }
}
