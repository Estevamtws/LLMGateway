package com.llmgateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error, including Spring MVC's own, in the common error body.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(ErrorResponse.of(ex.getMessage(), ex.type(), ex.code()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "Invalid value for parameter '" + ex.getName() + "'", "invalid_request_error", "invalid_parameter"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError().body(ErrorResponse.of(
                "Internal server error", "api_error", "internal_error"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        String type = statusCode.is4xxClientError() ? "invalid_request_error" : "api_error";
        String message = statusCode.value() == 400 ? "Invalid request body" : ex.getMessage();
        ErrorResponse error = ErrorResponse.of(message, type, codeFor(statusCode));
        return ResponseEntity.status(statusCode).headers(headers).body(error);
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "invalid_request";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 406 -> "not_acceptable";
            case 415 -> "unsupported_media_type";
            default -> "error";
        };
    }
}
