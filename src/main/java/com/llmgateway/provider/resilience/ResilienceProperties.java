package com.llmgateway.provider.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retry and circuit breaker settings, applied to each provider separately.
 */
@ConfigurationProperties("gateway.resilience")
public record ResilienceProperties(@DefaultValue Retry retry, @DefaultValue CircuitBreaker circuitBreaker) {

    /**
     * @param maxAttempts    total attempts including the first one; 1 disables retries
     * @param initialWaitMs  wait before the first retry, in milliseconds
     * @param multiplier     backoff multiplier applied to the wait after each retry
     * @param retryOnTimeout whether provider timeouts are retried; off by default because each
     *                       attempt can take up to the full read timeout
     */
    public record Retry(@DefaultValue("3") int maxAttempts,
                        @DefaultValue("200") long initialWaitMs,
                        @DefaultValue("2.0") double multiplier,
                        @DefaultValue("false") boolean retryOnTimeout) {
    }

    /**
     * @param failureRateThreshold     failure percentage that opens the circuit
     * @param slidingWindowSize        number of most recent calls the failure rate is computed over
     * @param minimumNumberOfCalls     calls needed before the failure rate is evaluated
     * @param waitInOpenMs             how long the circuit stays open before letting test calls through
     * @param permittedCallsInHalfOpen test calls allowed while half-open
     */
    public record CircuitBreaker(@DefaultValue("50") float failureRateThreshold,
                                 @DefaultValue("20") int slidingWindowSize,
                                 @DefaultValue("10") int minimumNumberOfCalls,
                                 @DefaultValue("30000") long waitInOpenMs,
                                 @DefaultValue("3") int permittedCallsInHalfOpen) {
    }
}
