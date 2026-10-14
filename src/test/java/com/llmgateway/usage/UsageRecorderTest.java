package com.llmgateway.usage;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageRecorderTest {

    private final UsageRecordRepository repository = mock(UsageRecordRepository.class);
    private final CostCalculator calculator = new CostCalculator(new PricingProperties(Map.of("m",
            new PricingProperties.ModelPrice(BigDecimal.ONE, BigDecimal.ONE))), List.of());
    private final UsageRecorder recorder = new UsageRecorder(repository, calculator,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void savesRecord() {
        recorder.record(UUID.randomUUID(), "p", "m", 10, 20, 5, UsageStatus.SUCCESS);

        verify(repository).save(any(UsageRecord.class));
    }

    @Test
    void doesNotFailTheRequestWhenSavingFails() {
        // Decision O1: the client still receives the response.
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatCode(() -> recorder.record(UUID.randomUUID(), "p", "m", 10, 20, 5, UsageStatus.SUCCESS))
                .doesNotThrowAnyException();
    }
}
