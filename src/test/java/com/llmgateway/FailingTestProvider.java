package com.llmgateway;

import com.llmgateway.provider.ChatRequest;
import com.llmgateway.provider.ChatResult;
import com.llmgateway.provider.LlmProvider;
import com.llmgateway.provider.ProviderException;
import com.llmgateway.provider.ProviderTimeoutException;

/**
 * Test-only provider: {@code test-error} fails with a provider error, {@code test-timeout} times out.
 */
public class FailingTestProvider implements LlmProvider {

    public static final String ERROR_MODEL = "test-error";
    public static final String TIMEOUT_MODEL = "test-timeout";

    @Override
    public String name() {
        return "test";
    }

    @Override
    public boolean supports(String model) {
        return ERROR_MODEL.equals(model) || TIMEOUT_MODEL.equals(model);
    }

    @Override
    public ChatResult complete(ChatRequest request) {
        if (TIMEOUT_MODEL.equals(request.model())) {
            throw new ProviderTimeoutException("Simulated timeout", null);
        }
        throw new ProviderException("Simulated error");
    }
}
