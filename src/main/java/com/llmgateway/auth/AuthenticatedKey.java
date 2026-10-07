package com.llmgateway.auth;

import java.util.UUID;

/**
 * The caller of a public endpoint, resolved from its gateway API key.
 */
public record AuthenticatedKey(UUID apiKeyId, UUID clientId) {

    public static final String REQUEST_ATTRIBUTE = "com.llmgateway.auth.AuthenticatedKey";
}
