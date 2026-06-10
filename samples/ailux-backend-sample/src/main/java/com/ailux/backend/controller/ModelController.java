package com.ailux.backend.controller;

import com.ailux.backend.config.ProviderConfig;
import com.ailux.backend.config.SecurityContext;
import com.ailux.backend.dto.ModelInfo;
import com.ailux.backend.model.User;
import com.ailux.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Returns available models for the current user.
 */
@RestController
@RequestMapping("/api")
public class ModelController {

    private final UserRepository userRepository;
    private final ProviderConfig providerConfig;

    public ModelController(UserRepository userRepository, ProviderConfig providerConfig) {
        this.userRepository = userRepository;
        this.providerConfig = providerConfig;
    }

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> getModels() {
        String userId = SecurityContext.getUserId();
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        List<ModelInfo> models = new ArrayList<>();

        // Build model list from provider config, filtered by user's available models
        Set<String> availableModels = new HashSet<>();
        if (user.getAvailableModels() != null) {
            for (String m : user.getAvailableModels().split(",")) {
                availableModels.add(m.trim());
            }
        }

        for (Map.Entry<String, ProviderConfig.ProviderProperties> entry : providerConfig.getProviders().entrySet()) {
            String providerName = entry.getKey();
            ProviderConfig.ProviderProperties props = entry.getValue();
            String modelId = props.getDefaultModel();

            if (availableModels.contains(modelId)) {
                boolean isDefault = modelId.equals(user.getDefaultModel())
                        && providerName.equals(user.getDefaultProvider());
                String displayName = formatModelName(modelId);
                models.add(new ModelInfo(modelId, providerName, displayName, isDefault));
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("models", models);
        return ResponseEntity.ok(response);
    }

    private String formatModelName(String modelId) {
        switch (modelId) {
            case "gpt-4o":
                return "GPT-4o";
            case "deepseek-chat":
                return "DeepSeek Chat";
            default:
                return modelId;
        }
    }
}
