package com.llmgateway.config;

import org.springframework.http.HttpStatus;

/**
 * An error that maps directly to the public error body: status, type, and code.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String code;

    public ApiException(HttpStatus status, String type, String code, String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request_error", code, message);
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "authentication_error", code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "invalid_request_error", code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, "invalid_request_error", code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String code() {
        return code;
    }
}
