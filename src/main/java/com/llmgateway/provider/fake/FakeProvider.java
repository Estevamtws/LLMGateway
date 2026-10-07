package com.llmgateway.provider.fake;

import com.llmgateway.provider.ChatMessage;
import com.llmgateway.provider.ChatRequest;
import com.llmgateway.provider.ChatResult;
import com.llmgateway.provider.LlmProvider;
import com.llmgateway.provider.ProviderException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Deterministic provider for tests and load tests. Never calls the network.
 */
@Component
@Order(0)
public class FakeProvider implements LlmProvider {

    public static final List<String> MODELS = List.of("fake-fast", "fake-slow");

    private final FakeProviderProperties properties;

    public FakeProvider(FakeProviderProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public boolean supports(String model) {
        return MODELS.contains(model);
    }

    @Override
    public ChatResult complete(ChatRequest request) {
        simulateLatency(request.model());
        if (properties.errorRate() > 0 && ThreadLocalRandom.current().nextDouble() < properties.errorRate()) {
            throw new ProviderException("Simulated provider error");
        }

        int inputTokens = estimateTokens(request.messages().stream().mapToInt(m -> length(m.content())).sum());
        String content = "[" + request.model() + "] You said: " + lastUserContent(request.messages());
        String finishReason = "stop";
        if (request.maxTokens() != null && estimateTokens(content.length()) > request.maxTokens()) {
            content = content.substring(0, Math.max(0, request.maxTokens()) * 4);
            finishReason = "length";
        }
        return new ChatResult(content, finishReason, inputTokens, estimateTokens(content.length()));
    }

    /**
     * Character count divided by 4, rounded up.
     */
    static int estimateTokens(int characters) {
        return (characters + 3) / 4;
    }

    private void simulateLatency(String model) {
        long latency = properties.latencyMs().getOrDefault(model, 0L);
        if (latency == 0) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted while simulating latency", e);
        }
    }

    private static String lastUserContent(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return nullToEmpty(messages.get(i).content());
            }
        }
        return "";
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
