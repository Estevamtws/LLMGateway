-- Requests rejected by an open circuit breaker never reach the provider; record them separately.
ALTER TABLE usage_record DROP CONSTRAINT ck_usage_record_status;
ALTER TABLE usage_record ADD CONSTRAINT ck_usage_record_status
    CHECK (status IN ('SUCCESS', 'PROVIDER_ERROR', 'TIMEOUT', 'CIRCUIT_OPEN'));
