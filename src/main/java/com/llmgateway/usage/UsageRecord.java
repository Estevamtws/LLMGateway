package com.llmgateway.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadata about one completion request. Prompt and response content are never stored (D6).
 */
@Entity
@Table(name = "usage_record")
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "api_key_id", nullable = false)
    private UUID apiKeyId;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cost_usd", nullable = false, precision = 18, scale = 8)
    private BigDecimal costUsd;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UsageStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UsageRecord() {
    }

    public UsageRecord(UUID apiKeyId, String provider, String model, int inputTokens, int outputTokens,
                       BigDecimal costUsd, int latencyMs, UsageStatus status, Instant createdAt) {
        this.apiKeyId = apiKeyId;
        this.provider = provider;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.costUsd = costUsd;
        this.latencyMs = latencyMs;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApiKeyId() {
        return apiKeyId;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public UsageStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
