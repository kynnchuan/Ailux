package com.ailux.backend.tool;

import com.ailux.backend.model.Order;
import com.ailux.backend.repository.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class QueryOrdersTool extends AbstractServerTool {

    private final OrderRepository orderRepository;

    public QueryOrdersTool(OrderRepository orderRepository, ObjectMapper objectMapper) {
        super(objectMapper);
        this.orderRepository = orderRepository;
    }

    @Override
    public String name() {
        return "query_orders";
    }

    @Override
    public String description() {
        return "查询用户的订单列表，可按状态筛选";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "status", Map.of(
                                "type", "string",
                                "description", "订单状态筛选: pending(待发货), shipped(已发货), delivered(已签收)",
                                "enum", List.of("pending", "shipped", "delivered")
                        )
                ),
                "required", List.of()
        );
    }

    @Override
    protected String doExecute(Map<String, Object> args, String userId) throws Exception {
        String status = (String) args.get("status");

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

        return toJson(result);
    }
}
