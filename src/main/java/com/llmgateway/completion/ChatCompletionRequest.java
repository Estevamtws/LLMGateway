package com.llmgateway.completion;

import java.util.List;

/**
 * The supported subset of the OpenAI chat completions request. Unknown fields are ignored.
 */
public record ChatCompletionRequest(String model, List<Message> messages, Integer maxTokens, Double temperature,
                                    Boolean stream) {

    public record Message(String role, String content) {
    }
}
