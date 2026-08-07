-- Existing V1/V2 facts predate aggregate version tracking and represent their initial snapshot.
-- The default is removed after backfill so every new event writer must provide the version.
ALTER TABLE crewscope.domain_event
    ADD COLUMN aggregate_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE crewscope.domain_event
    ALTER COLUMN aggregate_version DROP DEFAULT,
    ADD CONSTRAINT ck_domain_event_aggregate_version CHECK (aggregate_version >= 0),
    ADD CONSTRAINT ck_domain_event_idempotency_key CHECK (
        idempotency_key IS NULL OR BTRIM(idempotency_key) <> ''
    );

-- Outbox routing values are part of the durable publication contract.
ALTER TABLE crewscope.outbox_event
    ADD CONSTRAINT ck_outbox_topic CHECK (BTRIM(topic) <> ''),
    ADD CONSTRAINT ck_outbox_partition_key CHECK (BTRIM(partition_key) <> '');

-- Aggregate replay and gap detection always stay inside an Organization and use committed version.
DROP INDEX crewscope.ix_domain_event_subject;

CREATE INDEX ix_domain_event_subject
    ON crewscope.domain_event (
        organization_id,
        subject_type,
        subject_id,
        aggregate_version,
        occurred_at,
        event_id
    );
