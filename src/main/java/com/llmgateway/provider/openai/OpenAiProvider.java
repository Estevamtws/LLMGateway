package com.llmgateway.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.llmgateway.provider.ChatRequest;
import com.llmgateway.provider.ChatResult;
import com.llmgateway.provider.LlmProvider;
import com.llmgateway.provider.ProviderException;
import com.llmgateway.provider.ProviderTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.ProxySelector;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Client for the OpenAI chat completions API and compatible services. Token counts always come
 * from the provider's {@code usage} field, never from an estimate.
 */
public class OpenAiProvider implements LlmProvider {

    private final String name;
    private final Set<String> models;
    private final RestClient restClient;

    public OpenAiProvider(OpenAiProperties properties) {
        this.name = properties.name();
        this.models = Set.copyOf(new LinkedHashSet<>(properties.models()));
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .proxy(ProxySelector.getDefault())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.baseUrl()))
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<String> models() {
        return models;
    }

    @Override
    public ChatResult complete(ChatRequest request) {
        WireResponse response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(WireRequest.from(request))
                    .retrieve()
                    .body(WireResponse.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            throw new ProviderException("Provider returned HTTP " + status, isRetryable(status), e);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new ProviderTimeoutException("Provider did not respond in time", e);
            }
            throw new ProviderException("Could not reach provider", true, e);
        } catch (RestClientException e) {
            throw new ProviderException("Provider returned an unreadable response", false, e);
        }
        return toResult(response);
    }

    private static ChatResult toResult(WireResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new ProviderException("Provider response has no choices", false, null);
        }
        WireUsage usage = response.usage();
        if (usage == null || usage.promptTokens() == null || usage.completionTokens() == null) {
            throw new ProviderException("Provider response has no token usage", false, null);
        }
        WireChoice choice = response.choices().getFirst();
        String content = choice.message() == null || choice.message().content() == null
                ? "" : choice.message().content();
        String finishReason = choice.finishReason() == null ? "stop" : choice.finishReason();
        return new ChatResult(content, finishReason, usage.promptTokens(), usage.completionTokens());
    }

    /**
     * Rate limits, conflicts, request timeouts and server errors may succeed on a later attempt;
     * other client errors would fail the same way again.
     */
    static boolean isRetryable(int status) {
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireRequest(@JsonProperty("model") String model,
                       @JsonProperty("messages") List<WireMessage> messages,
                       @JsonProperty("max_tokens") Integer maxTokens,
                       @JsonProperty("temperature") Double temperature) {

        static WireRequest from(ChatRequest request) {
            List<WireMessage> messages = request.messages().stream()
                    .map(m -> new WireMessage(m.role(), m.content()))
                    .toList();
            return new WireRequest(request.model(), messages, request.maxTokens(), request.temperature());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireMessage(@JsonProperty("role") String role, @JsonProperty("content") String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireResponse(@JsonProperty("choices") List<WireChoice> choices, @JsonProperty("usage") WireUsage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireChoice(@JsonProperty("message") WireMessage message,
                      @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireUsage(@JsonProperty("prompt_tokens") Integer promptTokens,
                     @JsonProperty("completion_tokens") Integer completionTokens) {
    }
}
