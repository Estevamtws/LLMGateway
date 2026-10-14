package com.llmgateway.provider.openai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.llmgateway.provider.ChatMessage;
import com.llmgateway.provider.ChatRequest;
import com.llmgateway.provider.ChatResult;
import com.llmgateway.provider.ProviderException;
import com.llmgateway.provider.ProviderTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.ServerSocket;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiProviderTest {

    private static final String PATH = "/v1/chat/completions";
    private static final String SUCCESS = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-test",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Hi there"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14},
             "system_fingerprint":"fp_x"}""";

    private WireMockServer server;
    private OpenAiProvider provider;

    @BeforeEach
    void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        provider = providerFor(server.baseUrl() + "/v1/", 300);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void sendsRequestInOpenAiFormatAndUsesProviderTokenCounts() {
        server.stubFor(post(PATH).willReturn(okJson(SUCCESS)));

        ChatResult result = provider.complete(new ChatRequest("gpt-test",
                List.of(new ChatMessage("system", "Be brief"), new ChatMessage("user", "Hello")), 50, 0.2));

        assertThat(result).isEqualTo(new ChatResult("Hi there", "stop", 11, 3));
        server.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer secret-key"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"model":"gpt-test",
                         "messages":[{"role":"system","content":"Be brief"},{"role":"user","content":"Hello"}],
                         "max_tokens":50,"temperature":0.2}""")));
    }

    @Test
    void omitsUnsetOptionalFields() {
        server.stubFor(post(PATH).willReturn(okJson(SUCCESS)));

        provider.complete(request());

        server.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"model":"gpt-test","messages":[{"role":"user","content":"Hello"}]}""")));
    }

    @ParameterizedTest
    @CsvSource({"500, true", "502, true", "503, true", "429, true", "408, true",
            "400, false", "401, false", "403, false", "404, false", "422, false"})
    void mapsHttpErrorsToProviderErrors(int status, boolean retryable) {
        server.stubFor(post(PATH).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":{\"message\":\"nope\"}}")));

        assertThatThrownBy(() -> provider.complete(request()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.isRetryable()).isEqualTo(retryable);
                    assertThat(e.getMessage()).contains(String.valueOf(status));
                });
    }

    @Test
    void slowResponseIsTimeout() {
        server.stubFor(post(PATH).willReturn(okJson(SUCCESS).withFixedDelay(2000)));

        assertThatThrownBy(() -> provider.complete(request())).isInstanceOf(ProviderTimeoutException.class);
    }

    @Test
    void responseWithoutUsageIsAnErrorBecauseTokensAreNeverEstimated() {
        server.stubFor(post(PATH).willReturn(okJson("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]}""")));

        assertThatThrownBy(() -> provider.complete(request()))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessageContaining("usage");
    }

    @Test
    void responseWithoutChoicesIsAnError() {
        server.stubFor(post(PATH).willReturn(okJson("""
                {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":0}}""")));

        assertThatThrownBy(() -> provider.complete(request())).isInstanceOf(ProviderException.class);
    }

    @Test
    void unreadableResponseIsAnError() {
        server.stubFor(post(PATH).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{not json")));

        assertThatThrownBy(() -> provider.complete(request()))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.isRetryable()).isFalse());
    }

    @Test
    void nullContentBecomesEmptyString() {
        server.stubFor(post(PATH).willReturn(okJson("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":null},"finish_reason":"length"}],
                 "usage":{"prompt_tokens":5,"completion_tokens":0}}""")));

        assertThat(provider.complete(request())).isEqualTo(new ChatResult("", "length", 5, 0));
    }

    @Test
    void unreachableProviderIsRetryableError() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        OpenAiProvider unreachable = providerFor("http://localhost:" + closedPort + "/v1", 300);

        assertThatThrownBy(() -> unreachable.complete(request()))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void servesOnlyConfiguredModels() {
        assertThat(provider.name()).isEqualTo("openai");
        assertThat(provider.supports("gpt-test")).isTrue();
        assertThat(provider.supports("fake-fast")).isFalse();
    }

    private static OpenAiProvider providerFor(String baseUrl, long readTimeoutMs) {
        return new OpenAiProvider(new OpenAiProperties("openai", baseUrl, "secret-key",
                List.of("gpt-test", " other "), 500, readTimeoutMs));
    }

    private static ChatRequest request() {
        return new ChatRequest("gpt-test", List.of(new ChatMessage("user", "Hello")), null, null);
    }
}
