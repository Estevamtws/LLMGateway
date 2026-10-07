package com.llmgateway;

import com.llmgateway.client.ApiKey;
import com.llmgateway.client.ApiKeyRepository;
import com.llmgateway.client.Client;
import com.llmgateway.client.ClientRepository;
import com.llmgateway.client.ClientStatus;
import com.llmgateway.usage.UsageRecord;
import com.llmgateway.usage.UsageRecordRepository;
import com.llmgateway.usage.UsageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PersistenceSchemaTest {

    private static final String HASH = "a".repeat(64);

    @Autowired
    ClientRepository clients;
    @Autowired
    ApiKeyRepository apiKeys;
    @Autowired
    UsageRecordRepository usageRecords;

    @Test
    void persistsClientKeyAndUsageRecord() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Client client = clients.saveAndFlush(new Client("acme", now));
        ApiKey key = apiKeys.saveAndFlush(new ApiKey(client, HASH, "gw_abcdefgh", now));
        usageRecords.saveAndFlush(new UsageRecord(key.getId(), "fake", "fake-fast", 5, 12,
                new BigDecimal("0.00001234"), 42, UsageStatus.SUCCESS, now));

        ApiKey found = apiKeys.findByKeyHash(HASH).orElseThrow();
        assertThat(found.getClient().getName()).isEqualTo("acme");
        assertThat(found.getClient().getStatus()).isEqualTo(ClientStatus.ACTIVE);
        assertThat(found.isActive()).isTrue();
        assertThat(found.getRevokedAt()).isNull();

        UsageRecord record = usageRecords.findByApiKeyId(key.getId()).getFirst();
        assertThat(record.getCostUsd()).isEqualByComparingTo("0.00001234");
        assertThat(record.getStatus()).isEqualTo(UsageStatus.SUCCESS);
        assertThat(record.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void rejectsDuplicateClientName() {
        clients.saveAndFlush(new Client("dup", Instant.now()));
        assertThatThrownBy(() -> clients.saveAndFlush(new Client("dup", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateKeyHash() {
        Client client = clients.saveAndFlush(new Client("hash-owner", Instant.now()));
        apiKeys.saveAndFlush(new ApiKey(client, HASH, "gw_one", Instant.now()));
        assertThatThrownBy(() -> apiKeys.saveAndFlush(new ApiKey(client, HASH, "gw_two", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
