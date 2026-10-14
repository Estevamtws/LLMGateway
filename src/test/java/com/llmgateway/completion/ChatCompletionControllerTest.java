package com.llmgateway.completion;

import com.llmgateway.FailingTestProvider;
import com.llmgateway.IntegrationTest;
import com.llmgateway.client.Client;
import com.llmgateway.client.ClientRepository;
import com.llmgateway.client.ClientService;
import com.llmgateway.client.ClientStatus;
import com.llmgateway.usage.UsageRecord;
import com.llmgateway.usage.UsageRecordRepository;
import com.llmgateway.usage.UsageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class ChatCompletionControllerTest {

    private static final String HELLO = """
            {"model":"fake-fast","messages":[{"role":"user","content":"Hello"}],"max_tokens":100}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    ClientService clientService;
    @Autowired
    ClientRepository clients;
    @Autowired
    UsageRecordRepository usageRecords;

    private Client client;
    private ClientService.IssuedKey key;

    @BeforeEach
    void createClientAndKey() {
        client = clientService.createClient("completion-" + UUID.randomUUID());
        key = clientService.issueKey(client.getId());
    }

    @Test
    void returnsCompletionInOpenAiFormat() throws Exception {
        complete(HELLO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(startsWith("chatcmpl-")))
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.created").isNumber())
                .andExpect(jsonPath("$.model").value("fake-fast"))
                .andExpect(jsonPath("$.choices[0].index").value(0))
                .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
                .andExpect(jsonPath("$.choices[0].message.content").value("[fake-fast] You said: Hello"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(2))
                .andExpect(jsonPath("$.usage.completion_tokens").value(7))
                .andExpect(jsonPath("$.usage.total_tokens").value(9));

        UsageRecord record = singleUsageRecord();
        assertThat(record.getProvider()).isEqualTo("fake");
        assertThat(record.getModel()).isEqualTo("fake-fast");
        assertThat(record.getInputTokens()).isEqualTo(2);
        assertThat(record.getOutputTokens()).isEqualTo(7);
        // (2 * 0.10 + 7 * 0.40) / 1,000,000
        assertThat(record.getCostUsd()).isEqualByComparingTo("0.00000300");
        assertThat(record.getLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(record.getStatus()).isEqualTo(UsageStatus.SUCCESS);
    }

    @Test
    void rejectedRequestsDoNotProduceUsageRecords() throws Exception {
        complete(body("nope")).andExpect(status().isBadRequest());

        assertThat(usageRecords.findByApiKeyId(key.key().getId())).isEmpty();
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        complete("""
                {"model":"fake-fast","messages":[{"role":"user","content":"Hi","name":"x"}],"top_p":1,"user":"u"}""")
                .andExpect(status().isOk());
    }

    @Test
    void rejectsMissingKey() throws Exception {
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(HELLO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("authentication_error"))
                .andExpect(jsonPath("$.error.code").value("missing_api_key"));
    }

    @Test
    void rejectsUnknownKey() throws Exception {
        completeWithKey("gw_unknown", HELLO)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    void rejectsRevokedKey() throws Exception {
        clientService.revokeKey(key.key().getId());

        complete(HELLO)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    void rejectsSuspendedClient() throws Exception {
        Client stored = clients.findById(client.getId()).orElseThrow();
        stored.setStatus(ClientStatus.SUSPENDED);
        clients.save(stored);

        complete(HELLO)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("client_suspended"));
    }

    @Test
    void rejectsUnknownModel() throws Exception {
        complete("""
                {"model":"nope","messages":[{"role":"user","content":"Hi"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("unknown_model"));
    }

    @Test
    void rejectsStreaming() throws Exception {
        complete("""
                {"model":"fake-fast","messages":[{"role":"user","content":"Hi"}],"stream":true}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("stream_not_supported"));
    }

    @Test
    void rejectsInvalidBodies() throws Exception {
        String[][] cases = {
                {"{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}", "invalid_model"},
                {"{\"model\":\"fake-fast\"}", "invalid_messages"},
                {"{\"model\":\"fake-fast\",\"messages\":[]}", "invalid_messages"},
                {"{\"model\":\"fake-fast\",\"messages\":[{\"content\":\"Hi\"}]}", "invalid_messages"},
                {"{\"model\":\"fake-fast\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],\"max_tokens\":0}",
                        "invalid_max_tokens"},
                {"{\"model\":\"fake-fast\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],\"temperature\":3}",
                        "invalid_temperature"},
                {"not json", "invalid_request"},
        };
        for (String[] c : cases) {
            complete(c[0])
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value(c[1]));
        }
    }

    @Test
    void providerErrorReturns502() throws Exception {
        complete(body(FailingTestProvider.ERROR_MODEL))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.type").value("api_error"))
                .andExpect(jsonPath("$.error.code").value("provider_error"));

        assertFailureRecorded(FailingTestProvider.ERROR_MODEL, UsageStatus.PROVIDER_ERROR);
    }

    @Test
    void providerTimeoutReturns504() throws Exception {
        complete(body(FailingTestProvider.TIMEOUT_MODEL))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error.code").value("provider_timeout"));

        assertFailureRecorded(FailingTestProvider.TIMEOUT_MODEL, UsageStatus.TIMEOUT);
    }

    private void assertFailureRecorded(String model, UsageStatus status) {
        UsageRecord record = singleUsageRecord();
        assertThat(record.getModel()).isEqualTo(model);
        assertThat(record.getProvider()).isEqualTo("test");
        assertThat(record.getInputTokens()).isZero();
        assertThat(record.getOutputTokens()).isZero();
        assertThat(record.getCostUsd()).isEqualByComparingTo("0");
        assertThat(record.getStatus()).isEqualTo(status);
    }

    private UsageRecord singleUsageRecord() {
        List<UsageRecord> records = usageRecords.findByApiKeyId(key.key().getId());
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    private ResultActions complete(String body) throws Exception {
        return completeWithKey(key.plaintext(), body);
    }

    private ResultActions completeWithKey(String apiKey, String body) throws Exception {
        return mvc.perform(post("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String body(String model) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
    }
}
