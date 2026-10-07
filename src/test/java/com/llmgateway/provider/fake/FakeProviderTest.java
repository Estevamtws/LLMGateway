package com.llmgateway.provider.fake;

import com.llmgateway.provider.ChatMessage;
import com.llmgateway.provider.ChatRequest;
import com.llmgateway.provider.ChatResult;
import com.llmgateway.provider.ProviderException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeProviderTest {

    private final FakeProvider provider = new FakeProvider(new FakeProviderProperties(Map.of(), 0.0));

    @Test
    void supportsOnlyFakeModels() {
        assertThat(provider.name()).isEqualTo("fake");
        assertThat(provider.supports("fake-fast")).isTrue();
        assertThat(provider.supports("fake-slow")).isTrue();
        assertThat(provider.supports("gpt-4o")).isFalse();
    }

    @Test
    void answerIsDeterministicAndDerivedFromInput() {
        ChatRequest request = request("Hello", null);

        ChatResult first = provider.complete(request);
        ChatResult second = provider.complete(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.content()).isEqualTo("[fake-fast] You said: Hello");
        assertThat(first.finishReason()).isEqualTo("stop");
    }

    @Test
    void estimatesTokensAsCharactersDividedByFourRoundedUp() {
        ChatRequest request = new ChatRequest("fake-fast",
                List.of(new ChatMessage("system", "abcd"), new ChatMessage("user", "Hello")), null, null);

        ChatResult result = provider.complete(request);

        assertThat(result.inputTokens()).isEqualTo(3); // 9 characters
        assertThat(result.outputTokens()).isEqualTo(7); // 27 characters
        assertThat(FakeProvider.estimateTokens(0)).isZero();
        assertThat(FakeProvider.estimateTokens(1)).isEqualTo(1);
        assertThat(FakeProvider.estimateTokens(4)).isEqualTo(1);
        assertThat(FakeProvider.estimateTokens(5)).isEqualTo(2);
    }

    @Test
    void truncatesToMaxTokens() {
        ChatResult result = provider.complete(request("a long message that exceeds the limit", 2));

        assertThat(result.content()).hasSize(8);
        assertThat(result.outputTokens()).isEqualTo(2);
        assertThat(result.finishReason()).isEqualTo("length");
    }

    @Test
    void failsWhenErrorRateIsOne() {
        FakeProvider failing = new FakeProvider(new FakeProviderProperties(Map.of(), 1.0));

        assertThatThrownBy(() -> failing.complete(request("Hello", null))).isInstanceOf(ProviderException.class);
    }

    @Test
    void appliesConfiguredLatency() {
        FakeProvider slow = new FakeProvider(new FakeProviderProperties(Map.of("fake-slow", 100L), 0.0));
        ChatRequest request = new ChatRequest("fake-slow", List.of(new ChatMessage("user", "Hi")), null, null);

        long start = System.nanoTime();
        slow.complete(request);

        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(100);
    }

    @Test
    void rejectsInvalidProperties() {
        assertThatThrownBy(() -> new FakeProviderProperties(Map.of(), 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FakeProviderProperties(Map.of("fake-fast", -1L), 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ChatRequest request(String content, Integer maxTokens) {
        return new ChatRequest("fake-fast", List.of(new ChatMessage("user", content)), maxTokens, null);
    }
}
