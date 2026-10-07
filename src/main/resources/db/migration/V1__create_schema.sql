CREATE TABLE client (
    id         UUID         PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_client_name UNIQUE (name),
    CONSTRAINT ck_client_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE TABLE api_key (
    id         UUID        PRIMARY KEY,
    client_id  UUID        NOT NULL REFERENCES client (id),
    key_hash   CHAR(64)    NOT NULL,
    key_prefix VARCHAR(12) NOT NULL,
    active     BOOLEAN     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_api_key_hash UNIQUE (key_hash)
);

CREATE INDEX ix_api_key_client_id ON api_key (client_id);

CREATE TABLE usage_record (
    id            UUID          PRIMARY KEY,
    api_key_id    UUID          NOT NULL REFERENCES api_key (id),
    provider      VARCHAR(50)   NOT NULL,
    model         VARCHAR(100)  NOT NULL,
    input_tokens  INTEGER       NOT NULL,
    output_tokens INTEGER       NOT NULL,
    cost_usd      NUMERIC(18,8) NOT NULL,
    latency_ms    INTEGER       NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_usage_record_status CHECK (status IN ('SUCCESS', 'PROVIDER_ERROR', 'TIMEOUT'))
);

CREATE INDEX ix_usage_record_api_key_id_created_at ON usage_record (api_key_id, created_at);
