package com.llmgateway.provider;

/**
 * The provider's circuit breaker is open, so the call was rejected without reaching the provider.
 */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
