package com.ailux.backend.tool;

import com.ailux.backend.model.Order;
import com.ailux.backend.repository.OrderRepository;
import com.ailux.backend.service.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class QueryOrdersTool implements ToolExecutor.ServerTool {

    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    public QueryOrdersTool(OrderRepository orderRepository, ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String execute(String arguments, String userId) {
        try {
            String status = null;
            if (arguments != null && !arguments.isBlank()) {
                Map<String, Object> args = objectMapper.readValue(arguments, Map.class);
                status = (String) args.get("status");
            }

            List<Order> orders;
            if (status != null && !status.isEmpty()) {
                orders = orderRepository.findByUserIdAndStatus(userId, status);
            } else {
                orders = orderRepository.findByUserId(userId);
            }

            List<Map<String, Object>> result = orders.stream()
                    .map(o -> Map.<String, Object>of(
                            "order_id", o.getId(),
                            "order_no", o.getOrderNo(),
                            "item", o.getItemName(),
                            "status", o.getStatus(),
                            "amount", o.getAmount().toString()
                    ))
                    .collect(Collectors.toList());

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\": \"Failed to query orders: " + e.getMessage() + "\"}";
        }
    }
}
