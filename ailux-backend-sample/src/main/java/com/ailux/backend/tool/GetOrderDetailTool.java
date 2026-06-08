package com.ailux.backend.tool;

import com.ailux.backend.model.Order;
import com.ailux.backend.repository.OrderRepository;
import com.ailux.backend.service.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class GetOrderDetailTool implements ToolExecutor.ServerTool {

    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    public GetOrderDetailTool(OrderRepository orderRepository, ObjectMapper objectMapper) {
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

            Optional<Order> orderOpt = orderRepository.findById(orderId);
            if (orderOpt.isEmpty()) {
                return "{\"error\": \"Order not found: " + orderId + "\"}";
            }

            Order order = orderOpt.get();
            // Security check: user can only see their own orders
            if (!order.getUserId().equals(userId)) {
                return "{\"error\": \"Access denied\"}";
            }

            Map<String, Object> result = Map.of(
                    "order_id", order.getId(),
                    "order_no", order.getOrderNo(),
                    "item_name", order.getItemName(),
                    "status", order.getStatus(),
                    "amount", order.getAmount().toString(),
                    "created_at", order.getCreatedAt().toString()
            );

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\": \"Failed to get order detail: " + e.getMessage() + "\"}";
        }
    }
}
