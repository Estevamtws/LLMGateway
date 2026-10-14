package com.llmgateway.provider.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Settings for any provider that speaks the OpenAI chat completions API (OpenAI, Groq, OpenRouter,
 * DeepSeek, Together, Ollama, ...). The provider is enabled only when {@code apiKey} is set.
 *
 * @param name             provider name stored in usage records
 * @param baseUrl          API base URL, up to but not including {@code /chat/completions}
 * @param apiKey           provider API key
 * @param models           models served through this provider; each needs a price
 * @param connectTimeoutMs connect timeout in milliseconds
 * @param readTimeoutMs    read timeout in milliseconds
 */
@ConfigurationProperties("gateway.providers.openai")
public record OpenAiProperties(@DefaultValue("openai") String name,
                               @DefaultValue("https://api.openai.com/v1") String baseUrl,
                               String apiKey,
                               @DefaultValue List<String> models,
                               @DefaultValue("2000") long connectTimeoutMs,
                               @DefaultValue("30000") long readTimeoutMs) {

    public OpenAiProperties {
        models = models == null ? List.of()
                : models.stream().map(String::strip).filter(model -> !model.isEmpty()).distinct().toList();
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("gateway.providers.openai timeouts must be positive");
        }
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
