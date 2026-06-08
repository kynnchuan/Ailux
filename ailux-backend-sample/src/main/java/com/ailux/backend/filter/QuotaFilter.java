package com.ailux.backend.filter;

import com.ailux.backend.config.SecurityContext;
import com.ailux.backend.model.QuotaUsage;
import com.ailux.backend.model.User;
import com.ailux.backend.repository.QuotaUsageRepository;
import com.ailux.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

@Component
@Order(2)
public class QuotaFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final QuotaUsageRepository quotaUsageRepository;
    private final ObjectMapper objectMapper;

    public QuotaFilter(UserRepository userRepository,
                       QuotaUsageRepository quotaUsageRepository,
                       ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.quotaUsageRepository = quotaUsageRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Only check quota for chat completions endpoint
        if (!request.getRequestURI().equals("/api/chat/completions") || !"POST".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = SecurityContext.getUserId();
        if (userId == null) {
            // Auth filter should have rejected already
            filterChain.doFilter(request, response);
            return;
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        User user = userOpt.get();

        // Unlimited quota (negative limit means unlimited)
        if (user.getDailyRequestLimit() != null && user.getDailyRequestLimit() < 0) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check daily request limit
        LocalDate today = LocalDate.now();
        Optional<QuotaUsage> usageOpt = quotaUsageRepository.findByUserIdAndDate(userId, today);
        int currentRequests = usageOpt.map(QuotaUsage::getRequestCount).orElse(0);

        if (user.getDailyRequestLimit() != null && currentRequests >= user.getDailyRequestLimit()) {
            sendError(response, 429, "daily_request_limit_exceeded",
                    "Daily request limit exceeded. Limit: " + user.getDailyRequestLimit());
            return;
        }

        // Check daily token limit
        int currentTokens = usageOpt.map(QuotaUsage::getTokenCount).orElse(0);
        if (user.getDailyTokenLimit() != null && user.getDailyTokenLimit() > 0 && currentTokens >= user.getDailyTokenLimit()) {
            sendError(response, 429, "daily_token_limit_exceeded",
                    "Daily token limit exceeded. Limit: " + user.getDailyTokenLimit());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void sendError(HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "error", error,
                "message", message
        )));
    }
}
