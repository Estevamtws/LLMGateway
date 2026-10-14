package com.llmgateway.usage;

import com.llmgateway.IntegrationTest;
import com.llmgateway.client.Client;
import com.llmgateway.client.ClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class UsageAdminControllerTest {

    private static final String ADMIN_HEADER = "X-Admin-Token";
    private static final String ADMIN_TOKEN = "test-admin-token";
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-02-15T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-03-01T00:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    ClientService clientService;
    @Autowired
    UsageRecordRepository usageRecords;

    private Client client;

    @BeforeEach
    void seed() {
        client = clientService.createClient("usage-" + UUID.randomUUID());
        UUID key1 = clientService.issueKey(client.getId()).key().getId();
        UUID key2 = clientService.issueKey(client.getId()).key().getId();
        Client other = clientService.createClient("other-" + UUID.randomUUID());
        UUID otherKey = clientService.issueKey(other.getId()).key().getId();

        save(key1, "fake-fast", 100, 1, "9.00000000", T0);          // before the range
        save(key1, "fake-fast", 10, 20, "0.00000300", T1);           // range start is inclusive
        save(key2, "fake-fast", 5, 5, "0.00000250", T2);             // second key, same client
        save(key1, "fake-slow", 1, 1, "0.00000500", T2);
        save(key1, "fake-slow", 0, 0, "0", T2);                      // failed call
        save(key1, "fake-fast", 7, 7, "1.00000000", T3);             // range end is exclusive
        save(otherKey, "fake-fast", 1000, 1000, "5.00000000", T2);   // other client
    }

    @Test
    void returnsTotalsPerModelForTheRange() throws Exception {
        String body = mvc.perform(get("/admin/usage").header(ADMIN_HEADER, ADMIN_TOKEN)
                        .param("clientId", client.getId().toString())
                        .param("from", T1.toString())
                        .param("to", T3.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client_id").value(client.getId().toString()))
                .andExpect(jsonPath("$.from").value("2026-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.to").value("2026-03-01T00:00:00Z"))
                .andExpect(jsonPath("$.models", hasSize(2)))
                .andExpect(jsonPath("$.models[0].model").value("fake-fast"))
                .andExpect(jsonPath("$.models[0].requests").value(2))
                .andExpect(jsonPath("$.models[0].input_tokens").value(15))
                .andExpect(jsonPath("$.models[0].output_tokens").value(25))
                .andExpect(jsonPath("$.models[1].model").value("fake-slow"))
                .andExpect(jsonPath("$.models[1].requests").value(2))
                .andExpect(jsonPath("$.models[1].input_tokens").value(1))
                .andExpect(jsonPath("$.models[1].output_tokens").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"cost_usd\":0.00000550").contains("\"cost_usd\":0.00000500");
    }

    @Test
    void withoutRangeIncludesEverything() throws Exception {
        mvc.perform(get("/admin/usage").header(ADMIN_HEADER, ADMIN_TOKEN)
                        .param("clientId", client.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").doesNotExist())
                .andExpect(jsonPath("$.models[0].requests").value(4))
                .andExpect(jsonPath("$.models[0].input_tokens").value(122))
                .andExpect(jsonPath("$.models[0].cost_usd").value(10.0000055));
    }

    @Test
    void clientWithoutUsageGetsEmptyList() throws Exception {
        Client idle = clientService.createClient("idle-" + UUID.randomUUID());

        mvc.perform(get("/admin/usage").header(ADMIN_HEADER, ADMIN_TOKEN).param("clientId", idle.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", hasSize(0)));
    }

    @Test
    void rejectsInvalidQueries() throws Exception {
        mvc.perform(get("/admin/usage").header(ADMIN_HEADER, ADMIN_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("missing_parameter"));
        mvc.perform(get("/admin/usage").header(ADMIN_HEADER, ADMIN_TOKEN)
                        .param("clientId", client.getId().toString()).param("from", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_parameter"));
        mvc.perform(get("/admin/usage").header(ADMIN_HEADER, ADMIN_TOKEN)
                        .param("clientId", client.getId().toString())
                        .param("from", T3.toString()).param("to", T1.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_range"));
        mvc.perform(get("/admin/usage").header(ADMIN_HEADER, ADMIN_TOKEN)
                        .param("clientId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("client_not_found"));
    }

    @Test
    void requiresAdminToken() throws Exception {
        mvc.perform(get("/admin/usage").param("clientId", client.getId().toString()))
                .andExpect(status().isUnauthorized());
    }

    private void save(UUID keyId, String model, int in, int out, String cost, Instant at) {
        UsageStatus status = in == 0 && out == 0 ? UsageStatus.PROVIDER_ERROR : UsageStatus.SUCCESS;
        usageRecords.save(new UsageRecord(keyId, "fake", model, in, out, new BigDecimal(cost), 10, status, at));
    }
}
