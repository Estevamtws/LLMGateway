package com.llmgateway.provider;

/**
 * The provider answered with an error, or with a response the gateway cannot use.
 * {@code retryable} is false for errors that would fail the same way again, such as the provider
 * rejecting the request itself; those are neither retried nor counted by the circuit breaker.
 */
public class ProviderException extends RuntimeException {

    private final boolean retryable;

    public ProviderException(String message) {
        this(message, true, null);
    }

    public ProviderException(String message, Throwable cause) {
        this(message, true, cause);
    }

    public ProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
