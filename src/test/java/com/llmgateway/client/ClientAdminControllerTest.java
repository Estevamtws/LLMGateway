package com.llmgateway.client;

import com.jayway.jsonpath.JsonPath;
import com.llmgateway.IntegrationTest;
import com.llmgateway.auth.KeyHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class ClientAdminControllerTest {

    private static final String ADMIN_HEADER = "X-Admin-Token";
    private static final String ADMIN_TOKEN = "test-admin-token";

    @Autowired
    MockMvc mvc;
    @Autowired
    ApiKeyRepository apiKeys;

    @Test
    void createsClient() throws Exception {
        String name = uniqueName();
        mvc.perform(post("/admin/clients").header(ADMIN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.created_at").isNotEmpty());
    }

    @Test
    void rejectsDuplicateClientName() throws Exception {
        String name = uniqueName();
        createClient(name);
        mvc.perform(post("/admin/clients").header(ADMIN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("client_name_taken"));
    }

    @Test
    void rejectsBlankOrMissingName() throws Exception {
        for (String body : new String[]{"{}", "{\"name\":\"  \"}", "{\"name\":\"" + "x".repeat(101) + "\"}"}) {
            mvc.perform(post("/admin/clients").header(ADMIN_HEADER, ADMIN_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                    .andExpect(jsonPath("$.error.code").value("invalid_name"));
        }
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/admin/clients").header(ADMIN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    @Test
    void issuesKeyAndStoresOnlyItsHash() throws Exception {
        String clientId = createClient(uniqueName());

        String body = mvc.perform(post("/admin/clients/{id}/keys", clientId).header(ADMIN_HEADER, ADMIN_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").value(clientId))
                .andExpect(jsonPath("$.key").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String plaintext = JsonPath.read(body, "$.key");
        String keyPrefix = JsonPath.read(body, "$.key_prefix");
        UUID keyId = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(plaintext).startsWith("gw_").startsWith(keyPrefix);

        ApiKey stored = apiKeys.findById(keyId).orElseThrow();
        assertThat(stored.getKeyHash()).isEqualTo(KeyHasher.sha256Hex(plaintext));
        assertThat(stored.getKeyHash()).doesNotContain(plaintext.substring(3));
        assertThat(stored.isActive()).isTrue();
    }

    @Test
    void issuingKeyForUnknownClientReturns404() throws Exception {
        mvc.perform(post("/admin/clients/{id}/keys", UUID.randomUUID()).header(ADMIN_HEADER, ADMIN_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("client_not_found"));
    }

    @Test
    void invalidUuidReturns400() throws Exception {
        mvc.perform(post("/admin/clients/not-a-uuid/keys").header(ADMIN_HEADER, ADMIN_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_parameter"));
    }

    @Test
    void revokesKey() throws Exception {
        String clientId = createClient(uniqueName());
        String body = mvc.perform(post("/admin/clients/{id}/keys", clientId).header(ADMIN_HEADER, ADMIN_TOKEN))
                .andReturn().getResponse().getContentAsString();
        UUID keyId = UUID.fromString(JsonPath.read(body, "$.id"));

        mvc.perform(delete("/admin/keys/{id}", keyId).header(ADMIN_HEADER, ADMIN_TOKEN))
                .andExpect(status().isNoContent());

        ApiKey stored = apiKeys.findById(keyId).orElseThrow();
        assertThat(stored.isActive()).isFalse();
        assertThat(stored.getRevokedAt()).isNotNull();
    }

    @Test
    void revokingUnknownKeyReturns404() throws Exception {
        mvc.perform(delete("/admin/keys/{id}", UUID.randomUUID()).header(ADMIN_HEADER, ADMIN_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("key_not_found"));
    }

    @Test
    void rejectsMissingOrWrongAdminToken() throws Exception {
        mvc.perform(post("/admin/clients").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("authentication_error"))
                .andExpect(jsonPath("$.error.code").value("invalid_admin_token"));
        mvc.perform(delete("/admin/keys/{id}", UUID.randomUUID()).header(ADMIN_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
    }

    private String createClient(String name) throws Exception {
        String body = mvc.perform(post("/admin/clients").header(ADMIN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static String uniqueName() {
        return "client-" + UUID.randomUUID();
    }
}
