package com.llmgateway.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeyHasherTest {

    @Test
    void matchesKnownSha256Vector() {
        assertThat(KeyHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void producesLowercaseHexOf64Characters() {
        assertThat(KeyHasher.sha256Hex("gw_example")).matches("[0-9a-f]{64}");
    }
}
