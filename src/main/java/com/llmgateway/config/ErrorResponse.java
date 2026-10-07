package com.llmgateway.config;

/**
 * The single error body used by every endpoint.
 */
public record ErrorResponse(Error error) {

    public record Error(String message, String type, String code) {
    }

    public static ErrorResponse of(String message, String type, String code) {
        return new ErrorResponse(new Error(message, type, code));
    }
}
