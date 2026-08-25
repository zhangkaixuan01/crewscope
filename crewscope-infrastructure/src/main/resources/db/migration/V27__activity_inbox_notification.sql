-- M6 projection registry, generation-aware read models, Inbox authority and fixed-template
-- notification persistence. Existing single-generation checkpoints stay available until M6-E01
-- switches the runner; their committed positions are copied into Generation 1 below.

CREATE TABLE crewscope.projection_definition (
    projection_name VARCHAR(180) NOT NULL,
    definition_version BIGINT NOT NULL,
    projection_schema_version INTEGER NOT NULL,
    canonical_encoder VARCHAR(160) NOT NULL,
    validator VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (projection_name, definition_version),
    CONSTRAINT ck_projection_definition_name_v27 CHECK (
        projection_name ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'
    ),
    CONSTRAINT ck_projection_definition_versions_v27 CHECK (
        definition_version > 0 AND projection_schema_version > 0
    ),
    CONSTRAINT ck_projection_definition_components_v27 CHECK (
        canonical_encoder ~ '^[a-z][a-z0-9]*([.-][a-z0-9]+)*$'
        AND validator ~ '^[a-z][a-z0-9]*([.-][a-z0-9]+)*$'
    )
);

CREATE TABLE crewscope.projection_generation (
    organization_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    definition_version BIGINT NOT NULL,
    rebuild_job_id UUID,
    status VARCHAR(24) NOT NULL,
    fencing_token BIGINT NOT NULL,
    current_validation_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, projection_name, generation),
    CONSTRAINT uk_projection_generation_job_v27 UNIQUE (
        organization_id, projection_name, generation, rebuild_job_id
    ),
    CONSTRAINT uk_projection_generation_token_v27 UNIQUE (
        organization_id, projection_name, generation, fencing_token
    ),
    CONSTRAINT fk_projection_generation_organization_v27
        FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_projection_generation_definition_v27
        FOREIGN KEY (projection_name, definition_version)
        REFERENCES crewscope.projection_definition (projection_name, definition_version)
        ON DELETE RESTRICT,
    CONSTRAINT ck_projection_generation_values_v27 CHECK (
        generation > 0 AND fencing_token > 0 AND version >= 0
        AND updated_at >= created_at
    ),
    CONSTRAINT ck_projection_generation_status_v27 CHECK (
        status IN ('BUILDING', 'VALIDATING', 'ACTIVE', 'RETIRED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_projection_generation_job_shape_v27 CHECK (
        (status IN ('BUILDING', 'VALIDATING', 'FAILED', 'CANCELLED')
            AND rebuild_job_id IS NOT NULL)
        OR (status IN ('ACTIVE', 'RETIRED'))
    ),
    CONSTRAINT ck_projection_generation_validation_shape_v27 CHECK (
        status <> 'VALIDATING' OR current_validation_id IS NOT NULL
    )
);

CREATE UNIQUE INDEX ux_projection_generation_active_v27
    ON crewscope.projection_generation (organization_id, projection_name)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX ux_projection_generation_shadow_v27
    ON crewscope.projection_generation (organization_id, projection_name)
    WHERE status IN ('BUILDING', 'VALIDATING');

CREATE INDEX ix_projection_generation_router_v27
    ON crewscope.projection_generation (
        organization_id, projection_name, status, generation
    );

CREATE TABLE crewscope.projection_pointer (
    organization_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    active_generation BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, projection_name),
    CONSTRAINT fk_projection_pointer_generation_v27
        FOREIGN KEY (organization_id, projection_name, active_generation)
        REFERENCES crewscope.projection_generation (
            organization_id, projection_name, generation
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_projection_pointer_values_v27 CHECK (
        active_generation > 0 AND version >= 0
    )
);

CREATE TABLE crewscope.projection_rebuild_job (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    definition_version BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    retry_of UUID,
    requested_by_principal_id UUID NOT NULL,
    status VARCHAR(24) NOT NULL,
    current_validation_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_projection_rebuild_scope_id_v27 UNIQUE (organization_id, id),
    CONSTRAINT uk_projection_rebuild_generation_v27 UNIQUE (
        organization_id, projection_name, generation
    ),
    CONSTRAINT uk_projection_rebuild_exact_generation_v27 UNIQUE (
        organization_id, projection_name, generation, id
    ),
    CONSTRAINT uk_projection_rebuild_validation_v27 UNIQUE (
        organization_id, projection_name, generation, id, current_validation_id,
        definition_version
    ),
    CONSTRAINT fk_projection_rebuild_generation_v27
        FOREIGN KEY (organization_id, projection_name, generation, id)
        REFERENCES crewscope.projection_generation (
            organization_id, projection_name, generation, rebuild_job_id
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_projection_rebuild_retry_v27
        FOREIGN KEY (organization_id, retry_of)
        REFERENCES crewscope.projection_rebuild_job (organization_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_projection_rebuild_requester_v27
        FOREIGN KEY (organization_id, requested_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_projection_rebuild_definition_v27
        FOREIGN KEY (projection_name, definition_version)
        REFERENCES crewscope.projection_definition (projection_name, definition_version)
        ON DELETE RESTRICT,
    CONSTRAINT ck_projection_rebuild_values_v27 CHECK (
        generation > 0 AND version >= 0 AND updated_at >= created_at
        AND (retry_of IS NULL OR retry_of <> id)
    ),
    CONSTRAINT ck_projection_rebuild_status_v27 CHECK (
        status IN ('BUILDING', 'VALIDATING', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_projection_rebuild_validation_shape_v27 CHECK (
        status NOT IN ('VALIDATING', 'COMPLETED') OR current_validation_id IS NOT NULL
    )
);

CREATE INDEX ix_projection_rebuild_history_v27
    ON crewscope.projection_rebuild_job (
        organization_id, projection_name, created_at DESC, id DESC
    );

CREATE TABLE crewscope.projection_validation_result (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    rebuild_job_id UUID NOT NULL,
    definition_version BIGINT NOT NULL,
    expected_row_count BIGINT NOT NULL,
    expected_canonical_hash CHAR(64) NOT NULL,
    expected_gap_count BIGINT NOT NULL,
    actual_row_count BIGINT NOT NULL,
    actual_canonical_hash CHAR(64) NOT NULL,
    actual_gap_count BIGINT NOT NULL,
    passed BOOLEAN NOT NULL,
    validated_by_principal_id UUID NOT NULL,
    validated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_projection_validation_binding_v27 UNIQUE (
        organization_id, projection_name, generation, rebuild_job_id, id,
        definition_version
    ),
    CONSTRAINT fk_projection_validation_job_v27
        FOREIGN KEY (
            organization_id, projection_name, generation, rebuild_job_id
        ) REFERENCES crewscope.projection_rebuild_job (
            organization_id, projection_name, generation, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_projection_validation_actor_v27
        FOREIGN KEY (organization_id, validated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_projection_validation_counts_v27 CHECK (
        expected_row_count >= 0 AND expected_gap_count >= 0
        AND actual_row_count >= 0 AND actual_gap_count >= 0
    ),
    CONSTRAINT ck_projection_validation_hashes_v27 CHECK (
        expected_canonical_hash ~ '^[0-9a-f]{64}$'
        AND actual_canonical_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_projection_validation_passed_v27 CHECK (
        NOT passed OR (
            expected_row_count = actual_row_count
            AND expected_canonical_hash = actual_canonical_hash
            AND expected_gap_count = 0 AND actual_gap_count = 0
        )
    )
);

CREATE TABLE crewscope.projection_validation_failed_partition (
    validation_id UUID NOT NULL,
    snapshot_side VARCHAR(8) NOT NULL,
    partition_hash CHAR(64) NOT NULL,
    failure_code VARCHAR(80) NOT NULL,
    PRIMARY KEY (validation_id, snapshot_side, partition_hash, failure_code),
    CONSTRAINT fk_projection_validation_partition_v27
        FOREIGN KEY (validation_id)
        REFERENCES crewscope.projection_validation_result (id) ON DELETE RESTRICT,
    CONSTRAINT ck_projection_validation_partition_side_v27 CHECK (
        snapshot_side IN ('EXPECTED', 'ACTUAL')
    ),
    CONSTRAINT ck_projection_validation_partition_values_v27 CHECK (
        partition_hash ~ '^[0-9a-f]{64}$'
        AND failure_code ~ '^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$'
    )
);

ALTER TABLE crewscope.projection_generation
    ADD CONSTRAINT fk_projection_generation_job_v27
        FOREIGN KEY (organization_id, projection_name, generation, rebuild_job_id)
        REFERENCES crewscope.projection_rebuild_job (
            organization_id, projection_name, generation, id
        ) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_projection_generation_validation_v27
        FOREIGN KEY (
            organization_id, projection_name, generation, rebuild_job_id,
            current_validation_id, definition_version
        ) REFERENCES crewscope.projection_validation_result (
            organization_id, projection_name, generation, rebuild_job_id,
            id, definition_version
        ) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE crewscope.projection_rebuild_job
    ADD CONSTRAINT fk_projection_rebuild_current_validation_v27
        FOREIGN KEY (
            organization_id, projection_name, generation, id,
            current_validation_id, definition_version
        ) REFERENCES crewscope.projection_validation_result (
            organization_id, projection_name, generation, rebuild_job_id,
            id, definition_version
        ) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE crewscope.projection_consumer_receipt (
    organization_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    consumer_name VARCHAR(200) NOT NULL,
    domain_event_id UUID NOT NULL,
    fencing_token BIGINT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (
        organization_id, projection_name, generation, consumer_name, domain_event_id
    ),
    CONSTRAINT fk_projection_receipt_generation_v27
        FOREIGN KEY (organization_id, projection_name, generation)
        REFERENCES crewscope.projection_generation (
            organization_id, projection_name, generation
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_projection_receipt_event_v27
        FOREIGN KEY (organization_id, domain_event_id)
        REFERENCES crewscope.domain_event (organization_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_projection_receipt_values_v27 CHECK (
        BTRIM(consumer_name) <> '' AND fencing_token > 0
    )
);

CREATE INDEX ix_projection_receipt_event_v27
    ON crewscope.projection_consumer_receipt (
        organization_id, domain_event_id, projection_name, generation
    );

CREATE TABLE crewscope.projection_generation_checkpoint (
    organization_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    partition_key VARCHAR(240) NOT NULL,
    last_event_id UUID,
    last_event_cursor VARCHAR(500),
    last_event_occurred_at TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, projection_name, generation, partition_key),
    CONSTRAINT fk_projection_generation_checkpoint_generation_v27
        FOREIGN KEY (organization_id, projection_name, generation)
        REFERENCES crewscope.projection_generation (
            organization_id, projection_name, generation
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_projection_generation_checkpoint_event_v27
        FOREIGN KEY (organization_id, last_event_id)
        REFERENCES crewscope.domain_event (organization_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_projection_generation_checkpoint_values_v27 CHECK (
        BTRIM(partition_key) <> '' AND fencing_token > 0 AND version >= 0
        AND updated_at >= created_at
    ),
    CONSTRAINT ck_projection_generation_checkpoint_event_v27 CHECK (
        (last_event_id IS NULL AND last_event_cursor IS NULL
            AND last_event_occurred_at IS NULL)
        OR (last_event_id IS NOT NULL AND BTRIM(last_event_cursor) <> ''
            AND last_event_occurred_at IS NOT NULL)
    )
);

CREATE INDEX ix_projection_generation_checkpoint_updated_v27
    ON crewscope.projection_generation_checkpoint (
        organization_id, projection_name, generation, updated_at
    );

CREATE TABLE crewscope.projection_dead_letter (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    domain_event_id UUID NOT NULL,
    partition_hash CHAR(64) NOT NULL,
    failure_code VARCHAR(80) NOT NULL,
    fencing_token BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_projection_dead_letter_event_v27 UNIQUE (
        organization_id, projection_name, generation, domain_event_id
    ),
    CONSTRAINT fk_projection_dead_letter_generation_v27
        FOREIGN KEY (organization_id, projection_name, generation)
        REFERENCES crewscope.projection_generation (
            organization_id, projection_name, generation
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_projection_dead_letter_event_v27
        FOREIGN KEY (organization_id, domain_event_id)
        REFERENCES crewscope.domain_event (organization_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_projection_dead_letter_values_v27 CHECK (
        partition_hash ~ '^[0-9a-f]{64}$'
        AND failure_code ~ '^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$'
        AND fencing_token > 0
    )
);

CREATE INDEX ix_projection_dead_letter_generation_v27
    ON crewscope.projection_dead_letter (
        organization_id, projection_name, generation, created_at, id
    );

CREATE TABLE crewscope.projection_command_receipt (
    organization_id UUID NOT NULL,
    command_id UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    rebuild_job_id UUID NOT NULL,
    generation_status VARCHAR(24) NOT NULL,
    rebuild_status VARCHAR(24) NOT NULL,
    pointer_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, command_id),
    CONSTRAINT fk_projection_command_receipt_job_v27
        FOREIGN KEY (
            organization_id, projection_name, generation, rebuild_job_id
        ) REFERENCES crewscope.projection_rebuild_job (
            organization_id, projection_name, generation, id
        )
        ON DELETE RESTRICT,
    CONSTRAINT ck_projection_command_receipt_values_v27 CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$' AND generation > 0
        AND (pointer_version IS NULL OR pointer_version >= 0)
        AND generation_status IN (
            'BUILDING', 'VALIDATING', 'ACTIVE', 'RETIRED', 'FAILED', 'CANCELLED'
        )
        AND rebuild_status IN ('BUILDING', 'VALIDATING', 'COMPLETED', 'FAILED', 'CANCELLED')
    )
);

-- Existing M0 projections become the first active generation without losing their committed
-- checkpoint. M6-E01 will switch reads and writes to the generation-aware checkpoint table.
INSERT INTO crewscope.projection_definition (
    projection_name, definition_version, projection_schema_version,
    canonical_encoder, validator
)
SELECT DISTINCT projection_name, 1, 1, 'legacy.v1', 'legacy.v1'
FROM crewscope.event_projection_checkpoint
ON CONFLICT (projection_name, definition_version) DO NOTHING;

INSERT INTO crewscope.projection_generation (
    organization_id, projection_name, generation, definition_version,
    status, fencing_token, version, created_at, updated_at
)
SELECT organization_id, projection_name, 1, 1, 'ACTIVE', 1, 0,
       MIN(created_at), MAX(updated_at)
FROM crewscope.event_projection_checkpoint
GROUP BY organization_id, projection_name;

INSERT INTO crewscope.projection_pointer (
    organization_id, projection_name, active_generation, version, updated_at
)
SELECT organization_id, projection_name, 1, 0, updated_at
FROM crewscope.projection_generation
WHERE generation = 1 AND status = 'ACTIVE';

INSERT INTO crewscope.projection_generation_checkpoint (
    organization_id, projection_name, generation, partition_key,
    last_event_id, last_event_cursor, last_event_occurred_at,
    fencing_token, version, created_at, updated_at
)
SELECT organization_id, projection_name, 1, partition_key,
       last_event_id, last_event_cursor, last_event_occurred_at,
       1, version, created_at, updated_at
FROM crewscope.event_projection_checkpoint;

-- Generation-aware Activity rows retain stable event identity while isolating every rebuild.
CREATE TABLE crewscope.activity_event (
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    activity_event_id UUID NOT NULL,
    domain_event_id UUID NOT NULL,
    projection_schema_version INTEGER NOT NULL,
    team_sequence BIGINT NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    category VARCHAR(24) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_id UUID NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_principal_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    payload_schema_name VARCHAR(120) NOT NULL,
    payload_schema_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, projection_name, generation, activity_event_id),
    CONSTRAINT uk_activity_event_source_v27 UNIQUE (
        organization_id, projection_name, generation, domain_event_id
    ),
    CONSTRAINT uk_activity_event_team_sequence_v27 UNIQUE (
        organization_id, team_id, projection_name, generation, team_sequence
    ),
    CONSTRAINT fk_activity_event_generation_v27
        FOREIGN KEY (organization_id, projection_name, generation)
        REFERENCES crewscope.projection_generation (
            organization_id, projection_name, generation
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_activity_event_team_v27
        FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_activity_event_domain_event_v27
        FOREIGN KEY (organization_id, domain_event_id)
        REFERENCES crewscope.domain_event (organization_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT fk_activity_event_actor_v27
        FOREIGN KEY (organization_id, actor_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_activity_event_values_v27 CHECK (
        projection_schema_version > 0 AND team_sequence > 0
        AND payload_schema_version > 0 AND BTRIM(event_type) <> ''
        AND BTRIM(payload_schema_name) <> '' AND JSONB_TYPEOF(payload) = 'object'
    ),
    CONSTRAINT ck_activity_event_category_v27 CHECK (
        category IN ('TEAM', 'WORK_ITEM', 'TASK', 'REVIEW', 'ACTION', 'PROVIDER', 'SYSTEM')
    ),
    CONSTRAINT ck_activity_event_visibility_v27 CHECK (
        visibility IN ('TEAM_MEMBERS', 'WORK_ITEM_PARTICIPANTS', 'TEAM_ADMINS')
    ),
    CONSTRAINT ck_activity_event_subject_v27 CHECK (
        subject_type IN (
            'TEAM', 'WORK_ITEM', 'TASK', 'REVIEW', 'ACTION', 'PROVIDER_BINDING',
            'ARTIFACT', 'CONVERSATION'
        ) AND (subject_type <> 'TEAM' OR subject_id = team_id)
    ),
    CONSTRAINT ck_activity_event_actor_v27 CHECK (
        (actor_type = 'SERVICE' AND actor_principal_id IS NULL)
        OR (actor_type IN ('USER', 'PERSONAL_AGENT', 'TEAM_AGENT', 'SPECIALIST_AGENT')
            AND actor_principal_id IS NOT NULL)
    )
);

CREATE INDEX ix_activity_event_team_cursor_v27
    ON crewscope.activity_event (
        organization_id, team_id, projection_name, generation,
        team_sequence, activity_event_id
    );

CREATE INDEX ix_activity_event_subject_cursor_v27
    ON crewscope.activity_event (
        organization_id, team_id, projection_name, generation,
        subject_type, subject_id, team_sequence, activity_event_id
    );

CREATE TABLE crewscope.activity_reference (
    organization_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    activity_event_id UUID NOT NULL,
    reference_order INTEGER NOT NULL,
    reference_type VARCHAR(32) NOT NULL,
    reference_id UUID NOT NULL,
    PRIMARY KEY (
        organization_id, projection_name, generation, activity_event_id, reference_order
    ),
    CONSTRAINT uk_activity_reference_value_v27 UNIQUE (
        organization_id, projection_name, generation, activity_event_id,
        reference_type, reference_id
    ),
    CONSTRAINT fk_activity_reference_event_v27
        FOREIGN KEY (organization_id, projection_name, generation, activity_event_id)
        REFERENCES crewscope.activity_event (
            organization_id, projection_name, generation, activity_event_id
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_activity_reference_values_v27 CHECK (
        reference_order >= 0 AND reference_type IN (
            'TEAM', 'WORK_ITEM', 'TASK', 'REVIEW', 'ACTION', 'PROVIDER_BINDING',
            'ARTIFACT', 'CONVERSATION', 'PULL_REQUEST'
        )
    )
);

CREATE INDEX ix_activity_reference_lookup_v27
    ON crewscope.activity_reference (
        organization_id, reference_type, reference_id,
        projection_name, generation, activity_event_id
    );

-- Inbox projection rows are replaceable by generation; member disposition is a separate authority.
CREATE TABLE crewscope.inbox_item (
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    member_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    inbox_item_id UUID NOT NULL,
    projection_schema_version INTEGER NOT NULL,
    item_type VARCHAR(24) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id UUID NOT NULL,
    source_revision BIGINT NOT NULL,
    priority VARCHAR(16) NOT NULL,
    deadline TIMESTAMPTZ,
    opened_at TIMESTAMPTZ NOT NULL,
    source_status VARCHAR(16) NOT NULL,
    close_reason VARCHAR(48),
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, projection_name, generation, inbox_item_id),
    CONSTRAINT uk_inbox_source_generation_v27 UNIQUE (
        organization_id, projection_name, generation, member_id,
        item_type, source_type, source_id, source_revision
    ),
    CONSTRAINT uk_inbox_intent_binding_v27 UNIQUE (
        organization_id, team_id, member_id, projection_name, generation,
        inbox_item_id, item_type, source_type, source_id, source_revision
    ),
    CONSTRAINT fk_inbox_item_generation_v27
        FOREIGN KEY (organization_id, projection_name, generation)
        REFERENCES crewscope.projection_generation (
            organization_id, projection_name, generation
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_inbox_item_member_v27
        FOREIGN KEY (organization_id, team_id, member_id)
        REFERENCES crewscope.team_member (organization_id, team_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_inbox_item_values_v27 CHECK (
        projection_schema_version > 0 AND source_revision >= 0
        AND priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')
        AND (deadline IS NULL OR deadline >= opened_at)
    ),
    CONSTRAINT ck_inbox_item_source_type_v27 CHECK (
        (item_type IN ('OWNERSHIP', 'EXECUTION')
            AND source_type = 'RESPONSIBILITY_ASSIGNMENT')
        OR (item_type = 'REVIEW' AND source_type = 'REVIEW_REQUEST')
        OR (item_type = 'CONFIRMATION' AND source_type = 'ACTION_CONFIRMATION')
        OR (item_type = 'EXCEPTION' AND source_type IN (
            'TASK_EXECUTION', 'ACTION_DELIVERY', 'NOTIFICATION_DELIVERY'
        ))
    ),
    CONSTRAINT ck_inbox_item_terminal_v27 CHECK (
        (source_status = 'OPEN' AND close_reason IS NULL AND closed_at IS NULL)
        OR (source_status = 'CLOSED' AND close_reason IS NOT NULL
            AND closed_at IS NOT NULL AND closed_at >= opened_at)
    ),
    CONSTRAINT ck_inbox_item_close_reason_v27 CHECK (
        close_reason IS NULL OR close_reason = 'MEMBER_NO_LONGER_ELIGIBLE'
        OR (item_type IN ('OWNERSHIP', 'EXECUTION') AND close_reason IN (
            'RESPONSIBILITY_RELEASED', 'RESPONSIBILITY_REPLACED'
        ))
        OR (item_type = 'REVIEW' AND close_reason IN (
            'REVIEW_COMPLETED', 'REVIEW_SUPERSEDED'
        ))
        OR (item_type = 'CONFIRMATION' AND close_reason IN (
            'CONFIRMATION_COMPLETED', 'CONFIRMATION_CANCELLED', 'CONFIRMATION_EXPIRED'
        ))
        OR (item_type = 'EXCEPTION' AND close_reason IN (
            'EXCEPTION_RECOVERED', 'EXCEPTION_RESOLVED'
        ))
    )
);

CREATE INDEX ix_inbox_item_member_queue_v27
    ON crewscope.inbox_item (
        organization_id, team_id, member_id, projection_name, generation,
        source_status, priority, deadline, opened_at, inbox_item_id
    );

CREATE INDEX ix_inbox_item_source_v27
    ON crewscope.inbox_item (
        organization_id, source_type, source_id, source_revision,
        projection_name, generation
    );

CREATE TABLE crewscope.inbox_disposition (
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    member_id UUID NOT NULL,
    inbox_item_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    PRIMARY KEY (organization_id, inbox_item_id),
    CONSTRAINT uk_inbox_disposition_scope_v27 UNIQUE (
        organization_id, team_id, member_id, inbox_item_id
    ),
    CONSTRAINT fk_inbox_disposition_member_v27
        FOREIGN KEY (organization_id, team_id, member_id)
        REFERENCES crewscope.team_member (organization_id, team_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_inbox_disposition_created_by_v27
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_inbox_disposition_updated_by_v27
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_inbox_disposition_values_v27 CHECK (
        status IN ('READ', 'ACTED', 'ARCHIVED') AND version > 0
        AND updated_at >= created_at
    )
);

CREATE INDEX ix_inbox_disposition_member_v27
    ON crewscope.inbox_disposition (
        organization_id, team_id, member_id, updated_at DESC, inbox_item_id
    );

CREATE TABLE crewscope.notification_template (
    template_id UUID NOT NULL,
    template_version BIGINT NOT NULL,
    server_template_key VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (template_id, template_version),
    CONSTRAINT uk_notification_template_registry_key_v27 UNIQUE (
        server_template_key, template_version
    ),
    CONSTRAINT ck_notification_template_values_v27 CHECK (
        template_version > 0
        AND server_template_key ~ '^[a-z][a-z0-9._-]{2,127}$'
        AND status IN ('PUBLISHED', 'RETIRED')
    )
);

CREATE TABLE crewscope.notification_template_variable (
    template_id UUID NOT NULL,
    template_version BIGINT NOT NULL,
    variable_name VARCHAR(64) NOT NULL,
    variable_type VARCHAR(16) NOT NULL,
    maximum_length INTEGER NOT NULL,
    trusted_origins JSONB NOT NULL DEFAULT '[]'::JSONB,
    PRIMARY KEY (template_id, template_version, variable_name),
    CONSTRAINT fk_notification_template_variable_v27
        FOREIGN KEY (template_id, template_version)
        REFERENCES crewscope.notification_template (template_id, template_version)
        ON DELETE RESTRICT,
    CONSTRAINT ck_notification_template_variable_values_v27 CHECK (
        variable_name ~ '^[a-z][a-zA-Z0-9]{0,63}$'
        AND variable_type IN ('TEXT', 'TRUSTED_LINK')
        AND maximum_length BETWEEN 1 AND 4000
        AND JSONB_TYPEOF(trusted_origins) = 'array'
        AND ((variable_type = 'TEXT' AND JSONB_ARRAY_LENGTH(trusted_origins) = 0)
            OR (variable_type = 'TRUSTED_LINK'
                AND JSONB_ARRAY_LENGTH(trusted_origins) > 0))
    )
);

CREATE TABLE crewscope.notification_preference (
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    member_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL,
    enabled_item_types JSONB NOT NULL,
    muted_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    PRIMARY KEY (organization_id, team_id, member_id),
    CONSTRAINT fk_notification_preference_member_v27
        FOREIGN KEY (organization_id, team_id, member_id)
        REFERENCES crewscope.team_member (organization_id, team_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_notification_preference_created_by_v27
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_preference_updated_by_v27
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_notification_preference_values_v27 CHECK (
        JSONB_TYPEOF(enabled_item_types) = 'array' AND version >= 0
        AND updated_at >= created_at
    )
);

CREATE TABLE crewscope.notification_intent (
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    recipient_member_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    intent_id UUID NOT NULL,
    projection_schema_version INTEGER NOT NULL,
    inbox_item_id UUID NOT NULL,
    item_type VARCHAR(24) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id UUID NOT NULL,
    source_revision BIGINT NOT NULL,
    template_id UUID NOT NULL,
    template_version BIGINT NOT NULL,
    variables JSONB NOT NULL,
    variable_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, projection_name, generation, intent_id),
    CONSTRAINT uk_notification_intent_item_v27 UNIQUE (
        organization_id, projection_name, generation, inbox_item_id
    ),
    CONSTRAINT uk_notification_intent_action_binding_v27 UNIQUE (
        organization_id, team_id, recipient_member_id,
        projection_name, generation, intent_id
    ),
    CONSTRAINT fk_notification_intent_generation_v27
        FOREIGN KEY (organization_id, projection_name, generation)
        REFERENCES crewscope.projection_generation (
            organization_id, projection_name, generation
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_intent_inbox_v27
        FOREIGN KEY (
            organization_id, team_id, recipient_member_id, projection_name, generation,
            inbox_item_id, item_type, source_type, source_id, source_revision
        )
        REFERENCES crewscope.inbox_item (
            organization_id, team_id, member_id, projection_name, generation,
            inbox_item_id, item_type, source_type, source_id, source_revision
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_intent_member_v27
        FOREIGN KEY (organization_id, team_id, recipient_member_id)
        REFERENCES crewscope.team_member (organization_id, team_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_notification_intent_template_v27
        FOREIGN KEY (template_id, template_version)
        REFERENCES crewscope.notification_template (template_id, template_version)
        ON DELETE RESTRICT,
    CONSTRAINT ck_notification_intent_values_v27 CHECK (
        projection_schema_version > 0 AND source_revision >= 0
        AND JSONB_TYPEOF(variables) = 'object'
        AND variable_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_notification_intent_recipient_v27
    ON crewscope.notification_intent (
        organization_id, team_id, recipient_member_id,
        projection_name, generation, created_at, intent_id
    );

CREATE TABLE crewscope.notification_planned_action (
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    recipient_member_id UUID NOT NULL,
    projection_name VARCHAR(180) NOT NULL,
    generation BIGINT NOT NULL,
    action_id UUID NOT NULL,
    intent_id UUID NOT NULL,
    source_identity_hash CHAR(64) NOT NULL,
    template_id UUID NOT NULL,
    template_version BIGINT NOT NULL,
    variable_hash CHAR(64) NOT NULL,
    recipient_mapping_id UUID NOT NULL,
    recipient_mapping_version BIGINT NOT NULL,
    provider_binding_id UUID NOT NULL,
    provider_binding_version BIGINT NOT NULL,
    connection_id UUID NOT NULL,
    connection_version BIGINT NOT NULL,
    connection_grant_id UUID NOT NULL,
    connection_grant_version BIGINT NOT NULL,
    team_policy_id UUID NOT NULL,
    team_policy_version BIGINT NOT NULL,
    preference_version BIGINT NOT NULL,
    deduplication_key CHAR(64) NOT NULL,
    authorization_digest CHAR(64) NOT NULL,
    not_before TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    invalidation_reason VARCHAR(32),
    redelivery_of UUID,
    action_digest CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, action_id),
    CONSTRAINT uk_notification_action_dedup_v27 UNIQUE (
        organization_id, deduplication_key
    ),
    CONSTRAINT uk_notification_action_reference_v27 UNIQUE (
        organization_id, action_id, action_digest, deduplication_key
    ),
    CONSTRAINT fk_notification_action_member_v27
        FOREIGN KEY (organization_id, team_id, recipient_member_id)
        REFERENCES crewscope.team_member (organization_id, team_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_notification_action_intent_v27
        FOREIGN KEY (
            organization_id, team_id, recipient_member_id,
            projection_name, generation, intent_id
        ) REFERENCES crewscope.notification_intent (
            organization_id, team_id, recipient_member_id,
            projection_name, generation, intent_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_action_template_v27
        FOREIGN KEY (template_id, template_version)
        REFERENCES crewscope.notification_template (template_id, template_version)
        ON DELETE RESTRICT,
    CONSTRAINT fk_notification_action_connection_v27
        FOREIGN KEY (organization_id, connection_id)
        REFERENCES crewscope.connection (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_notification_action_values_v27 CHECK (
        source_identity_hash ~ '^[0-9a-f]{64}$'
        AND variable_hash ~ '^[0-9a-f]{64}$'
        AND deduplication_key ~ '^[0-9a-f]{64}$'
        AND authorization_digest ~ '^[0-9a-f]{64}$'
        AND action_digest ~ '^[0-9a-f]{64}$'
        AND recipient_mapping_version >= 0 AND provider_binding_version >= 0
        AND connection_version >= 0 AND connection_grant_version >= 0
        AND team_policy_version >= 0 AND preference_version >= 0
        AND not_before < valid_until AND updated_at >= created_at AND version >= 0
    ),
    CONSTRAINT ck_notification_action_status_v27 CHECK (
        (status = 'PLANNED' AND invalidation_reason IS NULL)
        OR (status = 'INVALIDATED' AND invalidation_reason IN (
            'SOURCE', 'TEMPLATE', 'VARIABLES', 'RECIPIENT_MAPPING',
            'PROVIDER_BINDING', 'CONNECTION', 'GRANT', 'TEAM_POLICY',
            'MEMBER_PREFERENCE'
        ))
    )
);

CREATE INDEX ix_notification_action_intent_v27
    ON crewscope.notification_planned_action (
        organization_id, intent_id, created_at DESC, action_id DESC
    );

CREATE TABLE crewscope.notification_delivery (
    organization_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    action_id UUID NOT NULL,
    action_digest CHAR(64) NOT NULL,
    deduplication_key CHAR(64) NOT NULL,
    redelivery_of UUID,
    status VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    invalidation_reason VARCHAR(32),
    receipt_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, delivery_id),
    CONSTRAINT uk_notification_delivery_action_v27 UNIQUE (organization_id, action_id),
    CONSTRAINT uk_notification_delivery_dedup_v27 UNIQUE (
        organization_id, deduplication_key
    ),
    CONSTRAINT uk_notification_delivery_receipt_v27 UNIQUE (
        organization_id, delivery_id, action_id, action_digest,
        deduplication_key, receipt_id
    ),
    CONSTRAINT uk_notification_delivery_binding_v27 UNIQUE (
        organization_id, delivery_id, action_id, action_digest, deduplication_key
    ),
    CONSTRAINT fk_notification_delivery_action_v27
        FOREIGN KEY (organization_id, action_id, action_digest, deduplication_key)
        REFERENCES crewscope.notification_planned_action (
            organization_id, action_id, action_digest, deduplication_key
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_delivery_redelivery_v27
        FOREIGN KEY (organization_id, redelivery_of)
        REFERENCES crewscope.notification_delivery (organization_id, delivery_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_notification_delivery_values_v27 CHECK (
        attempt_count >= 0 AND version >= 0 AND updated_at >= created_at
    ),
    CONSTRAINT ck_notification_delivery_status_v27 CHECK (
        status IN (
            'READY', 'RUNNING', 'RETRY_WAIT', 'UNKNOWN', 'RECONCILING',
            'SUCCEEDED', 'FAILED_FINAL', 'INVALIDATED', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_notification_delivery_shape_v27 CHECK (
        (status = 'RETRY_WAIT') = (next_attempt_at IS NOT NULL)
        AND (status = 'INVALIDATED') = (invalidation_reason IS NOT NULL)
        AND (status IN ('SUCCEEDED', 'FAILED_FINAL', 'INVALIDATED', 'CANCELLED'))
            = (receipt_id IS NOT NULL)
    )
);

CREATE INDEX ix_notification_delivery_claim_v27
    ON crewscope.notification_delivery (
        status, next_attempt_at, created_at, delivery_id
    ) WHERE status IN ('READY', 'RETRY_WAIT', 'UNKNOWN', 'RECONCILING');

CREATE TABLE crewscope.notification_receipt (
    organization_id UUID NOT NULL,
    receipt_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    action_id UUID NOT NULL,
    action_digest CHAR(64) NOT NULL,
    deduplication_key CHAR(64) NOT NULL,
    result VARCHAR(24) NOT NULL,
    failure_code VARCHAR(40),
    provider_receipt_hash CHAR(64),
    provider_message_hash CHAR(64),
    evidence_code VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, receipt_id),
    CONSTRAINT uk_notification_receipt_delivery_v27 UNIQUE (
        organization_id, delivery_id
    ),
    CONSTRAINT uk_notification_receipt_binding_v27 UNIQUE (
        organization_id, receipt_id, delivery_id, action_id, action_digest,
        deduplication_key, result
    ),
    CONSTRAINT uk_notification_receipt_delivery_binding_v27 UNIQUE (
        organization_id, receipt_id, delivery_id, action_id, action_digest,
        deduplication_key
    ),
    CONSTRAINT fk_notification_receipt_delivery_v27
        FOREIGN KEY (
            organization_id, delivery_id, action_id, action_digest, deduplication_key
        ) REFERENCES crewscope.notification_delivery (
            organization_id, delivery_id, action_id, action_digest, deduplication_key
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_notification_receipt_values_v27 CHECK (
        action_digest ~ '^[0-9a-f]{64}$'
        AND deduplication_key ~ '^[0-9a-f]{64}$'
        AND evidence_code ~ '^[A-Z][A-Z0-9_]{2,63}$'
        AND (failure_code IS NULL OR failure_code IN (
            'RECIPIENT_UNAVAILABLE', 'AUTHORIZATION_REVOKED', 'PROVIDER_REJECTED',
            'RETRY_EXHAUSTED', 'RECONCILIATION_EXHAUSTED'
        ))
        AND (provider_receipt_hash IS NULL OR provider_receipt_hash ~ '^[0-9a-f]{64}$')
        AND (provider_message_hash IS NULL OR provider_message_hash ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_notification_receipt_result_v27 CHECK (
        (result = 'ACCEPTED' AND failure_code IS NULL
            AND provider_receipt_hash IS NOT NULL AND provider_message_hash IS NOT NULL)
        OR (result = 'FAILED_FINAL' AND failure_code IS NOT NULL
            AND provider_receipt_hash IS NULL AND provider_message_hash IS NULL)
        OR (result IN ('INVALIDATED', 'CANCELLED') AND failure_code IS NULL
            AND provider_receipt_hash IS NULL AND provider_message_hash IS NULL)
    )
);

ALTER TABLE crewscope.notification_delivery
    ADD CONSTRAINT fk_notification_delivery_receipt_v27
        FOREIGN KEY (
            organization_id, receipt_id, delivery_id, action_id, action_digest,
            deduplication_key
        ) REFERENCES crewscope.notification_receipt (
            organization_id, receipt_id, delivery_id, action_id, action_digest,
            deduplication_key
        ) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE crewscope.notification_redelivery_receipt (
    organization_id UUID NOT NULL,
    command_id UUID NOT NULL,
    original_delivery_id UUID NOT NULL,
    replacement_delivery_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, command_id),
    CONSTRAINT fk_notification_redelivery_original_v27
        FOREIGN KEY (organization_id, original_delivery_id)
        REFERENCES crewscope.notification_delivery (organization_id, delivery_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_notification_redelivery_replacement_v27
        FOREIGN KEY (organization_id, replacement_delivery_id)
        REFERENCES crewscope.notification_delivery (organization_id, delivery_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_notification_redelivery_distinct_v27 CHECK (
        original_delivery_id <> replacement_delivery_id
    )
);

-- Add the stable Audit Explorer dimensions and composite UUID keyset indexes. Existing events are
-- classified as SYSTEM/STANDARD until M6-E06 rebuilds their safe query facts.
ALTER TABLE crewscope.audit_event
    ADD COLUMN event_category VARCHAR(24) NOT NULL DEFAULT 'SYSTEM',
    ADD COLUMN retention_level VARCHAR(24) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN provider_binding_id UUID,
    ADD COLUMN connection_id UUID,
    ADD COLUMN external_operation_hash CHAR(64),
    ADD CONSTRAINT ck_audit_event_category_v27 CHECK (
        event_category IN (
            'IDENTITY', 'TEAM', 'WORK', 'COLLABORATION', 'EXECUTION', 'AGENT',
            'MODEL', 'REVIEW', 'ACTION', 'PROVIDER', 'NOTIFICATION', 'PROJECTION',
            'SECURITY', 'SYSTEM'
        )
    ),
    ADD CONSTRAINT ck_audit_event_retention_v27 CHECK (
        retention_level IN ('STANDARD', 'EXTENDED', 'LEGAL_HOLD')
    ),
    ADD CONSTRAINT ck_audit_event_external_hash_v27 CHECK (
        external_operation_hash IS NULL OR external_operation_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_audit_event_provider_shape_v27 CHECK (
        (provider_binding_id IS NULL AND connection_id IS NULL
            AND external_operation_hash IS NULL)
        OR (provider_binding_id IS NOT NULL AND connection_id IS NOT NULL)
    );

CREATE INDEX ix_audit_event_team_keyset_v27
    ON crewscope.audit_event (
        organization_id, team_id, occurred_at DESC, event_id DESC
    );

CREATE INDEX ix_audit_event_team_category_keyset_v27
    ON crewscope.audit_event (
        organization_id, team_id, event_category, outcome,
        occurred_at DESC, event_id DESC
    );

CREATE INDEX ix_audit_event_initiator_keyset_v27
    ON crewscope.audit_event (
        organization_id, team_id, initiator_id, occurred_at DESC, event_id DESC
    ) WHERE initiator_id IS NOT NULL;

CREATE INDEX ix_audit_event_agent_keyset_v27
    ON crewscope.audit_event (
        organization_id, team_id, agent_principal_id, occurred_at DESC, event_id DESC
    ) WHERE agent_principal_id IS NOT NULL;

CREATE INDEX ix_audit_event_provider_keyset_v27
    ON crewscope.audit_event (
        organization_id, team_id, provider_binding_id, occurred_at DESC, event_id DESC
    ) WHERE provider_binding_id IS NOT NULL;

-- Deferred invariant checks permit one transaction to move ACTIVE, target and Pointer in the
-- fixed lock order while ensuring the committed registry always has exactly one pointed ACTIVE.
CREATE FUNCTION crewscope.require_projection_pointer_invariant_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    scoped_organization UUID;
    scoped_projection VARCHAR(180);
    active_count INTEGER;
    pointer_count INTEGER;
BEGIN
    scoped_organization := COALESCE(NEW.organization_id, OLD.organization_id);
    scoped_projection := COALESCE(NEW.projection_name, OLD.projection_name);
    SELECT COUNT(*) INTO active_count
    FROM crewscope.projection_generation generation_row
    WHERE generation_row.organization_id = scoped_organization
      AND generation_row.projection_name = scoped_projection
      AND generation_row.status = 'ACTIVE';
    SELECT COUNT(*) INTO pointer_count
    FROM crewscope.projection_pointer pointer_row
    JOIN crewscope.projection_generation generation_row
      ON generation_row.organization_id = pointer_row.organization_id
     AND generation_row.projection_name = pointer_row.projection_name
     AND generation_row.generation = pointer_row.active_generation
     AND generation_row.status = 'ACTIVE'
    WHERE pointer_row.organization_id = scoped_organization
      AND pointer_row.projection_name = scoped_projection;
    IF active_count <> 1 OR pointer_count <> 1 THEN
        RAISE EXCEPTION 'projection pointer must identify the single ACTIVE generation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_projection_generation_pointer_invariant_v27
    AFTER INSERT OR UPDATE OR DELETE ON crewscope.projection_generation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION crewscope.require_projection_pointer_invariant_v27();

CREATE CONSTRAINT TRIGGER trg_projection_pointer_generation_invariant_v27
    AFTER INSERT OR UPDATE OR DELETE ON crewscope.projection_pointer
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION crewscope.require_projection_pointer_invariant_v27();

CREATE FUNCTION crewscope.require_projection_write_lease_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM crewscope.projection_generation generation_row
        WHERE generation_row.organization_id = NEW.organization_id
          AND generation_row.projection_name = NEW.projection_name
          AND generation_row.generation = NEW.generation
          AND generation_row.fencing_token = NEW.fencing_token
          AND generation_row.status IN ('BUILDING', 'VALIDATING', 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'projection generation lease is stale or not writable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_projection_receipt_lease_v27
    BEFORE INSERT OR UPDATE ON crewscope.projection_consumer_receipt
    FOR EACH ROW EXECUTE FUNCTION crewscope.require_projection_write_lease_v27();

CREATE TRIGGER trg_projection_checkpoint_lease_v27
    BEFORE INSERT OR UPDATE ON crewscope.projection_generation_checkpoint
    FOR EACH ROW EXECUTE FUNCTION crewscope.require_projection_write_lease_v27();

CREATE TRIGGER trg_projection_dead_letter_lease_v27
    BEFORE INSERT ON crewscope.projection_dead_letter
    FOR EACH ROW EXECUTE FUNCTION crewscope.require_projection_write_lease_v27();

CREATE FUNCTION crewscope.guard_inbox_disposition_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    old_rank INTEGER;
    new_rank INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'InboxDisposition cannot be deleted' USING ERRCODE = '23514';
    END IF;
    old_rank := CASE OLD.status WHEN 'READ' THEN 1 WHEN 'ACTED' THEN 2 ELSE 3 END;
    new_rank := CASE NEW.status WHEN 'READ' THEN 1 WHEN 'ACTED' THEN 2 ELSE 3 END;
    IF ROW(NEW.organization_id, NEW.team_id, NEW.member_id, NEW.inbox_item_id,
           NEW.created_at, NEW.created_by_principal_id)
       IS DISTINCT FROM
       ROW(OLD.organization_id, OLD.team_id, OLD.member_id, OLD.inbox_item_id,
           OLD.created_at, OLD.created_by_principal_id)
       OR NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at
       OR new_rank <= old_rank THEN
        RAISE EXCEPTION 'invalid InboxDisposition transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_inbox_disposition_guard_v27
    BEFORE UPDATE OR DELETE ON crewscope.inbox_disposition
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_inbox_disposition_v27();

CREATE FUNCTION crewscope.guard_inbox_item_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF OLD.source_status <> 'OPEN' OR NEW.source_status <> 'CLOSED'
       OR ROW(NEW.organization_id, NEW.team_id, NEW.member_id, NEW.projection_name,
              NEW.generation, NEW.inbox_item_id, NEW.projection_schema_version,
              NEW.item_type, NEW.source_type, NEW.source_id, NEW.source_revision,
              NEW.priority, NEW.deadline, NEW.opened_at, NEW.created_at)
          IS DISTINCT FROM
          ROW(OLD.organization_id, OLD.team_id, OLD.member_id, OLD.projection_name,
              OLD.generation, OLD.inbox_item_id, OLD.projection_schema_version,
              OLD.item_type, OLD.source_type, OLD.source_id, OLD.source_revision,
              OLD.priority, OLD.deadline, OLD.opened_at, OLD.created_at) THEN
        RAISE EXCEPTION 'invalid Inbox source transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_inbox_item_guard_v27
    BEFORE UPDATE OR DELETE ON crewscope.inbox_item
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_inbox_item_v27();

CREATE FUNCTION crewscope.guard_projection_generation_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status NOT IN ('RETIRED', 'FAILED', 'CANCELLED') THEN
            RAISE EXCEPTION 'only terminal projection generations can be cleaned'
                USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF ROW(NEW.organization_id, NEW.projection_name, NEW.generation,
           NEW.definition_version, NEW.rebuild_job_id, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.organization_id, OLD.projection_name, OLD.generation,
           OLD.definition_version, OLD.rebuild_job_id, OLD.created_at)
       OR NEW.version <> OLD.version + 1
       OR NEW.fencing_token <> OLD.fencing_token + 1
       OR NEW.updated_at < OLD.updated_at
       OR NOT (
            (OLD.status = 'BUILDING' AND NEW.status IN (
                'BUILDING', 'VALIDATING', 'FAILED', 'CANCELLED'
            ))
            OR (OLD.status = 'VALIDATING' AND NEW.status IN (
                'VALIDATING', 'ACTIVE', 'FAILED', 'CANCELLED'
            ))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')
       ) THEN
        RAISE EXCEPTION 'invalid projection generation transition or fencing token'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_projection_generation_guard_v27
    BEFORE UPDATE OR DELETE ON crewscope.projection_generation
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_projection_generation_v27();

CREATE FUNCTION crewscope.guard_projection_pointer_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ProjectionPointer cannot be deleted directly' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.organization_id, NEW.projection_name)
       IS DISTINCT FROM ROW(OLD.organization_id, OLD.projection_name)
       OR NEW.active_generation = OLD.active_generation
       OR NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'invalid ProjectionPointer transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_projection_pointer_guard_v27
    BEFORE UPDATE OR DELETE ON crewscope.projection_pointer
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_projection_pointer_v27();

CREATE FUNCTION crewscope.guard_projection_rebuild_job_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN
            RAISE EXCEPTION 'only terminal rebuild jobs can be cleaned' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF ROW(NEW.id, NEW.organization_id, NEW.projection_name, NEW.definition_version,
           NEW.generation, NEW.retry_of, NEW.requested_by_principal_id, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.organization_id, OLD.projection_name, OLD.definition_version,
           OLD.generation, OLD.retry_of, OLD.requested_by_principal_id, OLD.created_at)
       OR NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at
       OR NOT (
            (OLD.status = 'BUILDING' AND NEW.status IN (
                'BUILDING', 'VALIDATING', 'FAILED', 'CANCELLED'
            ))
            OR (OLD.status = 'VALIDATING' AND NEW.status IN (
                'VALIDATING', 'COMPLETED', 'FAILED', 'CANCELLED'
            ))
       ) THEN
        RAISE EXCEPTION 'invalid ProjectionRebuildJob transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_projection_rebuild_job_guard_v27
    BEFORE UPDATE OR DELETE ON crewscope.projection_rebuild_job
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_projection_rebuild_job_v27();

CREATE FUNCTION crewscope.require_projection_validation_result_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    result_row crewscope.projection_validation_result%ROWTYPE;
    failed_count BIGINT;
    expected_pass BOOLEAN;
BEGIN
    SELECT * INTO result_row
    FROM crewscope.projection_validation_result
    WHERE id = COALESCE(
        (TO_JSONB(NEW) ->> 'validation_id')::UUID,
        (TO_JSONB(NEW) ->> 'id')::UUID,
        (TO_JSONB(OLD) ->> 'validation_id')::UUID,
        (TO_JSONB(OLD) ->> 'id')::UUID
    );
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT COUNT(*) INTO failed_count
    FROM crewscope.projection_validation_failed_partition
    WHERE validation_id = result_row.id;
    expected_pass := result_row.expected_row_count = result_row.actual_row_count
        AND result_row.expected_canonical_hash = result_row.actual_canonical_hash
        AND result_row.expected_gap_count = 0 AND result_row.actual_gap_count = 0
        AND failed_count = 0;
    IF result_row.passed IS DISTINCT FROM expected_pass THEN
        RAISE EXCEPTION 'projection validation result does not match its complete snapshot'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_projection_validation_result_integrity_v27
    AFTER INSERT ON crewscope.projection_validation_result
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION crewscope.require_projection_validation_result_v27();

CREATE CONSTRAINT TRIGGER trg_projection_validation_partition_integrity_v27
    AFTER INSERT OR DELETE ON crewscope.projection_validation_failed_partition
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION crewscope.require_projection_validation_result_v27();

CREATE FUNCTION crewscope.guard_projection_checkpoint_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF ROW(NEW.organization_id, NEW.projection_name, NEW.generation, NEW.partition_key,
           NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.organization_id, OLD.projection_name, OLD.generation, OLD.partition_key,
           OLD.created_at)
       OR NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'invalid generation checkpoint transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_projection_checkpoint_guard_v27
    BEFORE UPDATE OR DELETE ON crewscope.projection_generation_checkpoint
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_projection_checkpoint_v27();

CREATE UNIQUE INDEX ux_notification_template_published_v27
    ON crewscope.notification_template (template_id)
    WHERE status = 'PUBLISHED';

CREATE FUNCTION crewscope.guard_notification_template_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'NotificationTemplate cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'PUBLISHED' OR NEW.status <> 'RETIRED'
       OR ROW(NEW.template_id, NEW.template_version, NEW.server_template_key, NEW.created_at)
          IS DISTINCT FROM
          ROW(OLD.template_id, OLD.template_version, OLD.server_template_key, OLD.created_at) THEN
        RAISE EXCEPTION 'invalid NotificationTemplate transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_notification_template_guard_v27
    BEFORE UPDATE OR DELETE ON crewscope.notification_template
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_notification_template_v27();

CREATE TRIGGER trg_notification_template_variable_immutable_v27
    BEFORE UPDATE OR DELETE ON crewscope.notification_template_variable
    FOR EACH ROW EXECUTE FUNCTION crewscope.reject_v21_append_only_mutation();

CREATE FUNCTION crewscope.guard_notification_preference_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'NotificationPreference cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.organization_id, NEW.team_id, NEW.member_id, NEW.created_at,
           NEW.created_by_principal_id)
       IS DISTINCT FROM
       ROW(OLD.organization_id, OLD.team_id, OLD.member_id, OLD.created_at,
           OLD.created_by_principal_id)
       OR NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'invalid NotificationPreference transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_notification_preference_guard_v27
    BEFORE UPDATE OR DELETE ON crewscope.notification_preference
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_notification_preference_v27();

CREATE FUNCTION crewscope.guard_notification_action_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'NotificationPlannedAction cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'PLANNED' OR NEW.status <> 'INVALIDATED'
       OR NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at
       OR ROW(NEW.organization_id, NEW.team_id, NEW.recipient_member_id,
              NEW.projection_name, NEW.generation, NEW.action_id, NEW.intent_id,
              NEW.source_identity_hash, NEW.template_id, NEW.template_version,
              NEW.variable_hash, NEW.recipient_mapping_id,
              NEW.recipient_mapping_version, NEW.provider_binding_id,
              NEW.provider_binding_version, NEW.connection_id, NEW.connection_version,
              NEW.connection_grant_id, NEW.connection_grant_version,
              NEW.team_policy_id, NEW.team_policy_version, NEW.preference_version,
              NEW.deduplication_key, NEW.authorization_digest, NEW.not_before,
              NEW.valid_until, NEW.redelivery_of, NEW.action_digest, NEW.created_at)
          IS DISTINCT FROM
          ROW(OLD.organization_id, OLD.team_id, OLD.recipient_member_id,
              OLD.projection_name, OLD.generation, OLD.action_id, OLD.intent_id,
              OLD.source_identity_hash, OLD.template_id, OLD.template_version,
              OLD.variable_hash, OLD.recipient_mapping_id,
              OLD.recipient_mapping_version, OLD.provider_binding_id,
              OLD.provider_binding_version, OLD.connection_id, OLD.connection_version,
              OLD.connection_grant_id, OLD.connection_grant_version,
              OLD.team_policy_id, OLD.team_policy_version, OLD.preference_version,
              OLD.deduplication_key, OLD.authorization_digest, OLD.not_before,
              OLD.valid_until, OLD.redelivery_of, OLD.action_digest, OLD.created_at) THEN
        RAISE EXCEPTION 'invalid NotificationPlannedAction transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_notification_action_guard_v27
    BEFORE UPDATE OR DELETE ON crewscope.notification_planned_action
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_notification_action_v27();

CREATE FUNCTION crewscope.guard_notification_delivery_v27()
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
                'RETRY_WAIT', 'SUCCEEDED', 'FAILED_FINAL', 'INVALIDATED'
            ))
       ) THEN
        RAISE EXCEPTION 'invalid NotificationDelivery transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_notification_delivery_guard_v27
    BEFORE UPDATE OR DELETE ON crewscope.notification_delivery
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_notification_delivery_v27();

ALTER TABLE crewscope.notification_planned_action
    ADD CONSTRAINT fk_notification_action_redelivery_v27
        FOREIGN KEY (organization_id, redelivery_of)
        REFERENCES crewscope.notification_delivery (organization_id, delivery_id)
        ON DELETE RESTRICT;

CREATE FUNCTION crewscope.require_notification_terminal_receipt_v27()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    delivery_row crewscope.notification_delivery%ROWTYPE;
    receipt_result VARCHAR(24);
    expected_result VARCHAR(24);
BEGIN
    SELECT * INTO delivery_row
    FROM crewscope.notification_delivery
    WHERE organization_id = COALESCE(NEW.organization_id, OLD.organization_id)
      AND delivery_id = COALESCE(NEW.delivery_id, OLD.delivery_id);
    IF NOT FOUND OR delivery_row.receipt_id IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT result INTO receipt_result
    FROM crewscope.notification_receipt
    WHERE organization_id = delivery_row.organization_id
      AND receipt_id = delivery_row.receipt_id;
    expected_result := CASE delivery_row.status
        WHEN 'SUCCEEDED' THEN 'ACCEPTED'
        WHEN 'FAILED_FINAL' THEN 'FAILED_FINAL'
        WHEN 'INVALIDATED' THEN 'INVALIDATED'
        WHEN 'CANCELLED' THEN 'CANCELLED'
        ELSE NULL
    END;
    IF expected_result IS NULL OR receipt_result IS DISTINCT FROM expected_result THEN
        RAISE EXCEPTION 'notification terminal status and receipt result do not match'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_notification_delivery_receipt_integrity_v27
    AFTER INSERT OR UPDATE ON crewscope.notification_delivery
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION crewscope.require_notification_terminal_receipt_v27();

CREATE CONSTRAINT TRIGGER trg_notification_receipt_delivery_integrity_v27
    AFTER INSERT ON crewscope.notification_receipt
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION crewscope.require_notification_terminal_receipt_v27();

-- Immutable projection/history rows reject UPDATE; DELETE remains available for ordered retired
-- generation cleanup where the full FK graph has already been removed.
CREATE FUNCTION crewscope.reject_v27_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is immutable', TG_TABLE_NAME USING ERRCODE = '23514';
END;
$$;

DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'projection_definition', 'projection_validation_result',
        'projection_validation_failed_partition', 'projection_consumer_receipt',
        'projection_dead_letter', 'projection_command_receipt', 'activity_event',
        'activity_reference', 'notification_intent', 'notification_receipt',
        'notification_redelivery_receipt'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE ON crewscope.%I '
            || 'FOR EACH ROW EXECUTE FUNCTION crewscope.reject_v27_update()',
            'trg_' || table_name || '_immutable_v27', table_name
        );
    END LOOP;
END;
$$;

CREATE TRIGGER trg_audit_event_append_only_v27
    BEFORE UPDATE OR DELETE ON crewscope.audit_event
    FOR EACH ROW EXECUTE FUNCTION crewscope.reject_v21_append_only_mutation();
