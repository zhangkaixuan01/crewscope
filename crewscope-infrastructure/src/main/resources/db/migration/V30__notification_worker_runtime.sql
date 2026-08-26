-- Reliable M6-I03 Notification Worker claims. Provider bodies and credentials are never stored.
ALTER TABLE crewscope.notification_delivery
    ADD COLUMN claimed_by VARCHAR(160),
    ADD COLUMN claim_token BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN heartbeat_at TIMESTAMPTZ,
    ADD COLUMN reconciliation_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE crewscope.notification_delivery
    ADD CONSTRAINT ck_notification_delivery_worker_values_v30 CHECK (
        claim_token >= 0 AND reconciliation_count >= 0
    ),
    ADD CONSTRAINT ck_notification_delivery_worker_claim_v30 CHECK (
        (status IN ('RUNNING', 'RECONCILING')
            AND claimed_by IS NOT NULL AND BTRIM(claimed_by) <> ''
            AND lease_expires_at IS NOT NULL AND heartbeat_at IS NOT NULL
            AND lease_expires_at > heartbeat_at)
        OR (status NOT IN ('RUNNING', 'RECONCILING')
            AND claimed_by IS NULL AND lease_expires_at IS NULL AND heartbeat_at IS NULL)
    );

DROP INDEX crewscope.ix_notification_delivery_claim_v27;

CREATE INDEX ix_notification_delivery_execution_due_v30
    ON crewscope.notification_delivery (
        organization_id, status, next_attempt_at, created_at, delivery_id
    ) WHERE status IN ('READY', 'RETRY_WAIT');

CREATE INDEX ix_notification_delivery_reconciliation_due_v30
    ON crewscope.notification_delivery (
        organization_id, status, lease_expires_at, updated_at, delivery_id
    ) WHERE status IN ('RUNNING', 'UNKNOWN', 'RECONCILING');

-- Operations recovery remains an immutable authorization fact; completion points to the newly
-- planned Delivery and never rewrites the original FAILED_FINAL history.
ALTER TABLE crewscope.operations_recovery_schedule
    ADD COLUMN replacement_delivery_id UUID,
    ADD CONSTRAINT fk_operations_recovery_replacement_v30
        FOREIGN KEY (organization_id, replacement_delivery_id)
        REFERENCES crewscope.notification_delivery (organization_id, delivery_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_operations_recovery_completion_v30 CHECK (
        (status = 'COMPLETED' AND replacement_delivery_id IS NOT NULL
            AND completed_at IS NOT NULL AND claimed_by IS NULL AND lease_expires_at IS NULL)
        OR (status <> 'COMPLETED' AND replacement_delivery_id IS NULL
            AND completed_at IS NULL)
    ),
    ADD CONSTRAINT ck_operations_recovery_claim_clear_v30 CHECK (
        (status = 'CLAIMED' AND claimed_by IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'CLAIMED' AND claimed_by IS NULL AND lease_expires_at IS NULL)
    );

CREATE INDEX ix_operations_notification_recovery_due_v30
    ON crewscope.operations_recovery_schedule (
        organization_id, status, lease_expires_at, accepted_at, schedule_id
    ) WHERE recovery_action = 'RETRY_NOTIFICATION_DELIVERY'
        AND status IN ('PENDING', 'CLAIMED');

CREATE OR REPLACE FUNCTION crewscope.guard_operations_recovery_schedule_v29()
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
        OR NEW.version <> OLD.version + 1
        OR (OLD.replacement_delivery_id IS NOT NULL
            AND NEW.replacement_delivery_id IS DISTINCT FROM OLD.replacement_delivery_id) THEN
        RAISE EXCEPTION 'operations recovery schedule immutable coordinates changed';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- V30 extends the frozen business state machine with query deferral and technical re-fencing.
CREATE OR REPLACE FUNCTION crewscope.guard_notification_delivery_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'NotificationDelivery cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.organization_id, NEW.delivery_id, NEW.action_id, NEW.action_digest,
           NEW.deduplication_key, NEW.redelivery_of, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.organization_id, OLD.delivery_id, OLD.action_id, OLD.action_digest,
           OLD.deduplication_key, OLD.redelivery_of, OLD.created_at)
       OR NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at
       OR NEW.claim_token < OLD.claim_token
       OR NEW.reconciliation_count < OLD.reconciliation_count
       OR NOT (
            (OLD.status = 'READY' AND NEW.status IN ('RUNNING', 'INVALIDATED', 'CANCELLED'))
            OR (OLD.status = 'RUNNING' AND NEW.status IN (
                'RETRY_WAIT', 'UNKNOWN', 'SUCCEEDED', 'FAILED_FINAL', 'INVALIDATED'
            ))
            OR (OLD.status = 'RETRY_WAIT' AND NEW.status IN (
                'RUNNING', 'INVALIDATED', 'CANCELLED'
            ))
            OR (OLD.status = 'UNKNOWN' AND NEW.status IN ('RECONCILING', 'INVALIDATED'))
            OR (OLD.status = 'RECONCILING' AND NEW.status IN (
                'RECONCILING', 'UNKNOWN', 'RETRY_WAIT', 'SUCCEEDED',
                'FAILED_FINAL', 'INVALIDATED'
            ))
       ) THEN
        RAISE EXCEPTION 'invalid NotificationDelivery transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
