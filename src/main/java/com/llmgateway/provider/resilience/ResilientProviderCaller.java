package com.llmgateway.provider.resilience;

import com.llmgateway.provider.ChatRequest;
import com.llmgateway.provider.ChatResult;
import com.llmgateway.provider.LlmProvider;
import com.llmgateway.provider.ProviderException;
import com.llmgateway.provider.ProviderTimeoutException;
import com.llmgateway.provider.ProviderUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Calls a provider through a retry and a circuit breaker, both kept per provider name.
 * The retry wraps the circuit breaker, so every attempt counts towards the failure rate and an
 * open circuit stops further attempts immediately.
 */
@Component
public class ResilientProviderCaller {

    private static final Logger log = LoggerFactory.getLogger(ResilientProviderCaller.class);

    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;

    public ResilientProviderCaller(ResilienceProperties properties) {
        this.circuitBreakers = CircuitBreakerRegistry.of(circuitBreakerConfig(properties.circuitBreaker()));
        this.retries = RetryRegistry.of(retryConfig(properties.retry()));

        circuitBreakers.getEventPublisher().onEntryAdded(event -> event.getAddedEntry().getEventPublisher()
                .onStateTransition(e -> log.warn("Circuit breaker for provider {}: {}",
                        e.getCircuitBreakerName(), e.getStateTransition())));
        retries.getEventPublisher().onEntryAdded(event -> event.getAddedEntry().getEventPublisher()
                .onRetry(e -> log.info("Retrying provider {} (attempt {}) after: {}",
                        e.getName(), e.getNumberOfRetryAttempts() + 1, e.getLastThrowable().getMessage())));
    }

    public ChatResult complete(LlmProvider provider, ChatRequest request) {
        CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(provider.name());
        Retry retry = retries.retry(provider.name());
        Supplier<ChatResult> call = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, () -> provider.complete(request)));
        try {
            return call.get();
        } catch (CallNotPermittedException e) {
            throw new ProviderUnavailableException("Circuit breaker is open for provider " + provider.name(), e);
        }
    }

    CircuitBreaker.State state(String providerName) {
        return circuitBreakers.circuitBreaker(providerName).getState();
    }

    private static CircuitBreakerConfig circuitBreakerConfig(ResilienceProperties.CircuitBreaker p) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(p.slidingWindowSize())
                .minimumNumberOfCalls(p.minimumNumberOfCalls())
                .failureRateThreshold(p.failureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(p.waitInOpenMs()))
                .permittedNumberOfCallsInHalfOpenState(p.permittedCallsInHalfOpen())
                .recordException(ResilientProviderCaller::isProviderFailure)
                .build();
    }

    private static RetryConfig retryConfig(ResilienceProperties.Retry p) {
        return RetryConfig.custom()
                .maxAttempts(p.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        Duration.ofMillis(p.initialWaitMs()), p.multiplier()))
                .retryOnException(e -> e instanceof ProviderTimeoutException
                        ? p.retryOnTimeout()
                        : e instanceof ProviderException pe && pe.isRetryable())
                .build();
    }

    /**
     * Timeouts and retryable errors say something about the provider's health; errors caused by the
     * request itself do not, so they never open the circuit.
     */
    private static boolean isProviderFailure(Throwable e) {
        return e instanceof ProviderTimeoutException || e instanceof ProviderException pe && pe.isRetryable();
    }
}
