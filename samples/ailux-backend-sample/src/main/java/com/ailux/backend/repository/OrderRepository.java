package com.ailux.backend.repository;

import com.ailux.backend.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByUserId(String userId);
    List<Order> findByUserIdAndStatus(String userId, String status);
    Optional<Order> findByOrderNo(String orderNo);
}
