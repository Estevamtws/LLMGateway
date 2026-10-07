package com.llmgateway.client;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
public class ClientAdminController {

    private final ClientService clientService;

    public ClientAdminController(ClientService clientService) {
        this.clientService = clientService;
    }

    @PostMapping("/clients")
    @ResponseStatus(HttpStatus.CREATED)
    public ClientResponse createClient(@RequestBody CreateClientRequest request) {
        return ClientResponse.from(clientService.createClient(request.name()));
    }

    @PostMapping("/clients/{clientId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedKeyResponse issueKey(@PathVariable UUID clientId) {
        return IssuedKeyResponse.from(clientService.issueKey(clientId));
    }

    @DeleteMapping("/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeKey(@PathVariable UUID keyId) {
        clientService.revokeKey(keyId);
    }

    public record CreateClientRequest(String name) {
    }

    public record ClientResponse(UUID id, String name, ClientStatus status, Instant createdAt) {

        static ClientResponse from(Client client) {
            return new ClientResponse(client.getId(), client.getName(), client.getStatus(), client.getCreatedAt());
        }
    }

    public record IssuedKeyResponse(UUID id, UUID clientId, String key, String keyPrefix, Instant createdAt) {

        static IssuedKeyResponse from(ClientService.IssuedKey issued) {
            ApiKey key = issued.key();
            return new IssuedKeyResponse(key.getId(), key.getClient().getId(), issued.plaintext(),
                    key.getKeyPrefix(), key.getCreatedAt());
        }
    }
}
