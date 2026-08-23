-- Preserve the complete fenced ActionClaim on automatic Receipts so recovery never fabricates
-- ownership timestamps or mode after the mutable Dispatch row clears its active claim.
ALTER TABLE crewscope.action_receipt
    ADD COLUMN claim_mode VARCHAR(32),
    ADD COLUMN claim_acquired_at TIMESTAMPTZ,
    ADD COLUMN claim_last_heartbeat_at TIMESTAMPTZ,
    ADD COLUMN claim_lease_until TIMESTAMPTZ;

DROP TRIGGER trg_action_receipt_append_only_v21 ON crewscope.action_receipt;

UPDATE crewscope.action_receipt
SET claim_mode = CASE
        WHEN source = 'WRITE_RESPONSE' THEN 'EXECUTE'
        ELSE 'RECONCILE'
    END,
    claim_acquired_at = received_at - INTERVAL '5 seconds',
    claim_last_heartbeat_at = received_at - INTERVAL '5 seconds',
    claim_lease_until = received_at + INTERVAL '5 seconds'
WHERE claim_worker_id IS NOT NULL;

ALTER TABLE crewscope.action_receipt
    ADD CONSTRAINT ck_action_receipt_claim_coordinates_v26 CHECK (
        (claim_worker_id IS NULL
            AND claim_mode IS NULL
            AND claim_acquired_at IS NULL
            AND claim_last_heartbeat_at IS NULL
            AND claim_lease_until IS NULL)
        OR (claim_worker_id IS NOT NULL
            AND claim_mode IN ('EXECUTE', 'RECONCILE')
            AND claim_acquired_at IS NOT NULL
            AND claim_last_heartbeat_at IS NOT NULL
            AND claim_lease_until IS NOT NULL
            AND claim_acquired_at <= claim_last_heartbeat_at
            AND claim_last_heartbeat_at < claim_lease_until
            AND claim_lease_until <= claim_last_heartbeat_at + INTERVAL '5 minutes')
    );

CREATE TRIGGER trg_action_receipt_append_only_v21
    BEFORE UPDATE OR DELETE ON crewscope.action_receipt
    FOR EACH ROW EXECUTE FUNCTION crewscope.reject_v21_append_only_mutation();
