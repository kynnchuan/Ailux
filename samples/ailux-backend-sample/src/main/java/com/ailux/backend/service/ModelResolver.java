package com.ailux.backend.service;

import com.ailux.backend.config.ProviderConfig;
import com.ailux.backend.dto.ChatRequest;
import com.ailux.backend.model.User;
import com.ailux.backend.repository.UserRepository;
import com.ailux.backend.web.ModelAccessException;
import com.ailux.backend.web.UnauthorizedException;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for resolving the effective provider/model of a request
 * and validating that the user is allowed to use it.
 *
 * <p>Extracted so the validation can run at the <b>controller entry</b> (before any
 * SSE response is committed), allowing a clean HTTP 403 to be returned instead of an
 * in-stream error event. {@link ChatService} reuses the same resolution for the actual
 * LLM call, so provider/model logic lives in exactly one place.
 */
@Component
public class ModelResolver {

    private final UserRepository userRepository;
    private final ProviderConfig providerConfig;

    public ModelResolver(UserRepository userRepository, ProviderConfig providerConfig) {
        this.userRepository = userRepository;
        this.providerConfig = providerConfig;
    }

    /**
     * Resolve provider/model from the request (falling back to account defaults)
     * and verify the user has access to the resolved model.
     *
     * @throws UnauthorizedException if the user id cannot be found
     * @throws ModelAccessException  if the user lacks access to the resolved model
     */
    public Resolved resolveAndValidate(ChatRequest request, String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Unknown user"));

        String provider = request.getProvider() != null ? request.getProvider() : user.getDefaultProvider();

        String model = request.getModel();
        if (model == null || model.isEmpty()) {
            ProviderConfig.ProviderProperties props = providerConfig.getProvider(provider);
            model = props != null ? props.getDefaultModel() : user.getDefaultModel();
        }

        if (!user.hasModelAccess(model)) {
            throw new ModelAccessException(model);
        }

        return new Resolved(provider, model);
    }

    /** Resolved provider + model pair. */
    public static class Resolved {
        private final String provider;
        private final String model;

        public Resolved(String provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        public String getProvider() { return provider; }
        public String getModel() { return model; }
    }
}
