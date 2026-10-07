package com.llmgateway.client;

import com.llmgateway.auth.ApiKeyGenerator;
import com.llmgateway.auth.ApiKeyGenerator.GeneratedKey;
import com.llmgateway.config.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class ClientService {

    static final int MAX_NAME_LENGTH = 100;

    private final ClientRepository clients;
    private final ApiKeyRepository apiKeys;
    private final ApiKeyGenerator keyGenerator;
    private final Clock clock;

    public ClientService(ClientRepository clients, ApiKeyRepository apiKeys, ApiKeyGenerator keyGenerator,
                         Clock clock) {
        this.clients = clients;
        this.apiKeys = apiKeys;
        this.keyGenerator = keyGenerator;
        this.clock = clock;
    }

    @Transactional
    public Client createClient(String rawName) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty()) {
            throw ApiException.badRequest("invalid_name", "Field 'name' is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw ApiException.badRequest("invalid_name", "Field 'name' must be at most 100 characters");
        }
        if (clients.existsByName(name)) {
            throw nameTaken(name);
        }
        try {
            return clients.saveAndFlush(new Client(name, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw nameTaken(name);
        }
    }

    @Transactional
    public IssuedKey issueKey(UUID clientId) {
        Client client = clients.findById(clientId)
                .orElseThrow(() -> ApiException.notFound("client_not_found", "Client not found"));
        GeneratedKey generated = keyGenerator.generate();
        ApiKey key = apiKeys.save(new ApiKey(client, generated.hash(), generated.displayPrefix(), clock.instant()));
        return new IssuedKey(key, generated.plaintext());
    }

    @Transactional
    public void revokeKey(UUID keyId) {
        ApiKey key = apiKeys.findById(keyId)
                .orElseThrow(() -> ApiException.notFound("key_not_found", "API key not found"));
        key.revoke(clock.instant());
    }

    private static ApiException nameTaken(String name) {
        return ApiException.conflict("client_name_taken", "A client named '" + name + "' already exists");
    }

    /**
     * A newly issued key together with its plaintext, which is only available at this point.
     */
    public record IssuedKey(ApiKey key, String plaintext) {
    }
}
