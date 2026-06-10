package com.ailux.backend.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "quota_usage")
public class QuotaUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "request_count")
    private Integer requestCount = 0;

    @Column(name = "token_count")
    private Integer tokenCount = 0;

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public Integer getRequestCount() { return requestCount; }
    public void setRequestCount(Integer requestCount) { this.requestCount = requestCount; }

    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
}
