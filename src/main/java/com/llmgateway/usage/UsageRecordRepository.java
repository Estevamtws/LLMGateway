package com.llmgateway.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    List<UsageRecord> findByApiKeyId(UUID apiKeyId);

    /**
     * Totals per model for every key of a client, with {@code from} inclusive and {@code to} exclusive.
     */
    @Query("""
            select new com.llmgateway.usage.ModelUsage(
                r.model, count(r), sum(r.inputTokens), sum(r.outputTokens), sum(r.costUsd))
            from UsageRecord r, ApiKey k
            where r.apiKeyId = k.id
              and k.client.id = :clientId
              and r.createdAt >= :from
              and r.createdAt < :to
            group by r.model
            order by r.model""")
    List<ModelUsage> summarizeByModel(@Param("clientId") UUID clientId, @Param("from") Instant from,
                                      @Param("to") Instant to);
}
