package com.ailux.backend.tool;

import com.ailux.backend.model.Logistics;
import com.ailux.backend.model.Order;
import com.ailux.backend.repository.LogisticsRepository;
import com.ailux.backend.repository.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class GetLogisticsTool extends AbstractServerTool {

    private final LogisticsRepository logisticsRepository;
    private final OrderRepository orderRepository;

    public GetLogisticsTool(LogisticsRepository logisticsRepository,
                            OrderRepository orderRepository,
                            ObjectMapper objectMapper) {
        super(objectMapper);
        this.logisticsRepository = logisticsRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public String name() {
        return "get_logistics";
    }

    @Override
    public String description() {
        return "查询订单的物流状态和配送进度";
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

        // Security check: user can only see their own order's logistics
        Optional<Order> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty() || !orderOpt.get().getUserId().equals(userId)) {
            return errorJson("Order not found or access denied");
        }

        Optional<Logistics> logisticsOpt = logisticsRepository.findByOrderId(orderId);
        if (logisticsOpt.isEmpty()) {
            return errorJson("No logistics info for order: " + orderId);
        }

        Logistics logistics = logisticsOpt.get();
        Map<String, Object> result = new HashMap<>();
        result.put("order_id", orderId);
        result.put("status", logistics.getStatus());
        result.put("location", logistics.getLocation());
        result.put("eta", logistics.getEta());
        result.put("updated_at", logistics.getUpdatedAt() != null ? logistics.getUpdatedAt().toString() : null);

        return toJson(result);
    }
}
