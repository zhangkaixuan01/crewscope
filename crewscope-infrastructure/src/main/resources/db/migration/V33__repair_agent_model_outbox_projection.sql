-- Agent configuration revisions are one-based, while DomainEvent aggregate versions are
-- zero-based. Repair previously emitted configuration events before retrying their projections.
UPDATE crewscope.domain_event
SET aggregate_version = (payload ->> 'revision')::BIGINT - 1
WHERE event_type = 'AGENT_CONFIGURATION_APPENDED'
  AND payload ->> 'revision' ~ '^[1-9][0-9]*$'
  AND aggregate_version = (payload ->> 'revision')::BIGINT;

-- These events were rejected by the Audit projector before any externally visible delivery.
-- Reset only the known compatibility failures; partition ordering will replay each aggregate in
-- aggregate-version, occurrence-time and event-id order.
UPDATE crewscope.outbox_event outbox
SET delivery_status = 'PENDING',
    retry_count = 0,
    next_delivery_at = CURRENT_TIMESTAMP,
    claim_token = NULL,
    claimed_by = NULL,
    claim_expires_at = NULL,
    last_error_code = NULL,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
FROM crewscope.domain_event event
WHERE event.event_id = outbox.domain_event_id
  AND outbox.delivery_status = 'DEAD_LETTER'
  AND outbox.last_error_code = 'TRANSPORT_FAILURE'
  AND event.subject_type IN ('AGENT_CONFIGURATION', 'MODEL_CONNECTION');
