package com.llmgateway.auth;

import com.llmgateway.client.ApiKey;
import com.llmgateway.client.ApiKeyRepository;
import com.llmgateway.client.ClientStatus;
import com.llmgateway.config.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an {@code Authorization: Bearer <key>} header to an active key of an active client.
 */
@Component
public class ApiKeyAuthenticator {

    private static final String BEARER = "Bearer ";

    private final ApiKeyRepository apiKeys;

    public ApiKeyAuthenticator(ApiKeyRepository apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Transactional(readOnly = true)
    public AuthenticatedKey authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw ApiException.unauthorized("missing_api_key", "Missing API key in Authorization header");
        }
        String key = authorizationHeader.substring(BEARER.length()).strip();
        if (key.isEmpty()) {
            throw ApiException.unauthorized("missing_api_key", "Missing API key in Authorization header");
        }
        ApiKey apiKey = apiKeys.findByKeyHash(KeyHasher.sha256Hex(key))
                .filter(ApiKey::isActive)
                .orElseThrow(() -> ApiException.unauthorized("invalid_api_key", "Invalid or revoked API key"));
        if (apiKey.getClient().getStatus() != ClientStatus.ACTIVE) {
            throw ApiException.unauthorized("client_suspended", "Client is suspended");
        }
        return new AuthenticatedKey(apiKey.getId(), apiKey.getClient().getId());
    }
}
