package com.llmgateway.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

/**
 * Stores one usage record per completion request. Only metadata is stored (D6).
 *
 * <p>Decision O1: if saving fails, the client still receives its response. Availability comes
 * first; the lost record is logged at ERROR with all its fields so it can be reconciled.
 */
@Service
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    private final UsageRecordRepository repository;
    private final CostCalculator costCalculator;
    private final Clock clock;

    public UsageRecorder(UsageRecordRepository repository, CostCalculator costCalculator, Clock clock) {
        this.repository = repository;
        this.costCalculator = costCalculator;
        this.clock = clock;
    }

    public void record(UUID apiKeyId, String provider, String model, int inputTokens, int outputTokens,
                       long latencyMs, UsageStatus status) {
        BigDecimal cost = costCalculator.cost(model, inputTokens, outputTokens);
        int latency = (int) Math.min(latencyMs, Integer.MAX_VALUE);
        try {
            repository.save(new UsageRecord(apiKeyId, provider, model, inputTokens, outputTokens, cost, latency,
                    status, clock.instant()));
        } catch (RuntimeException e) {
            log.error("Failed to save usage record (apiKeyId={}, provider={}, model={}, inputTokens={}, "
                            + "outputTokens={}, costUsd={}, latencyMs={}, status={})",
                    apiKeyId, provider, model, inputTokens, outputTokens, cost.toPlainString(), latency, status, e);
        }
    }
}
