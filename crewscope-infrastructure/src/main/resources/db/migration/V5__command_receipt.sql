-- Command idempotency is reserved before side effects and completed in the same local transaction.
CREATE TABLE crewscope.command_receipt (
    organization_id UUID NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    command_type VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    command_id UUID NOT NULL,
    domain_event_id UUID,
    committed_version BIGINT,
    correlation_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, idempotency_key),
    CONSTRAINT uk_command_receipt_command UNIQUE (organization_id, command_id),
    CONSTRAINT fk_command_receipt_organization FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_command_receipt_domain_event FOREIGN KEY (organization_id, domain_event_id)
        REFERENCES crewscope.domain_event (organization_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_command_receipt_key CHECK (
        idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}$'
    ),
    CONSTRAINT ck_command_receipt_type CHECK (BTRIM(command_type) <> ''),
    CONSTRAINT ck_command_receipt_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_command_receipt_status CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT ck_command_receipt_completion CHECK (
        (status = 'PENDING' AND domain_event_id IS NULL AND committed_version IS NULL)
        OR (status = 'COMPLETED' AND domain_event_id IS NOT NULL AND committed_version >= 0)
    ),
    CONSTRAINT ck_command_receipt_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX ux_command_receipt_domain_event
    ON crewscope.command_receipt (organization_id, domain_event_id)
    WHERE domain_event_id IS NOT NULL;

CREATE INDEX ix_command_receipt_created
    ON crewscope.command_receipt (organization_id, created_at DESC);
