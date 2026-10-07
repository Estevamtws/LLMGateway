package com.llmgateway.auth;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyGeneratorTest {

    private final ApiKeyGenerator generator = new ApiKeyGenerator();

    @Test
    void keyHasGatewayPrefixAnd32RandomBytesInBase64Url() {
        ApiKeyGenerator.GeneratedKey key = generator.generate();

        assertThat(key.plaintext()).startsWith("gw_").matches("gw_[A-Za-z0-9_-]{43}");
        byte[] decoded = Base64.getUrlDecoder().decode(key.plaintext().substring(3));
        assertThat(decoded).hasSize(32);
    }

    @Test
    void hashIsSha256OfPlaintextAndPrefixIsItsStart() {
        ApiKeyGenerator.GeneratedKey key = generator.generate();

        assertThat(key.hash()).isEqualTo(KeyHasher.sha256Hex(key.plaintext())).hasSize(64);
        assertThat(key.displayPrefix()).hasSize(12);
        assertThat(key.plaintext()).startsWith(key.displayPrefix());
    }

    @Test
    void keysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            keys.add(generator.generate().plaintext());
        }
        assertThat(keys).hasSize(1000);
    }
}
