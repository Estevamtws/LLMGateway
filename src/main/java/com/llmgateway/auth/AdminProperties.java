package com.llmgateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway.admin")
public record AdminProperties(String token) {

    public AdminProperties {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("gateway.admin.token must be set (ADMIN_TOKEN environment variable)");
        }
    }
}
