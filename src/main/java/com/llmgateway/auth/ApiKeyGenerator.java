package com.llmgateway.auth;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates gateway API keys: {@code gw_} followed by 32 random bytes, base64url without padding.
 */
@Component
public class ApiKeyGenerator {

    public static final String KEY_PREFIX = "gw_";
    static final int RANDOM_BYTES = 32;
    static final int DISPLAY_PREFIX_LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    public GeneratedKey generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        String key = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new GeneratedKey(key, KeyHasher.sha256Hex(key), key.substring(0, DISPLAY_PREFIX_LENGTH));
    }

    /**
     * A freshly generated key. {@code plaintext} is returned to the caller once and never stored.
     */
    public record GeneratedKey(String plaintext, String hash, String displayPrefix) {
    }
}
