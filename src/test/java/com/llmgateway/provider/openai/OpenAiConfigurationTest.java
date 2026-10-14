package com.llmgateway.provider.openai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, OpenAiConfiguration.class);

    @Test
    void disabledWithoutApiKey() {
        runner.withPropertyValues("gateway.providers.openai.api-key=", "gateway.providers.openai.models=gpt-test")
                .run(context -> assertThat(context).doesNotHaveBean(OpenAiProvider.class));
    }

    @Test
    void enabledWithApiKeyAndModels() {
        runner.withPropertyValues("gateway.providers.openai.api-key=k", "gateway.providers.openai.models=a, b")
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenAiProvider.class);
                    assertThat(context.getBean(OpenAiProvider.class).models()).containsExactlyInAnyOrder("a", "b");
                });
    }

    @Test
    void failsWhenApiKeyIsSetWithoutModels() {
        runner.withPropertyValues("gateway.providers.openai.api-key=k")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("OPENAI_MODELS"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OpenAiProperties.class)
    static class PropertiesConfig {
    }
}
