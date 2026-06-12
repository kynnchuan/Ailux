package com.ailux.backend.tool;

import com.ailux.backend.model.Order;
import com.ailux.backend.repository.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class GetOrderDetailTool extends AbstractServerTool {

    private final OrderRepository orderRepository;

    public GetOrderDetailTool(OrderRepository orderRepository, ObjectMapper objectMapper) {
        super(objectMapper);
        this.orderRepository = orderRepository;
    }

    @Override
    public String name() {
        return "get_order_detail";
    }

    @Override
    public String description() {
        return "查询指定订单的详细信息";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "order_id", Map.of(
                                "type", "string",
                                "description", "订单ID"
                        )
                ),
                "required", List.of("order_id")
        );
    }

    @Override
    protected String doExecute(Map<String, Object> args, String userId) throws Exception {
        String orderId = (String) args.get("order_id");
        if (orderId == null || orderId.isEmpty()) {
            return errorJson("order_id is required");
        }

        Optional<Order> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty()) {
            return errorJson("Order not found: " + orderId);
        }

        Order order = orderOpt.get();
        // Security check: user can only see their own orders
        if (!order.getUserId().equals(userId)) {
            return errorJson("Access denied");
        }

        Map<String, Object> result = Map.of(
                "order_id", order.getId(),
                "order_no", order.getOrderNo(),
                "item_name", order.getItemName(),
                "status", order.getStatus(),
                "amount", order.getAmount().toString(),
                "created_at", order.getCreatedAt().toString()
        );

        return toJson(result);
    }
}
