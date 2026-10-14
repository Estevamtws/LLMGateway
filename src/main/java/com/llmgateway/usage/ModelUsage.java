package com.llmgateway.usage;

import java.math.BigDecimal;

/**
 * Usage totals for one model.
 */
public record ModelUsage(String model, Long requests, Long inputTokens, Long outputTokens, BigDecimal costUsd) {
}
