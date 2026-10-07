package com.llmgateway.provider;

import java.util.List;

/**
 * Provider-independent chat request. {@code maxTokens} and {@code temperature} may be null.
 */
public record ChatRequest(String model, List<ChatMessage> messages, Integer maxTokens, Double temperature) {

    public ChatRequest {
        messages = List.copyOf(messages);
    }
}
