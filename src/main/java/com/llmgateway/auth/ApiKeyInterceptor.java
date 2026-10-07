package com.llmgateway.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates public API requests and exposes the caller as a request attribute.
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final ApiKeyAuthenticator authenticator;

    public ApiKeyInterceptor(ApiKeyAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AuthenticatedKey caller = authenticator.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        request.setAttribute(AuthenticatedKey.REQUEST_ATTRIBUTE, caller);
        return true;
    }
}
