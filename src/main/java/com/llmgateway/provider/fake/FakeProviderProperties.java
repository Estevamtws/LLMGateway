package com.llmgateway.provider.fake;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * @param latencyMs simulated latency per model, in milliseconds
 * @param errorRate probability from 0.0 to 1.0 that a call fails with a provider error
 */
@ConfigurationProperties("gateway.providers.fake")
public record FakeProviderProperties(Map<String, Long> latencyMs, double errorRate) {

    public FakeProviderProperties {
        latencyMs = latencyMs == null ? Map.of() : Map.copyOf(latencyMs);
        if (latencyMs.values().stream().anyMatch(ms -> ms == null || ms < 0)) {
            throw new IllegalArgumentException("gateway.providers.fake.latency-ms values must be >= 0");
        }
        if (errorRate < 0.0 || errorRate > 1.0) {
            throw new IllegalArgumentException("gateway.providers.fake.error-rate must be between 0.0 and 1.0");
        }
    }
}
