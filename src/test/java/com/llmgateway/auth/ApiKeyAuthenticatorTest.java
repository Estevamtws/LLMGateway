package com.llmgateway.auth;

import com.llmgateway.IntegrationTest;
import com.llmgateway.client.ApiKey;
import com.llmgateway.client.ApiKeyRepository;
import com.llmgateway.client.Client;
import com.llmgateway.client.ClientRepository;
import com.llmgateway.client.ClientService;
import com.llmgateway.client.ClientStatus;
import com.llmgateway.config.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class ApiKeyAuthenticatorTest {

    @Autowired
    ApiKeyAuthenticator authenticator;
    @Autowired
    ClientService clientService;
    @Autowired
    ClientRepository clients;
    @Autowired
    ApiKeyRepository apiKeys;

    @Test
    void acceptsActiveKeyOfActiveClient() {
        Client client = clientService.createClient("auth-" + UUID.randomUUID());
        ClientService.IssuedKey issued = clientService.issueKey(client.getId());

        AuthenticatedKey caller = authenticator.authenticate("Bearer " + issued.plaintext());

        assertThat(caller.apiKeyId()).isEqualTo(issued.key().getId());
        assertThat(caller.clientId()).isEqualTo(client.getId());
    }

    @Test
    void rejectsMissingHeader() {
        assertUnauthorized(null, "missing_api_key");
        assertUnauthorized("", "missing_api_key");
        assertUnauthorized("Bearer ", "missing_api_key");
        assertUnauthorized("Basic abc", "missing_api_key");
    }

    @Test
    void rejectsUnknownKey() {
        assertUnauthorized("Bearer gw_doesnotexist", "invalid_api_key");
    }

    @Test
    void rejectsRevokedKey() {
        Client client = clientService.createClient("revoked-" + UUID.randomUUID());
        ClientService.IssuedKey issued = clientService.issueKey(client.getId());
        clientService.revokeKey(issued.key().getId());

        assertUnauthorized("Bearer " + issued.plaintext(), "invalid_api_key");
    }

    @Test
    void rejectsKeyOfSuspendedClient() {
        Client client = clientService.createClient("suspended-" + UUID.randomUUID());
        ClientService.IssuedKey issued = clientService.issueKey(client.getId());
        Client stored = clients.findById(client.getId()).orElseThrow();
        stored.setStatus(ClientStatus.SUSPENDED);
        clients.save(stored);

        assertUnauthorized("Bearer " + issued.plaintext(), "client_suspended");
        ApiKey key = apiKeys.findById(issued.key().getId()).orElseThrow();
        assertThat(key.isActive()).isTrue();
    }

    private void assertUnauthorized(String header, String code) {
        assertThatThrownBy(() -> authenticator.authenticate(header))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.code()).isEqualTo(code);
                });
    }
}
