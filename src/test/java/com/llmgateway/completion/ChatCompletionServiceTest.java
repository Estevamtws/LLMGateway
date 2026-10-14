package com.llmgateway.completion;

import com.llmgateway.auth.AuthenticatedKey;
import com.llmgateway.config.ApiException;
import com.llmgateway.provider.ChatRequest;
import com.llmgateway.provider.ChatResult;
import com.llmgateway.provider.LlmProvider;
import com.llmgateway.provider.ProviderUnavailableException;
import com.llmgateway.provider.resilience.ResilientProviderCaller;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatCompletionServiceTest {

    @Test
    void openCircuitReturns503() {
        LlmProvider provider = new LlmProvider() {
            @Override
            public String name() {
                return "p";
            }

            @Override
            public boolean supports(String model) {
                return true;
            }

            @Override
            public ChatResult complete(ChatRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        ResilientProviderCaller caller = mock(ResilientProviderCaller.class);
        when(caller.complete(any(), any())).thenThrow(new ProviderUnavailableException("open", null));
        ChatCompletionService service =
                new ChatCompletionService(new ProviderRouter(List.of(provider)), caller, Clock.systemUTC());
        var request = new ChatCompletionRequest("m",
                List.of(new ChatCompletionRequest.Message("user", "Hi")), null, null, null);

        assertThatThrownBy(() -> service.complete(new AuthenticatedKey(UUID.randomUUID(), UUID.randomUUID()), request))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.type()).isEqualTo("api_error");
                    assertThat(e.code()).isEqualTo("provider_unavailable");
                });
    }
}
