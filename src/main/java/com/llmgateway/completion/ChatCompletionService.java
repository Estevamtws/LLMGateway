package com.llmgateway.completion;

import com.llmgateway.auth.AuthenticatedKey;
import com.llmgateway.config.ApiException;
import com.llmgateway.provider.ChatMessage;
import com.llmgateway.provider.ChatRequest;
import com.llmgateway.provider.ChatResult;
import com.llmgateway.provider.LlmProvider;
import com.llmgateway.provider.ProviderException;
import com.llmgateway.provider.ProviderTimeoutException;
import com.llmgateway.provider.ProviderUnavailableException;
import com.llmgateway.provider.resilience.ResilientProviderCaller;
import com.llmgateway.usage.UsageRecorder;
import com.llmgateway.usage.UsageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class ChatCompletionService {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionService.class);

    private final ProviderRouter router;
    private final ResilientProviderCaller providerCaller;
    private final UsageRecorder usageRecorder;
    private final Clock clock;

    public ChatCompletionService(ProviderRouter router, ResilientProviderCaller providerCaller,
                                 UsageRecorder usageRecorder, Clock clock) {
        this.router = router;
        this.providerCaller = providerCaller;
        this.usageRecorder = usageRecorder;
        this.clock = clock;
    }

    public ChatCompletionResponse complete(AuthenticatedKey caller, ChatCompletionRequest request) {
        ChatRequest chatRequest = toInternal(request);
        LlmProvider provider = router.route(chatRequest.model());

        String model = chatRequest.model();
        long start = System.nanoTime();
        ChatResult result;
        try {
            result = providerCaller.complete(provider, chatRequest);
        } catch (ProviderUnavailableException e) {
            log.warn("Provider {} rejected by open circuit breaker for model {}", provider.name(), model);
            recordFailure(caller, provider, model, start, UsageStatus.CIRCUIT_OPEN);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "api_error", "provider_unavailable",
                    "The provider is temporarily unavailable");
        } catch (ProviderTimeoutException e) {
            log.warn("Provider {} timed out for model {}", provider.name(), model);
            recordFailure(caller, provider, model, start, UsageStatus.TIMEOUT);
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "api_error", "provider_timeout",
                    "The provider did not respond in time");
        } catch (ProviderException e) {
            log.warn("Provider {} failed for model {}: {}", provider.name(), model, e.getMessage());
            recordFailure(caller, provider, model, start, UsageStatus.PROVIDER_ERROR);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "api_error", "provider_error",
                    "The provider returned an error");
        }
        usageRecorder.record(caller.apiKeyId(), provider.name(), model, result.inputTokens(), result.outputTokens(),
                elapsedMs(start), UsageStatus.SUCCESS);
        return toResponse(chatRequest.model(), result);
    }

    private void recordFailure(AuthenticatedKey caller, LlmProvider provider, String model, long start,
                               UsageStatus status) {
        usageRecorder.record(caller.apiKeyId(), provider.name(), model, 0, 0, elapsedMs(start), status);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static ChatRequest toInternal(ChatCompletionRequest request) {
        if (Boolean.TRUE.equals(request.stream())) {
            throw ApiException.badRequest("stream_not_supported", "Streaming is not supported");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw ApiException.badRequest("invalid_model", "Field 'model' is required");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw ApiException.badRequest("invalid_messages", "Field 'messages' must contain at least one message");
        }
        List<ChatMessage> messages = request.messages().stream().map(ChatCompletionService::toInternal).toList();
        if (request.maxTokens() != null && request.maxTokens() < 1) {
            throw ApiException.badRequest("invalid_max_tokens", "Field 'max_tokens' must be at least 1");
        }
        if (request.temperature() != null && (request.temperature() < 0 || request.temperature() > 2)) {
            throw ApiException.badRequest("invalid_temperature", "Field 'temperature' must be between 0 and 2");
        }
        return new ChatRequest(request.model(), messages, request.maxTokens(), request.temperature());
    }

    private static ChatMessage toInternal(ChatCompletionRequest.Message message) {
        if (message == null || message.role() == null || message.role().isBlank() || message.content() == null) {
            throw ApiException.badRequest("invalid_messages", "Each message needs a 'role' and a 'content'");
        }
        return new ChatMessage(message.role(), message.content());
    }

    private ChatCompletionResponse toResponse(String model, ChatResult result) {
        var choice = new ChatCompletionResponse.Choice(0,
                new ChatCompletionResponse.Message("assistant", result.content()), result.finishReason());
        var usage = new ChatCompletionResponse.Usage(result.inputTokens(), result.outputTokens(),
                result.inputTokens() + result.outputTokens());
        return new ChatCompletionResponse("chatcmpl-" + UUID.randomUUID(), "chat.completion",
                clock.instant().getEpochSecond(), model, List.of(choice), usage);
    }
}
