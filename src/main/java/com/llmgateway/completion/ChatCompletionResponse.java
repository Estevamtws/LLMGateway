package com.llmgateway.completion;

import java.util.List;

/**
 * OpenAI-compatible chat completion response.
 */
public record ChatCompletionResponse(String id, String object, long created, String model, List<Choice> choices,
                                     Usage usage) {

    public record Choice(int index, Message message, String finishReason) {
    }

    public record Message(String role, String content) {
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    }
}
