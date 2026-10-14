package com.llmgateway.usage;

import com.llmgateway.client.ClientRepository;
import com.llmgateway.config.ApiException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class UsageAdminController {

    private static final Instant NO_UPPER_BOUND = Instant.parse("9999-12-31T23:59:59Z");

    private final UsageRecordRepository usageRecords;
    private final ClientRepository clients;

    public UsageAdminController(UsageRecordRepository usageRecords, ClientRepository clients) {
        this.usageRecords = usageRecords;
        this.clients = clients;
    }

    /**
     * Usage totals per model for one client. {@code from} is inclusive, {@code to} exclusive, both
     * ISO-8601 instants and both optional.
     */
    @GetMapping("/admin/usage")
    @Transactional(readOnly = true)
    public UsageReport usage(@RequestParam UUID clientId,
                             @RequestParam(required = false) Instant from,
                             @RequestParam(required = false) Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.badRequest("invalid_range", "'from' must be before 'to'");
        }
        if (!clients.existsById(clientId)) {
            throw ApiException.notFound("client_not_found", "Client not found");
        }
        List<ModelUsage> models = usageRecords.summarizeByModel(clientId,
                from == null ? Instant.EPOCH : from, to == null ? NO_UPPER_BOUND : to);
        return new UsageReport(clientId, from, to, models);
    }

    public record UsageReport(UUID clientId, Instant from, Instant to, List<ModelUsage> models) {
    }
}
