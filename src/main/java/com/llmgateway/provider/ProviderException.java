package com.llmgateway.provider;

/**
 * The provider answered with an error, or with a response the gateway cannot use.
 */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
