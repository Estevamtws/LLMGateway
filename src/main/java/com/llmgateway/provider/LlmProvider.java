package com.llmgateway.provider;

/**
 * A backend that answers chat completions. Implementations translate the internal types to and
 * from their own wire format.
 */
public interface LlmProvider {

    String name();

    boolean supports(String model);

    /**
     * @throws ProviderException        if the provider returned an error
     * @throws ProviderTimeoutException if the provider did not answer in time
     */
    ChatResult complete(ChatRequest request);
}
