package com.llmgateway.provider.resilience;

import com.llmgateway.provider.ChatMessage;
import com.llmgateway.provider.ChatRequest;
import com.llmgateway.provider.ChatResult;
import com.llmgateway.provider.LlmProvider;
import com.llmgateway.provider.ProviderException;
import com.llmgateway.provider.ProviderTimeoutException;
import com.llmgateway.provider.ProviderUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientProviderCallerTest {

    private static final ChatRequest REQUEST =
            new ChatRequest("m", List.of(new ChatMessage("user", "Hi")), null, null);
    private static final ChatResult OK = new ChatResult("ok", "stop", 1, 1);

    @Test
    void retriesRetryableErrorsUntilSuccess() {
        ResilientProviderCaller caller = caller(retry(3, false), lenientBreaker());
        ScriptedProvider provider = new ScriptedProvider("p", fail(), fail(), () -> OK);

        assertThat(caller.complete(provider, REQUEST)).isEqualTo(OK);
        assertThat(provider.calls).isEqualTo(3);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        ResilientProviderCaller caller = caller(retry(3, false), lenientBreaker());
        ScriptedProvider provider = new ScriptedProvider("p", fail(), fail(), fail(), () -> OK);

        assertThatThrownBy(() -> caller.complete(provider, REQUEST)).isInstanceOf(ProviderException.class);
        assertThat(provider.calls).isEqualTo(3);
    }

    @Test
    void doesNotRetryNonRetryableErrors() {
        ResilientProviderCaller caller = caller(retry(3, false), lenientBreaker());
        ScriptedProvider provider = new ScriptedProvider("p", () -> {
            throw new ProviderException("bad request", false, null);
        }, () -> OK);

        assertThatThrownBy(() -> caller.complete(provider, REQUEST)).isInstanceOf(ProviderException.class);
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void doesNotRetryTimeoutsByDefault() {
        ResilientProviderCaller caller = caller(retry(3, false), lenientBreaker());
        ScriptedProvider provider = new ScriptedProvider("p", timeout(), () -> OK);

        assertThatThrownBy(() -> caller.complete(provider, REQUEST)).isInstanceOf(ProviderTimeoutException.class);
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void retriesTimeoutsWhenEnabled() {
        ResilientProviderCaller caller = caller(retry(3, true), lenientBreaker());
        ScriptedProvider provider = new ScriptedProvider("p", timeout(), () -> OK);

        assertThat(caller.complete(provider, REQUEST)).isEqualTo(OK);
        assertThat(provider.calls).isEqualTo(2);
    }

    @Test
    void opensCircuitAfterFailuresAndRejectsWithoutCallingProvider() {
        ResilientProviderCaller caller = caller(retry(1, false), breaker(4, 60_000));
        ScriptedProvider provider = new ScriptedProvider("p", fail(), fail(), fail(), fail(), () -> OK);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> caller.complete(provider, REQUEST)).isInstanceOf(ProviderException.class);
        }

        assertThat(caller.state("p")).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> caller.complete(provider, REQUEST))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThat(provider.calls).isEqualTo(4);
    }

    @Test
    void openCircuitStopsRemainingRetries() {
        ResilientProviderCaller caller = caller(retry(5, false), breaker(2, 60_000));
        ScriptedProvider provider = new ScriptedProvider("p", fail(), fail(), fail(), fail(), fail());

        assertThatThrownBy(() -> caller.complete(provider, REQUEST))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThat(provider.calls).isEqualTo(2);
    }

    @Test
    void nonRetryableErrorsDoNotOpenCircuit() {
        ResilientProviderCaller caller = caller(retry(1, false), breaker(2, 60_000));
        Supplier<ChatResult> badRequest = () -> {
            throw new ProviderException("bad request", false, null);
        };
        ScriptedProvider provider = new ScriptedProvider("p", badRequest, badRequest, badRequest, () -> OK);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> caller.complete(provider, REQUEST)).isInstanceOf(ProviderException.class);
        }

        assertThat(caller.state("p")).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(caller.complete(provider, REQUEST)).isEqualTo(OK);
    }

    @Test
    void circuitsAreIndependentPerProvider() {
        ResilientProviderCaller caller = caller(retry(1, false), breaker(2, 60_000));
        ScriptedProvider broken = new ScriptedProvider("broken", fail(), fail());
        ScriptedProvider healthy = new ScriptedProvider("healthy", () -> OK);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> caller.complete(broken, REQUEST)).isInstanceOf(ProviderException.class);
        }

        assertThat(caller.state("broken")).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(caller.complete(healthy, REQUEST)).isEqualTo(OK);
    }

    @Test
    void closesAgainAfterSuccessfulTestCallInHalfOpenState() throws InterruptedException {
        ResilientProviderCaller caller = caller(retry(1, false), breaker(2, 50));
        ScriptedProvider provider = new ScriptedProvider("p", fail(), fail(), () -> OK);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> caller.complete(provider, REQUEST)).isInstanceOf(ProviderException.class);
        }
        assertThat(caller.state("p")).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(100);

        assertThat(caller.complete(provider, REQUEST)).isEqualTo(OK);
        assertThat(caller.state("p")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private static ResilientProviderCaller caller(ResilienceProperties.Retry retry,
                                                  ResilienceProperties.CircuitBreaker breaker) {
        return new ResilientProviderCaller(new ResilienceProperties(retry, breaker));
    }

    private static ResilienceProperties.Retry retry(int maxAttempts, boolean retryOnTimeout) {
        return new ResilienceProperties.Retry(maxAttempts, 1, 1.0, retryOnTimeout);
    }

    private static ResilienceProperties.CircuitBreaker breaker(int window, long waitInOpenMs) {
        return new ResilienceProperties.CircuitBreaker(50, window, window, waitInOpenMs, 1);
    }

    private static ResilienceProperties.CircuitBreaker lenientBreaker() {
        return new ResilienceProperties.CircuitBreaker(50, 100, 100, 60_000, 1);
    }

    private static Supplier<ChatResult> fail() {
        return () -> {
            throw new ProviderException("boom");
        };
    }

    private static Supplier<ChatResult> timeout() {
        return () -> {
            throw new ProviderTimeoutException("slow", null);
        };
    }

    /**
     * Answers each call with the next scripted outcome.
     */
    private static final class ScriptedProvider implements LlmProvider {

        private final String name;
        private final Deque<Supplier<ChatResult>> outcomes;
        int calls;

        @SafeVarargs
        ScriptedProvider(String name, Supplier<ChatResult>... outcomes) {
            this.name = name;
            this.outcomes = new ArrayDeque<>(List.of(outcomes));
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> models() {
            return Set.of("m");
        }

        @Override
        public ChatResult complete(ChatRequest request) {
            calls++;
            return outcomes.removeFirst().get();
        }
    }
}
