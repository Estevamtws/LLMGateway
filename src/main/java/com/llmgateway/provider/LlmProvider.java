package com.llmgateway.provider;

import java.util.Set;

/**
 * A backend that answers chat completions. Implementations translate the internal types to and
 * from their own wire format.
 */
public interface LlmProvider {

    String name();

    /**
     * Every model this provider serves. Used at startup to check that each one has a price.
     */
    Set<String> models();

    default boolean supports(String model) {
        return models().contains(model);
    }

    /**
     * @throws ProviderException        if the provider returned an error
     * @throws ProviderTimeoutException if the provider did not answer in time
     */
    ChatResult complete(ChatRequest request);
}
