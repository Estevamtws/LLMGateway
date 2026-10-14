package com.llmgateway.usage;

public enum UsageStatus {
    SUCCESS,
    PROVIDER_ERROR,
    TIMEOUT,
    /** Rejected by an open circuit breaker without calling the provider. */
    CIRCUIT_OPEN
}
