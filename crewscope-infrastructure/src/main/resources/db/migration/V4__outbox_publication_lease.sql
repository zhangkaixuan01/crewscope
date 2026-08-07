-- Outbox delivery uses short database claims and publishes outside database transactions.
ALTER TABLE crewscope.outbox_event
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_by VARCHAR(200),
    ADD COLUMN claim_expires_at TIMESTAMPTZ,
    ADD COLUMN last_error_code VARCHAR(100);

ALTER TABLE crewscope.outbox_event
    DROP CONSTRAINT ck_outbox_delivery_status,
    ADD CONSTRAINT ck_outbox_delivery_status CHECK (
        delivery_status IN ('PENDING', 'CLAIMED', 'DELIVERED', 'DEAD_LETTER')
    ),
    ADD CONSTRAINT ck_outbox_claim_fields CHECK (
        (delivery_status = 'CLAIMED'
            AND claim_token IS NOT NULL
            AND claimed_by IS NOT NULL
            AND claim_expires_at IS NOT NULL
            AND delivered_at IS NULL)
        OR
        (delivery_status <> 'CLAIMED'
            AND claim_token IS NULL
            AND claimed_by IS NULL
            AND claim_expires_at IS NULL)
    ),
    ADD CONSTRAINT ck_outbox_delivered_state CHECK (
        (delivery_status = 'DELIVERED' AND delivered_at IS NOT NULL)
        OR
        (delivery_status <> 'DELIVERED' AND delivered_at IS NULL)
    ),
    ADD CONSTRAINT ck_outbox_claimed_by CHECK (
        claimed_by IS NULL OR BTRIM(claimed_by) <> ''
    ),
    ADD CONSTRAINT ck_outbox_last_error_code CHECK (
        last_error_code IS NULL OR BTRIM(last_error_code) <> ''
    );

DROP INDEX crewscope.ix_outbox_pending;

CREATE INDEX ix_outbox_publishable
    ON crewscope.outbox_event (
        delivery_status,
        next_delivery_at,
        claim_expires_at,
        created_at,
        id
    );

CREATE INDEX ix_outbox_partition_order
    ON crewscope.outbox_event (topic, partition_key, created_at, id)
    WHERE delivery_status IN ('PENDING', 'CLAIMED');

-- A consumer receipt is inserted in the same local transaction as the consumer side effect.
CREATE TABLE crewscope.event_consumer_receipt (
    consumer_name VARCHAR(200) NOT NULL,
    domain_event_id UUID NOT NULL REFERENCES crewscope.domain_event (event_id),
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, domain_event_id),
    CONSTRAINT ck_event_consumer_receipt_name CHECK (BTRIM(consumer_name) <> '')
);

CREATE INDEX ix_event_consumer_receipt_event
    ON crewscope.event_consumer_receipt (domain_event_id, consumer_name);
