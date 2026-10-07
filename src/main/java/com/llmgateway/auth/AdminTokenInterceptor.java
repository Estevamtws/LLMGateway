package com.llmgateway.auth;

import com.llmgateway.config.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Checks the {@code X-Admin-Token} header on admin routes. A deliberate phase 1 simplification.
 */
@Component
public class AdminTokenInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Token";

    private final byte[] expected;

    public AdminTokenInterceptor(AdminProperties properties) {
        this.expected = properties.token().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized("invalid_admin_token", "Missing or invalid admin token");
        }
        return true;
    }
}
