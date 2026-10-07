package com.llmgateway.provider;

/**
 * The provider did not answer within the configured timeout.
 */
public class ProviderTimeoutException extends RuntimeException {

    public ProviderTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
