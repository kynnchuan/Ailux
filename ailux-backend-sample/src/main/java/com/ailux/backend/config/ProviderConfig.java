package com.ailux.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "ailux")
public class ProviderConfig {

    private Map<String, ProviderProperties> providers = new HashMap<>();
    private ContextConfig context = new ContextConfig();

    public Map<String, ProviderProperties> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderProperties> providers) {
        this.providers = providers;
    }

    public ContextConfig getContext() {
        return context;
    }

    public void setContext(ContextConfig context) {
        this.context = context;
    }

    public ProviderProperties getProvider(String name) {
        return providers.get(name);
    }

    public static class ProviderProperties {
        private String baseUrl;
        private String apiKey;
        private String defaultModel;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }
    }

    public static class ContextConfig {
        private int maxMessages = 20;

        public int getMaxMessages() {
            return maxMessages;
        }

        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }
    }
}
