package com.ailux.backend.tool;

import com.ailux.backend.model.Logistics;
import com.ailux.backend.model.Order;
import com.ailux.backend.repository.LogisticsRepository;
import com.ailux.backend.repository.OrderRepository;
import com.ailux.backend.service.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class GetLogisticsTool implements ToolExecutor.ServerTool {

    private final LogisticsRepository logisticsRepository;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    public GetLogisticsTool(LogisticsRepository logisticsRepository,
                            OrderRepository orderRepository,
                            ObjectMapper objectMapper) {
        this.logisticsRepository = logisticsRepository;
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String execute(String arguments, String userId) {
        try {
            Map<String, Object> args = objectMapper.readValue(arguments, Map.class);
            String orderId = (String) args.get("order_id");

            if (orderId == null || orderId.isEmpty()) {
                return "{\"error\": \"order_id is required\"}";
            }

            // Security check: user can only see their own order's logistics
            Optional<Order> orderOpt = orderRepository.findById(orderId);
            if (orderOpt.isEmpty() || !orderOpt.get().getUserId().equals(userId)) {
                return "{\"error\": \"Order not found or access denied\"}";
            }

            Optional<Logistics> logisticsOpt = logisticsRepository.findByOrderId(orderId);
            if (logisticsOpt.isEmpty()) {
                return "{\"error\": \"No logistics info for order: " + orderId + "\"}";
            }

            Logistics logistics = logisticsOpt.get();
            Map<String, Object> result = new HashMap<>();
            result.put("order_id", orderId);
            result.put("status", logistics.getStatus());
            result.put("location", logistics.getLocation());
            result.put("eta", logistics.getEta());
            result.put("updated_at", logistics.getUpdatedAt() != null ? logistics.getUpdatedAt().toString() : null);

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\": \"Failed to get logistics: " + e.getMessage() + "\"}";
        }
    }
}
