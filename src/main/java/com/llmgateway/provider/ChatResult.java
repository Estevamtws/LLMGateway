package com.llmgateway.provider;

/**
 * Provider-independent chat result.
 */
public record ChatResult(String content, String finishReason, int inputTokens, int outputTokens) {
}
