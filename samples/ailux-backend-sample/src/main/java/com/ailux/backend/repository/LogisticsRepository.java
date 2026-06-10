package com.ailux.backend.repository;

import com.ailux.backend.model.Logistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LogisticsRepository extends JpaRepository<Logistics, Long> {
    Optional<Logistics> findByOrderId(String orderId);
}
