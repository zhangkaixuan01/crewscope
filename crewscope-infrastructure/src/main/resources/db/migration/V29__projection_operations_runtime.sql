-- Durable M6-I02 projection supervision and immutable operations-recovery scheduling.
CREATE TABLE crewscope.projection_worker_claim (
    organization_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    worker_role VARCHAR(16) NOT NULL,
    owner_id VARCHAR(160),
    fencing_token BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'IDLE',
    lease_expires_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    cursor_aggregate_type VARCHAR(100),
    cursor_aggregate_id UUID,
    cursor_aggregate_version BIGINT,
    cursor_occurred_at TIMESTAMPTZ,
    cursor_event_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, projection_name, generation, worker_role),
    CONSTRAINT fk_projection_worker_claim_generation_v29
        FOREIGN KEY (organization_id, projection_name, generation)
        REFERENCES crewscope.projection_generation (
            organization_id, projection_name, generation
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_projection_worker_claim_values_v29 CHECK (
        generation > 0 AND fencing_token >= 0 AND version >= 0
        AND worker_role IN ('ONLINE', 'SHADOW')
        AND status IN ('IDLE', 'RUNNING', 'INTERRUPTED', 'CAUGHT_UP')
        AND updated_at >= created_at
    ),
    CONSTRAINT ck_projection_worker_claim_lease_v29 CHECK (
        (status = 'RUNNING' AND owner_id IS NOT NULL
            AND lease_expires_at IS NOT NULL AND heartbeat_at IS NOT NULL
            AND lease_expires_at > heartbeat_at)
        OR (status <> 'RUNNING' AND owner_id IS NULL
            AND lease_expires_at IS NULL AND heartbeat_at IS NULL)
    ),
    CONSTRAINT ck_projection_worker_claim_cursor_v29 CHECK (
        (cursor_aggregate_type IS NULL AND cursor_aggregate_id IS NULL
            AND cursor_aggregate_version IS NULL AND cursor_occurred_at IS NULL
            AND cursor_event_id IS NULL)
        OR (cursor_aggregate_type IS NOT NULL AND BTRIM(cursor_aggregate_type) <> ''
            AND cursor_aggregate_id IS NOT NULL AND cursor_aggregate_version >= 0
            AND cursor_occurred_at IS NOT NULL AND cursor_event_id IS NOT NULL)
    )
);

CREATE INDEX ix_projection_worker_claim_due_v29
    ON crewscope.projection_worker_claim (status, lease_expires_at, updated_at)
    WHERE status IN ('IDLE', 'RUNNING', 'INTERRUPTED');

CREATE TABLE crewscope.operations_recovery_schedule (
    organization_id UUID NOT NULL,
    schedule_id UUID NOT NULL,
    command_id UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    recovery_action VARCHAR(48) NOT NULL,
    target_reference_hash CHAR(64) NOT NULL,
    projection_name VARCHAR(180),
    generation BIGINT,
    target_id UUID NOT NULL,
    domain_event_id UUID,
    expected_version BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    audit_domain_event_id UUID NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    claimed_by VARCHAR(160),
    claim_token BIGINT NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_id, schedule_id),
    CONSTRAINT uk_operations_recovery_schedule_command_v29
        UNIQUE (organization_id, command_id),
    CONSTRAINT fk_operations_recovery_schedule_organization_v29
        FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_operations_recovery_schedule_audit_event_v29
        FOREIGN KEY (organization_id, audit_domain_event_id)
        REFERENCES crewscope.domain_event (organization_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_operations_recovery_schedule_values_v29 CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'
        AND target_reference_hash ~ '^[0-9a-f]{64}$'
        AND expected_version >= 0 AND claim_token >= 0 AND version >= 0
        AND recovery_action IN (
            'REPLAY_OUTBOX_DEAD_LETTER',
            'REPLAY_PROJECTION_DEAD_LETTER',
            'RETRY_NOTIFICATION_DELIVERY'
        )
        AND status IN ('PENDING', 'CLAIMED', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_operations_recovery_schedule_projection_v29 CHECK (
        (recovery_action = 'REPLAY_PROJECTION_DEAD_LETTER'
            AND projection_name IS NOT NULL AND generation > 0
            AND domain_event_id IS NOT NULL)
        OR (recovery_action = 'REPLAY_OUTBOX_DEAD_LETTER'
            AND projection_name IS NULL AND generation IS NULL
            AND domain_event_id IS NOT NULL)
        OR (recovery_action = 'RETRY_NOTIFICATION_DELIVERY'
            AND projection_name IS NULL AND generation IS NULL
            AND domain_event_id IS NULL)
    ),
    CONSTRAINT ck_operations_recovery_schedule_claim_v29 CHECK (
        (status = 'CLAIMED' AND claimed_by IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR status <> 'CLAIMED'
    )
);

CREATE INDEX ix_operations_recovery_schedule_pending_v29
    ON crewscope.operations_recovery_schedule (status, accepted_at, schedule_id)
    WHERE status IN ('PENDING', 'CLAIMED');

-- Cleanup receipts remain after generation-owned rows have been removed.
CREATE TABLE crewscope.projection_cleanup_receipt (
    organization_id UUID NOT NULL,
    cleanup_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    terminal_status VARCHAR(20) NOT NULL,
    deleted_row_count BIGINT NOT NULL,
    cleaned_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, cleanup_id),
    CONSTRAINT fk_projection_cleanup_receipt_organization_v29
        FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT ck_projection_cleanup_receipt_values_v29 CHECK (
        generation > 0 AND deleted_row_count >= 0
        AND terminal_status IN ('RETIRED', 'FAILED', 'CANCELLED')
    )
);

-- Worker Claim state can change only by increasing version. Fencing tokens cannot decrease.
CREATE FUNCTION crewscope.guard_projection_worker_claim_v29()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.version <> OLD.version + 1
        OR NEW.fencing_token < OLD.fencing_token
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'projection worker claim transition is invalid';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_projection_worker_claim_guard_v29
    BEFORE UPDATE ON crewscope.projection_worker_claim
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_projection_worker_claim_v29();

CREATE FUNCTION crewscope.guard_operations_recovery_schedule_v29()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'operations recovery schedule is append-preserving';
    END IF;
    IF NEW.organization_id <> OLD.organization_id
        OR NEW.schedule_id <> OLD.schedule_id
        OR NEW.command_id <> OLD.command_id
        OR NEW.request_fingerprint <> OLD.request_fingerprint
        OR NEW.recovery_action <> OLD.recovery_action
        OR NEW.target_reference_hash <> OLD.target_reference_hash
        OR NEW.projection_name IS DISTINCT FROM OLD.projection_name
        OR NEW.generation IS DISTINCT FROM OLD.generation
        OR NEW.target_id <> OLD.target_id
        OR NEW.domain_event_id IS DISTINCT FROM OLD.domain_event_id
        OR NEW.expected_version <> OLD.expected_version
        OR NEW.audit_domain_event_id <> OLD.audit_domain_event_id
        OR NEW.accepted_at <> OLD.accepted_at
        OR NEW.claim_token < OLD.claim_token
        OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'operations recovery schedule immutable coordinates changed';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_operations_recovery_schedule_guard_v29
    BEFORE UPDATE OR DELETE ON crewscope.operations_recovery_schedule
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_operations_recovery_schedule_v29();
