package com.llmgateway.provider.openai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class OpenAiConfiguration {

    @Bean
    @Order(1)
    @Conditional(ApiKeyConfigured.class)
    OpenAiProvider openAiProvider(OpenAiProperties properties) {
        if (properties.models().isEmpty()) {
            throw new IllegalStateException("OPENAI_API_KEY is set but OPENAI_MODELS is empty; "
                    + "list the models to serve, separated by commas");
        }
        return new OpenAiProvider(properties);
    }

    static class ApiKeyConfigured implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty("gateway.providers.openai.api-key"));
        }
    }
}
