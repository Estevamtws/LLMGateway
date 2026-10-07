package com.llmgateway.completion;

import com.llmgateway.config.ApiException;
import com.llmgateway.provider.ChatRequest;
import com.llmgateway.provider.ChatResult;
import com.llmgateway.provider.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRouterTest {

    @Test
    void routesToFirstProviderSupportingModel() {
        LlmProvider first = new StubProvider("first", Set.of("a"));
        LlmProvider second = new StubProvider("second", Set.of("a", "b"));
        ProviderRouter router = new ProviderRouter(List.of(first, second));

        assertThat(router.route("a")).isSameAs(first);
        assertThat(router.route("b")).isSameAs(second);
    }

    @Test
    void unknownModelIsBadRequest() {
        ProviderRouter router = new ProviderRouter(List.of(new StubProvider("only", Set.of("a"))));

        assertThatThrownBy(() -> router.route("nope"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("unknown_model");
                });
    }

    private record StubProvider(String name, Set<String> models) implements LlmProvider {

        @Override
        public boolean supports(String model) {
            return models.contains(model);
        }

        @Override
        public ChatResult complete(ChatRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
