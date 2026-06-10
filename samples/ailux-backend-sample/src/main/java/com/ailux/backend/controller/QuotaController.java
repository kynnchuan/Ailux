package com.ailux.backend.controller;

import com.ailux.backend.config.SecurityContext;
import com.ailux.backend.dto.QuotaResponse;
import com.ailux.backend.model.QuotaUsage;
import com.ailux.backend.model.User;
import com.ailux.backend.repository.QuotaUsageRepository;
import com.ailux.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Returns quota usage information for the current user.
 */
@RestController
@RequestMapping("/api")
public class QuotaController {

    private final UserRepository userRepository;
    private final QuotaUsageRepository quotaUsageRepository;

    public QuotaController(UserRepository userRepository,
                           QuotaUsageRepository quotaUsageRepository) {
        this.userRepository = userRepository;
        this.quotaUsageRepository = quotaUsageRepository;
    }

    @GetMapping("/quota")
    public ResponseEntity<QuotaResponse> getQuota() {
        String userId = SecurityContext.getUserId();
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        LocalDate today = LocalDate.now();
        Optional<QuotaUsage> usageOpt = quotaUsageRepository.findByUserIdAndDate(userId, today);
        int usedRequests = usageOpt.map(QuotaUsage::getRequestCount).orElse(0);
        int usedTokens = usageOpt.map(QuotaUsage::getTokenCount).orElse(0);

        int requestLimit = user.getDailyRequestLimit() != null ? user.getDailyRequestLimit() : -1;
        int tokenLimit = user.getDailyTokenLimit() != null ? user.getDailyTokenLimit() : -1;

        List<String> availableModels = user.getAvailableModels() != null
                ? Arrays.stream(user.getAvailableModels().split(","))
                    .map(String::trim)
                    .collect(Collectors.toList())
                : List.of();

        QuotaResponse response = new QuotaResponse(
                new QuotaResponse.UsageInfo(usedRequests, requestLimit),
                new QuotaResponse.UsageInfo(usedTokens, tokenLimit),
                availableModels
        );

        return ResponseEntity.ok(response);
    }
}
