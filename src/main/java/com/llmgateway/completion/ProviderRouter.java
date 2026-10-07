package com.llmgateway.completion;

import com.llmgateway.config.ApiException;
import com.llmgateway.provider.LlmProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 1 routing: the first provider, in bean order, that supports the requested model.
 */
@Component
public class ProviderRouter {

    private final List<LlmProvider> providers;

    public ProviderRouter(List<LlmProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public LlmProvider route(String model) {
        return providers.stream()
                .filter(provider -> provider.supports(model))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("unknown_model", "Unknown model '" + model + "'"));
    }
}
