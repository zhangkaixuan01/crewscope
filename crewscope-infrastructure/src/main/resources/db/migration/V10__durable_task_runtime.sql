-- M3 durable Task Runtime. All tenant-owned relations carry their complete scope so PostgreSQL,
-- rather than repository convention, rejects cross-organization and cross-workspace references.

CREATE TABLE crewscope.task_responsibility_snapshot (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_task_responsibility_snapshot_scope_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, work_item_id, id),
    CONSTRAINT fk_task_responsibility_snapshot_work_item
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id, work_item_id)
        REFERENCES crewscope.work_item (
            organization_id, team_id, workspace_id, project_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_responsibility_snapshot_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_responsibility_snapshot_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_task_responsibility_snapshot_hash
        CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_task_responsibility_snapshot_version CHECK (version >= 0),
    CONSTRAINT ck_task_responsibility_snapshot_timestamps CHECK (
        updated_at >= created_at AND captured_at <= created_at
    )
);

CREATE TABLE crewscope.task_responsibility_snapshot_entry (
    snapshot_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    assignment_version BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    principal_id UUID NOT NULL,
    principal_type VARCHAR(32) NOT NULL,
    member_id UUID,
    assigned_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (snapshot_id, assignment_id),
    CONSTRAINT fk_task_responsibility_entry_snapshot
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id, work_item_id, snapshot_id
        ) REFERENCES crewscope.task_responsibility_snapshot (
            organization_id, team_id, workspace_id, project_id, work_item_id, id
        ) ON DELETE CASCADE,
    CONSTRAINT fk_task_responsibility_entry_assignment
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id, work_item_id, assignment_id
        ) REFERENCES crewscope.responsibility_assignment (
            organization_id, team_id, workspace_id, project_id, work_item_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_responsibility_entry_principal
        FOREIGN KEY (organization_id, principal_id, principal_type)
        REFERENCES crewscope.principal (organization_id, id, principal_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_task_responsibility_entry_member
        FOREIGN KEY (organization_id, team_id, member_id, principal_id)
        REFERENCES crewscope.team_member (
            organization_id, team_id, id, user_principal_id
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_task_responsibility_entry_version CHECK (assignment_version >= 0),
    CONSTRAINT ck_task_responsibility_entry_role
        CHECK (role IN ('OWNER', 'EXECUTOR', 'REVIEWER')),
    CONSTRAINT ck_task_responsibility_entry_member_shape CHECK (
        (principal_type = 'USER' AND member_id IS NOT NULL)
        OR (principal_type <> 'USER' AND member_id IS NULL)
    ),
    CONSTRAINT ck_task_responsibility_entry_role_actor CHECK (
        (role = 'OWNER' AND principal_type = 'USER')
        OR (role = 'EXECUTOR' AND principal_type IN (
            'USER', 'PERSONAL_AGENT', 'TEAM_AGENT', 'SPECIALIST_AGENT'
        ))
        OR (role = 'REVIEWER' AND principal_type IN ('USER', 'SPECIALIST_AGENT'))
    ),
    CONSTRAINT ck_task_responsibility_entry_times CHECK (accepted_at >= assigned_at)
);

CREATE TABLE crewscope.task (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_work_item_version BIGINT NOT NULL,
    source_conversation_id UUID,
    source_input_type VARCHAR(32),
    source_input_id UUID,
    source_input_version BIGINT,
    responsibility_snapshot_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_execution_id UUID,
    cancelled_by_principal_id UUID,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(2000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_task_scope_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, id),
    CONSTRAINT uk_task_scope_work_item_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, work_item_id, id),
    CONSTRAINT fk_task_work_item
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id, work_item_id)
        REFERENCES crewscope.work_item (
            organization_id, team_id, workspace_id, project_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_source_conversation
        FOREIGN KEY (organization_id, team_id, workspace_id, source_conversation_id)
        REFERENCES crewscope.conversation (organization_id, team_id, workspace_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_task_responsibility_snapshot
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            work_item_id, responsibility_snapshot_id
        ) REFERENCES crewscope.task_responsibility_snapshot (
            organization_id, team_id, workspace_id, project_id, work_item_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_cancelled_by
        FOREIGN KEY (organization_id, cancelled_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_task_source_version CHECK (source_work_item_version >= 0),
    CONSTRAINT ck_task_source_shape CHECK (
        (source_type = 'WORK_ITEM'
            AND source_conversation_id IS NULL
            AND source_input_type IS NULL
            AND source_input_id IS NULL
            AND source_input_version IS NULL)
        OR (source_type = 'CONVERSATION'
            AND source_conversation_id IS NOT NULL
            AND source_input_type IN ('MESSAGE', 'TASK_INTENT')
            AND source_input_id IS NOT NULL
            AND source_input_version > 0)
    ),
    CONSTRAINT ck_task_status CHECK (
        status IN ('CREATED', 'ACTIVE', 'WAITING', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_task_execution_shape CHECK (
        (status = 'CREATED' AND current_execution_id IS NULL)
        OR status = 'CANCELLED'
        OR (status IN ('ACTIVE', 'WAITING', 'COMPLETED', 'FAILED')
            AND current_execution_id IS NOT NULL)
    ),
    CONSTRAINT ck_task_cancellation_shape CHECK (
        (status = 'CANCELLED'
            AND cancelled_by_principal_id IS NOT NULL
            AND cancelled_at IS NOT NULL
            AND BTRIM(cancellation_reason) <> '')
        OR (status <> 'CANCELLED'
            AND cancelled_by_principal_id IS NULL
            AND cancelled_at IS NULL
            AND cancellation_reason IS NULL)
    ),
    CONSTRAINT ck_task_version CHECK (version >= 0),
    CONSTRAINT ck_task_timestamps CHECK (
        updated_at >= created_at AND (cancelled_at IS NULL OR cancelled_at >= created_at)
    )
);

CREATE INDEX ix_task_work_item_updated
    ON crewscope.task (
        organization_id, team_id, workspace_id, project_id,
        work_item_id, updated_at DESC, id DESC
    );

CREATE TABLE crewscope.conversation_task_link (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    task_id UUID NOT NULL,
    origin VARCHAR(32) NOT NULL,
    created_by_principal_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_conversation_task_link_scope_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, id),
    CONSTRAINT uk_conversation_task_pair UNIQUE (conversation_id, task_id),
    CONSTRAINT fk_conversation_task_link_conversation
        FOREIGN KEY (organization_id, team_id, workspace_id, conversation_id)
        REFERENCES crewscope.conversation (organization_id, team_id, workspace_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_task_link_task
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id, work_item_id, task_id
        ) REFERENCES crewscope.task (
            organization_id, team_id, workspace_id, project_id, work_item_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_task_link_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_task_link_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_conversation_task_link_origin CHECK (origin IN ('SOURCE', 'MANUAL')),
    CONSTRAINT ck_conversation_task_link_creator CHECK (
        created_by_principal_id = updated_by_principal_id
    ),
    CONSTRAINT ck_conversation_task_link_version CHECK (version >= 0),
    CONSTRAINT ck_conversation_task_link_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_conversation_task_link_task
    ON crewscope.conversation_task_link (
        organization_id, team_id, workspace_id, project_id, task_id
    );

-- ExecutionPrincipal stores the immutable Assignment ID/version selected from the Task snapshot.
-- This shorter alternate key still proves Organization/Team/Workspace/Project ownership.
ALTER TABLE crewscope.responsibility_assignment
    ADD CONSTRAINT uk_responsibility_assignment_project_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, id);

CREATE TABLE crewscope.task_execution (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    max_attempts INTEGER NOT NULL,
    parent_execution_id UUID,
    priority INTEGER NOT NULL,
    not_before TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    waiting_reason VARCHAR(32),
    waiting_since TIMESTAMPTZ,
    control_request_type VARCHAR(32),
    control_requested_by_principal_id UUID,
    control_requested_at TIMESTAMPTZ,
    control_request_reason VARCHAR(2000),
    terminal_decided_by_principal_id UUID,
    terminal_decided_at TIMESTAMPTZ,
    terminal_failure_class VARCHAR(32),
    terminal_failure_code VARCHAR(100),
    execution_principal_id UUID,
    execution_assignment_id UUID,
    execution_assignment_version BIGINT,
    responsibility_snapshot_hash CHAR(64),
    current_policy_snapshot_id UUID,
    current_policy_snapshot_hash CHAR(64),
    current_safety_overlay_id UUID,
    current_safety_overlay_version BIGINT,
    current_safety_overlay_hash CHAR(64),
    current_plan_version_id UUID,
    current_plan_version_hash CHAR(64),
    last_fencing_token BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_task_execution_scope_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, task_id, id),
    CONSTRAINT uk_task_execution_attempt UNIQUE (task_id, attempt),
    CONSTRAINT fk_task_execution_task
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id, task_id)
        REFERENCES crewscope.task (organization_id, team_id, workspace_id, project_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_task_execution_parent
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id, task_id, parent_execution_id
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_execution_control_actor
        FOREIGN KEY (organization_id, control_requested_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_execution_terminal_actor
        FOREIGN KEY (organization_id, terminal_decided_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_execution_principal
        FOREIGN KEY (organization_id, execution_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_execution_assignment
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            execution_assignment_id
        ) REFERENCES crewscope.responsibility_assignment (
            organization_id, team_id, workspace_id, project_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_execution_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_execution_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_task_execution_attempt CHECK (
        attempt >= 1 AND max_attempts >= attempt AND max_attempts <= 100
    ),
    CONSTRAINT ck_task_execution_parent_shape CHECK (
        (attempt = 1 AND parent_execution_id IS NULL)
        OR (attempt > 1 AND parent_execution_id IS NOT NULL)
    ),
    CONSTRAINT ck_task_execution_priority CHECK (priority BETWEEN 0 AND 100),
    CONSTRAINT ck_task_execution_status CHECK (status IN (
        'CREATED', 'READY', 'CLAIMED', 'PREPARING', 'RUNNING', 'WAITING',
        'PAUSE_REQUESTED', 'PAUSED', 'RECOVERING', 'CANCEL_REQUESTED',
        'MANUAL_TAKEOVER', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_task_execution_waiting_shape CHECK (
        (status = 'WAITING' AND waiting_reason IN (
            'RUNTIME', 'COLLABORATION', 'REVIEW', 'CONFIRMATION', 'USER_INPUT',
            'EXTERNAL_EXECUTION', 'EVENT', 'MANUAL'
        ) AND waiting_since IS NOT NULL)
        OR (status <> 'WAITING' AND waiting_reason IS NULL AND waiting_since IS NULL)
    ),
    CONSTRAINT ck_task_execution_control_shape CHECK (
        (status IN ('PAUSE_REQUESTED', 'PAUSED') AND control_request_type = 'PAUSE'
            AND control_requested_by_principal_id IS NOT NULL
            AND control_requested_at IS NOT NULL AND BTRIM(control_request_reason) <> '')
        OR (status IN ('CANCEL_REQUESTED', 'CANCELLED') AND control_request_type = 'CANCEL'
            AND control_requested_by_principal_id IS NOT NULL
            AND control_requested_at IS NOT NULL AND BTRIM(control_request_reason) <> '')
        OR (status NOT IN ('PAUSE_REQUESTED', 'PAUSED', 'CANCEL_REQUESTED', 'CANCELLED')
            AND control_request_type IS NULL
            AND control_requested_by_principal_id IS NULL
            AND control_requested_at IS NULL
            AND control_request_reason IS NULL)
    ),
    CONSTRAINT ck_task_execution_terminal_shape CHECK (
        (status IN ('COMPLETED', 'FAILED', 'CANCELLED')
            AND terminal_decided_by_principal_id IS NOT NULL
            AND terminal_decided_at IS NOT NULL
            AND ((status = 'FAILED'
                AND terminal_failure_class IN (
                    'TRANSIENT', 'RATE_LIMITED', 'TIMEOUT', 'RUNTIME_UNAVAILABLE',
                    'MODEL_UNAVAILABLE', 'TOOL_UNAVAILABLE', 'RESOURCE_EXHAUSTED',
                    'RECOVERY_INTERRUPTED', 'VALIDATION', 'AUTHENTICATION',
                    'AUTHORIZATION', 'POLICY_VIOLATION', 'CAPABILITY_UNSUPPORTED',
                    'NOT_FOUND', 'CONFLICT', 'INTERNAL'
                )
                AND terminal_failure_code ~ '^[A-Z][A-Z0-9_]{0,99}$')
                OR (status <> 'FAILED'
                    AND terminal_failure_class IS NULL
                    AND terminal_failure_code IS NULL)))
        OR (status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
            AND terminal_decided_by_principal_id IS NULL
            AND terminal_decided_at IS NULL
            AND terminal_failure_class IS NULL
            AND terminal_failure_code IS NULL)
    ),
    CONSTRAINT ck_task_execution_planning_shape CHECK (
        (execution_principal_id IS NULL
            AND execution_assignment_id IS NULL
            AND execution_assignment_version IS NULL
            AND responsibility_snapshot_hash IS NULL
            AND current_policy_snapshot_id IS NULL
            AND current_policy_snapshot_hash IS NULL
            AND current_safety_overlay_id IS NULL
            AND current_safety_overlay_version IS NULL
            AND current_safety_overlay_hash IS NULL
            AND current_plan_version_id IS NULL
            AND current_plan_version_hash IS NULL)
        OR (execution_principal_id IS NOT NULL
            AND execution_assignment_id IS NOT NULL
            AND execution_assignment_version >= 0
            AND responsibility_snapshot_hash ~ '^[0-9a-f]{64}$'
            AND current_policy_snapshot_id IS NOT NULL
            AND current_policy_snapshot_hash ~ '^[0-9a-f]{64}$'
            AND current_safety_overlay_id IS NOT NULL
            AND current_safety_overlay_version > 0
            AND current_safety_overlay_hash ~ '^[0-9a-f]{64}$'
            AND ((current_plan_version_id IS NULL AND current_plan_version_hash IS NULL)
                OR (current_plan_version_id IS NOT NULL
                    AND current_plan_version_hash ~ '^[0-9a-f]{64}$')))
    ),
    CONSTRAINT ck_task_execution_fencing CHECK (
        (status IN ('CLAIMED', 'PREPARING', 'RUNNING', 'PAUSE_REQUESTED', 'RECOVERING')
            AND last_fencing_token IS NOT NULL
            AND last_fencing_token > 0)
        OR (status NOT IN ('CLAIMED', 'PREPARING', 'RUNNING', 'PAUSE_REQUESTED', 'RECOVERING')
            AND (last_fencing_token IS NULL OR last_fencing_token > 0))
    ),
    CONSTRAINT ck_task_execution_version CHECK (version >= 0),
    CONSTRAINT ck_task_execution_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_task_execution_ready_queue
    ON crewscope.task_execution (
        organization_id, status, priority DESC, not_before, created_at, id
    ) WHERE status = 'READY';

CREATE INDEX ix_task_execution_task_history
    ON crewscope.task_execution (task_id, attempt DESC);

CREATE UNIQUE INDEX ux_task_execution_active_task
    ON crewscope.task_execution (task_id)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED');

ALTER TABLE crewscope.task
    ADD CONSTRAINT fk_task_current_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id, id, current_execution_id
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id
        ) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

-- The Task pointer is the effective-attempt verdict. A defensive partial index also prevents one
-- TaskExecution row from being selected by multiple Task rows.
CREATE UNIQUE INDEX ux_task_current_execution
    ON crewscope.task (current_execution_id)
    WHERE current_execution_id IS NOT NULL;

CREATE TABLE crewscope.policy_snapshot (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    parent_snapshot_id UUID,
    change_reason VARCHAR(64) NOT NULL,
    execution_principal_id UUID NOT NULL,
    execution_assignment_id UUID NOT NULL,
    execution_assignment_version BIGINT NOT NULL,
    responsibility_snapshot_hash CHAR(64) NOT NULL,
    policy_pack_id UUID NOT NULL,
    policy_pack_version BIGINT NOT NULL,
    agent_profile_id UUID NOT NULL,
    agent_profile_version BIGINT NOT NULL,
    capabilities JSONB NOT NULL,
    allowed_tools JSONB NOT NULL,
    provider_binding_ids JSONB NOT NULL,
    max_tokens BIGINT NOT NULL,
    max_model_calls INTEGER NOT NULL,
    max_tool_calls INTEGER NOT NULL,
    max_duration_seconds BIGINT NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_policy_snapshot_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_policy_snapshot_revision UNIQUE (task_execution_id, revision),
    CONSTRAINT fk_policy_snapshot_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id, task_id, task_execution_id
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_policy_snapshot_parent
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, parent_snapshot_id
        ) REFERENCES crewscope.policy_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_policy_snapshot_principal
        FOREIGN KEY (organization_id, execution_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_policy_snapshot_assignment
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id, execution_assignment_id
        ) REFERENCES crewscope.responsibility_assignment (
            organization_id, team_id, workspace_id, project_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_policy_snapshot_profile
        FOREIGN KEY (organization_id, team_id, agent_profile_id)
        REFERENCES crewscope.agent_profile (organization_id, team_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_policy_snapshot_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_policy_snapshot_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_policy_snapshot_revision CHECK (revision > 0),
    CONSTRAINT ck_policy_snapshot_parent_shape CHECK (
        (revision = 1 AND parent_snapshot_id IS NULL AND change_reason = 'TASK_CREATED')
        OR (revision > 1 AND parent_snapshot_id IS NOT NULL
            AND change_reason IN (
                'PLAN_REQUIREMENTS_CHANGED', 'EXECUTOR_CHANGED',
                'PROVIDER_BINDING_CHANGED', 'POLICY_PACK_CHANGED',
                'RUNTIME_CHANGED', 'MANUAL_REAUTHORIZATION'
            ))
    ),
    CONSTRAINT ck_policy_snapshot_versions CHECK (
        execution_assignment_version >= 0
        AND policy_pack_version >= 0
        AND agent_profile_version >= 0
        AND version >= 0
    ),
    CONSTRAINT ck_policy_snapshot_collections CHECK (
        JSONB_TYPEOF(capabilities) = 'array'
        AND JSONB_ARRAY_LENGTH(capabilities) <= 200
        AND JSONB_TYPEOF(allowed_tools) = 'array'
        AND JSONB_ARRAY_LENGTH(allowed_tools) <= 200
        AND JSONB_TYPEOF(provider_binding_ids) = 'array'
        AND JSONB_ARRAY_LENGTH(provider_binding_ids) <= 200
    ),
    CONSTRAINT ck_policy_snapshot_budget CHECK (
        max_tokens > 0 AND max_model_calls > 0
        AND max_tool_calls > 0 AND max_duration_seconds > 0
    ),
    CONSTRAINT ck_policy_snapshot_hashes CHECK (
        responsibility_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND snapshot_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_policy_snapshot_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE crewscope.safety_enforcement_overlay (
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    overlay_version BIGINT NOT NULL,
    parent_overlay_hash CHAR(64),
    restrictions JSONB NOT NULL,
    disabled_capabilities JSONB NOT NULL,
    disabled_tools JSONB NOT NULL,
    overlay_hash CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    PRIMARY KEY (id, overlay_version),
    CONSTRAINT uk_safety_overlay_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, overlay_version
        ),
    CONSTRAINT uk_safety_overlay_version UNIQUE (task_execution_id, overlay_version),
    CONSTRAINT uk_safety_overlay_hash
        UNIQUE (task_execution_id, id, overlay_hash),
    CONSTRAINT fk_safety_overlay_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id, task_id, task_execution_id
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_safety_overlay_parent
        FOREIGN KEY (task_execution_id, id, parent_overlay_hash)
        REFERENCES crewscope.safety_enforcement_overlay (
            task_execution_id, id, overlay_hash
        )
        ON DELETE RESTRICT,
    CONSTRAINT fk_safety_overlay_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_safety_overlay_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_safety_overlay_parent_shape CHECK (
        (overlay_version = 1 AND parent_overlay_hash IS NULL)
        OR (overlay_version > 1 AND parent_overlay_hash ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_safety_overlay_collections CHECK (
        JSONB_TYPEOF(restrictions) = 'array'
        AND JSONB_TYPEOF(disabled_capabilities) = 'array'
        AND JSONB_TYPEOF(disabled_tools) = 'array'
    ),
    CONSTRAINT ck_safety_overlay_hash CHECK (overlay_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_safety_overlay_version CHECK (version >= 0),
    CONSTRAINT ck_safety_overlay_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE crewscope.plan_version (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    parent_version_id UUID,
    change_reason VARCHAR(64) NOT NULL,
    policy_snapshot_id UUID NOT NULL,
    policy_snapshot_hash CHAR(64) NOT NULL,
    safety_overlay_id UUID NOT NULL,
    safety_overlay_version BIGINT NOT NULL,
    safety_overlay_hash CHAR(64) NOT NULL,
    execution_principal_id UUID NOT NULL,
    execution_assignment_id UUID NOT NULL,
    execution_assignment_version BIGINT NOT NULL,
    responsibility_snapshot_hash CHAR(64) NOT NULL,
    markdown TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    version_hash CHAR(64) NOT NULL,
    published_by_principal_id UUID NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_plan_version_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_plan_version_execution_id UNIQUE (task_execution_id, id),
    CONSTRAINT uk_plan_version_revision UNIQUE (task_execution_id, revision),
    CONSTRAINT fk_plan_version_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id, task_id, task_execution_id
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_version_parent
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, parent_version_id
        ) REFERENCES crewscope.plan_version (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_version_policy
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, policy_snapshot_id
        ) REFERENCES crewscope.policy_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_version_safety_overlay
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, safety_overlay_id, safety_overlay_version
        ) REFERENCES crewscope.safety_enforcement_overlay (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, overlay_version
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_version_execution_principal
        FOREIGN KEY (organization_id, execution_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_version_published_by
        FOREIGN KEY (organization_id, published_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_version_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_version_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_plan_version_revision CHECK (revision > 0),
    CONSTRAINT ck_plan_version_parent_shape CHECK (
        (revision = 1 AND parent_version_id IS NULL AND change_reason = 'INITIAL_PLAN')
        OR (revision > 1 AND parent_version_id IS NOT NULL
            AND change_reason IN (
                'REQUIREMENTS_CHANGED', 'POLICY_CHANGED', 'RECOVERY_REPLAN',
                'REVIEW_FEEDBACK', 'MANUAL_REVISION'
            ))
    ),
    CONSTRAINT ck_plan_version_versions CHECK (
        safety_overlay_version > 0
        AND execution_assignment_version >= 0
        AND version >= 0
    ),
    CONSTRAINT ck_plan_version_content CHECK (BTRIM(markdown) <> ''),
    CONSTRAINT ck_plan_version_hashes CHECK (
        policy_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND safety_overlay_hash ~ '^[0-9a-f]{64}$'
        AND responsibility_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND content_hash ~ '^[0-9a-f]{64}$'
        AND version_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_plan_version_audit CHECK (
        published_by_principal_id = created_by_principal_id
        AND published_at = created_at
    ),
    CONSTRAINT ck_plan_version_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE crewscope.plan_step (
    plan_version_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    step_key VARCHAR(64) NOT NULL,
    sequence INTEGER NOT NULL,
    title VARCHAR(300) NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    dependency_keys JSONB NOT NULL,
    required_capabilities JSONB NOT NULL,
    required_tools JSONB NOT NULL,
    critical BOOLEAN NOT NULL,
    PRIMARY KEY (plan_version_id, step_key),
    CONSTRAINT uk_plan_step_sequence UNIQUE (plan_version_id, sequence),
    CONSTRAINT fk_plan_step_version
        FOREIGN KEY (task_execution_id, plan_version_id)
        REFERENCES crewscope.plan_version (task_execution_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_plan_step_key CHECK (step_key ~ '^[a-z][a-z0-9-]{0,63}$'),
    CONSTRAINT ck_plan_step_sequence CHECK (sequence > 0),
    CONSTRAINT ck_plan_step_title CHECK (BTRIM(title) <> ''),
    CONSTRAINT ck_plan_step_type CHECK (
        step_type IN ('ANALYSIS', 'IMPLEMENTATION', 'VALIDATION', 'REVIEW', 'DELIVERY')
    ),
    CONSTRAINT ck_plan_step_collections CHECK (
        JSONB_TYPEOF(dependency_keys) = 'array'
        AND JSONB_TYPEOF(required_capabilities) = 'array'
        AND JSONB_TYPEOF(required_tools) = 'array'
    )
);

CREATE TABLE crewscope.plan_todo_summary (
    plan_version_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    sequence INTEGER NOT NULL,
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    priority VARCHAR(20),
    plan_step_key VARCHAR(64),
    PRIMARY KEY (plan_version_id, sequence),
    CONSTRAINT fk_plan_todo_version
        FOREIGN KEY (task_execution_id, plan_version_id)
        REFERENCES crewscope.plan_version (task_execution_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_todo_step
        FOREIGN KEY (plan_version_id, plan_step_key)
        REFERENCES crewscope.plan_step (plan_version_id, step_key) ON DELETE RESTRICT,
    CONSTRAINT ck_plan_todo_sequence CHECK (sequence > 0),
    CONSTRAINT ck_plan_todo_content CHECK (BTRIM(content) <> ''),
    CONSTRAINT ck_plan_todo_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_plan_todo_priority CHECK (priority IS NULL OR BTRIM(priority) <> '')
);

-- Mutable pointers are installed after immutable version tables exist. Composite references ensure
-- a current Plan/Policy/Safety value cannot be borrowed from another execution.
ALTER TABLE crewscope.task_execution
    ADD CONSTRAINT fk_task_execution_current_policy
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, id, current_policy_snapshot_id
        ) REFERENCES crewscope.policy_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_task_execution_current_safety_overlay
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, id, current_safety_overlay_id, current_safety_overlay_version
        ) REFERENCES crewscope.safety_enforcement_overlay (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, overlay_version
        ) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_task_execution_current_plan
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, id, current_plan_version_id
        ) REFERENCES crewscope.plan_version (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE crewscope.step_execution (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    plan_version_id UUID NOT NULL,
    plan_version_hash CHAR(64) NOT NULL,
    plan_step_key VARCHAR(64) NOT NULL,
    sequence INTEGER NOT NULL,
    critical BOOLEAN NOT NULL,
    execution_principal_id UUID NOT NULL,
    execution_assignment_id UUID NOT NULL,
    execution_assignment_version BIGINT NOT NULL,
    responsibility_snapshot_hash CHAR(64) NOT NULL,
    policy_snapshot_id UUID NOT NULL,
    policy_snapshot_hash CHAR(64) NOT NULL,
    safety_overlay_id UUID NOT NULL,
    safety_overlay_version BIGINT NOT NULL,
    safety_overlay_hash CHAR(64) NOT NULL,
    run_attempt INTEGER NOT NULL,
    max_run_attempts INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    wait_reason VARCHAR(32),
    checkpoint_sequence BIGINT,
    checkpoint_code VARCHAR(64),
    checkpoint_payload_hash CHAR(64),
    checkpoint_recorded_by_principal_id UUID,
    checkpoint_recorded_at TIMESTAMPTZ,
    failure_class VARCHAR(32),
    failure_code VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_step_execution_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_step_execution_sequence UNIQUE (task_execution_id, sequence),
    CONSTRAINT uk_step_execution_plan_key
        UNIQUE (task_execution_id, plan_version_id, plan_step_key),
    CONSTRAINT fk_step_execution_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id, task_id, task_execution_id
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_step_execution_plan_step
        FOREIGN KEY (plan_version_id, plan_step_key)
        REFERENCES crewscope.plan_step (plan_version_id, step_key) ON DELETE RESTRICT,
    CONSTRAINT fk_step_execution_policy
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, policy_snapshot_id
        ) REFERENCES crewscope.policy_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_step_execution_safety_overlay
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, safety_overlay_id, safety_overlay_version
        ) REFERENCES crewscope.safety_enforcement_overlay (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, overlay_version
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_step_execution_principal
        FOREIGN KEY (organization_id, execution_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_step_execution_checkpoint_actor
        FOREIGN KEY (organization_id, checkpoint_recorded_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_step_execution_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_step_execution_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_step_execution_sequence CHECK (sequence > 0),
    CONSTRAINT ck_step_execution_attempt CHECK (
        run_attempt >= 1 AND max_run_attempts >= run_attempt AND max_run_attempts <= 100
    ),
    CONSTRAINT ck_step_execution_status CHECK (status IN (
        'PENDING', 'READY', 'RUNNING', 'WAITING', 'SUCCEEDED',
        'FAILED_RETRYABLE', 'FAILED_FINAL', 'SKIPPED', 'CANCELLED'
    )),
    CONSTRAINT ck_step_execution_wait_shape CHECK (
        (status = 'WAITING' AND wait_reason IN (
            'AGENT_INTERRUPT', 'COLLABORATION', 'REVIEW', 'HANDOFF', 'TAKEOVER',
            'CONFIRMATION', 'EXTERNAL_EXECUTION', 'EVENT', 'USER_INPUT', 'MANUAL'
        )) OR (status <> 'WAITING' AND wait_reason IS NULL)
    ),
    CONSTRAINT ck_step_execution_checkpoint_shape CHECK (
        (checkpoint_sequence IS NULL AND checkpoint_code IS NULL
            AND checkpoint_payload_hash IS NULL
            AND checkpoint_recorded_by_principal_id IS NULL
            AND checkpoint_recorded_at IS NULL)
        OR (checkpoint_sequence > 0
            AND checkpoint_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
            AND checkpoint_payload_hash ~ '^[0-9a-f]{64}$'
            AND checkpoint_recorded_by_principal_id IS NOT NULL
            AND checkpoint_recorded_at IS NOT NULL)
    ),
    CONSTRAINT ck_step_execution_failure_shape CHECK (
        (status IN ('FAILED_RETRYABLE', 'FAILED_FINAL')
            AND failure_class IN (
                'TRANSIENT', 'RATE_LIMITED', 'TIMEOUT', 'RUNTIME_UNAVAILABLE',
                'MODEL_UNAVAILABLE', 'TOOL_UNAVAILABLE', 'RESOURCE_EXHAUSTED',
                'RECOVERY_INTERRUPTED', 'VALIDATION', 'AUTHENTICATION',
                'AUTHORIZATION', 'POLICY_VIOLATION', 'CAPABILITY_UNSUPPORTED',
                'NOT_FOUND', 'CONFLICT', 'INTERNAL'
            )
            AND failure_code ~ '^[A-Z][A-Z0-9_]{0,99}$')
        OR (status NOT IN ('FAILED_RETRYABLE', 'FAILED_FINAL')
            AND failure_class IS NULL AND failure_code IS NULL)
    ),
    CONSTRAINT ck_step_execution_versions CHECK (
        execution_assignment_version >= 0 AND safety_overlay_version > 0 AND version >= 0
    ),
    CONSTRAINT ck_step_execution_hashes CHECK (
        plan_version_hash ~ '^[0-9a-f]{64}$'
        AND responsibility_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND policy_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND safety_overlay_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_step_execution_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_step_execution_ready
    ON crewscope.step_execution (task_execution_id, status, sequence)
    WHERE status = 'READY';

CREATE TABLE crewscope.execution_runtime (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    runtime_environment VARCHAR(64) NOT NULL,
    runtime_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    implementation_version VARCHAR(64) NOT NULL,
    capabilities JSONB NOT NULL,
    languages JSONB NOT NULL,
    build_systems JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_execution_runtime_scope_id
        UNIQUE (organization_id, runtime_environment, id),
    CONSTRAINT uk_execution_runtime_stable_key
        UNIQUE (organization_id, runtime_environment, runtime_key),
    CONSTRAINT fk_execution_runtime_organization
        FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_execution_runtime_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_execution_runtime_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_execution_runtime_environment CHECK (
        runtime_environment ~ '^[a-z][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT ck_execution_runtime_key CHECK (
        runtime_key ~ '^[a-z][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT ck_execution_runtime_text CHECK (
        BTRIM(display_name) <> '' AND BTRIM(implementation_version) <> ''
    ),
    CONSTRAINT ck_execution_runtime_capabilities CHECK (
        JSONB_TYPEOF(capabilities) = 'array'
        AND JSONB_ARRAY_LENGTH(capabilities) <= 100
        AND JSONB_TYPEOF(languages) = 'array'
        AND JSONB_ARRAY_LENGTH(languages) <= 100
        AND JSONB_TYPEOF(build_systems) = 'array'
        AND JSONB_ARRAY_LENGTH(build_systems) <= 100
    ),
    CONSTRAINT ck_execution_runtime_status CHECK (
        status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')
    ),
    CONSTRAINT ck_execution_runtime_version CHECK (version >= 0),
    CONSTRAINT ck_execution_runtime_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE crewscope.runtime_worker (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    runtime_environment VARCHAR(64) NOT NULL,
    runtime_id UUID NOT NULL,
    stable_key VARCHAR(128) NOT NULL,
    runtime_profile VARCHAR(16) NOT NULL,
    capabilities JSONB NOT NULL,
    languages JSONB NOT NULL,
    build_systems JSONB NOT NULL,
    max_concurrent_executions INTEGER NOT NULL,
    active_executions INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_heartbeat_at TIMESTAMPTZ NOT NULL,
    heartbeat_sequence BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_runtime_worker_scope_id
        UNIQUE (organization_id, runtime_environment, runtime_id, id),
    CONSTRAINT uk_runtime_worker_stable_key UNIQUE (runtime_id, stable_key),
    CONSTRAINT fk_runtime_worker_runtime
        FOREIGN KEY (organization_id, runtime_environment, runtime_id)
        REFERENCES crewscope.execution_runtime (organization_id, runtime_environment, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_runtime_worker_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_runtime_worker_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_runtime_worker_stable_key CHECK (
        stable_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
    ),
    CONSTRAINT ck_runtime_worker_profile CHECK (runtime_profile IN ('ALL', 'WORKER')),
    CONSTRAINT ck_runtime_worker_capabilities CHECK (
        JSONB_TYPEOF(capabilities) = 'array'
        AND JSONB_ARRAY_LENGTH(capabilities) <= 100
        AND JSONB_TYPEOF(languages) = 'array'
        AND JSONB_ARRAY_LENGTH(languages) <= 100
        AND JSONB_TYPEOF(build_systems) = 'array'
        AND JSONB_ARRAY_LENGTH(build_systems) <= 100
    ),
    CONSTRAINT ck_runtime_worker_capacity CHECK (
        max_concurrent_executions BETWEEN 1 AND 10000
        AND active_executions BETWEEN 0 AND max_concurrent_executions
    ),
    CONSTRAINT ck_runtime_worker_status CHECK (
        status IN ('REGISTERED', 'ACTIVE', 'DRAINING', 'DISABLED')
    ),
    CONSTRAINT ck_runtime_worker_heartbeat CHECK (heartbeat_sequence >= 0),
    CONSTRAINT ck_runtime_worker_version CHECK (version >= 0),
    CONSTRAINT ck_runtime_worker_timestamps CHECK (
        updated_at >= created_at AND last_heartbeat_at >= created_at
    )
);

CREATE INDEX ix_runtime_worker_routing
    ON crewscope.runtime_worker (
        organization_id, runtime_environment, runtime_id,
        status, last_heartbeat_at DESC, active_executions
    );

CREATE TABLE crewscope.execution_lease (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    runtime_environment VARCHAR(64) NOT NULL,
    runtime_id UUID NOT NULL,
    worker_id UUID NOT NULL,
    claim_token_hash CHAR(64) NOT NULL,
    fencing_token BIGINT NOT NULL,
    phase VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL,
    last_heartbeat_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    release_reason VARCHAR(32),
    lease_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_execution_lease_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_execution_lease_fencing UNIQUE (task_execution_id, fencing_token),
    CONSTRAINT uk_execution_lease_claim_hash UNIQUE (claim_token_hash),
    CONSTRAINT fk_execution_lease_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_execution_lease_worker
        FOREIGN KEY (organization_id, runtime_environment, runtime_id, worker_id)
        REFERENCES crewscope.runtime_worker (
            organization_id, runtime_environment, runtime_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_execution_lease_attempt CHECK (attempt > 0),
    CONSTRAINT ck_execution_lease_claim_hash CHECK (
        claim_token_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_execution_lease_fencing CHECK (fencing_token > 0),
    CONSTRAINT ck_execution_lease_phase CHECK (phase IN ('PREPARE', 'RUN')),
    CONSTRAINT ck_execution_lease_status CHECK (status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ck_execution_lease_release_shape CHECK (
        (status = 'ACTIVE'
            AND released_at IS NULL
            AND release_reason IS NULL)
        OR (status = 'RELEASED'
            AND released_at IS NOT NULL
            AND release_reason IN (
                'COMPLETED', 'FAILED', 'CANCELLED', 'PAUSED', 'WAITING', 'EXPIRED',
                'MANUAL_TAKEOVER', 'WORKER_SHUTDOWN'
            ))
    ),
    CONSTRAINT ck_execution_lease_times CHECK (
        acquired_at <= last_heartbeat_at
        AND last_heartbeat_at < expires_at
        AND (released_at IS NULL OR released_at >= acquired_at)
    ),
    CONSTRAINT ck_execution_lease_version CHECK (lease_version >= 0)
);

CREATE UNIQUE INDEX ux_execution_lease_active
    ON crewscope.execution_lease (task_execution_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_execution_lease_expiry
    ON crewscope.execution_lease (
        organization_id, runtime_environment, expires_at, task_execution_id
    ) WHERE status = 'ACTIVE';

CREATE INDEX ix_execution_lease_owner
    ON crewscope.execution_lease (runtime_id, worker_id, status, expires_at);

CREATE TABLE crewscope.task_credential_grant (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    execution_lease_id UUID NOT NULL,
    runtime_environment VARCHAR(64) NOT NULL,
    runtime_id UUID NOT NULL,
    worker_id UUID NOT NULL,
    claim_token_hash CHAR(64) NOT NULL,
    fencing_token BIGINT NOT NULL,
    execution_principal_id UUID NOT NULL,
    execution_assignment_id UUID NOT NULL,
    execution_assignment_version BIGINT NOT NULL,
    responsibility_snapshot_hash CHAR(64) NOT NULL,
    policy_snapshot_id UUID NOT NULL,
    policy_snapshot_hash CHAR(64) NOT NULL,
    safety_overlay_id UUID NOT NULL,
    safety_overlay_version BIGINT NOT NULL,
    safety_overlay_hash CHAR(64) NOT NULL,
    jti_hash CHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    use_count BIGINT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMPTZ,
    terminated_by_principal_id UUID,
    terminated_at TIMESTAMPTZ,
    termination_reason VARCHAR(2000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_task_credential_grant_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_task_credential_grant_workspace_id
        UNIQUE (organization_id, team_id, workspace_id, id),
    CONSTRAINT uk_task_credential_grant_jti_hash UNIQUE (jti_hash),
    CONSTRAINT fk_task_credential_grant_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_credential_grant_lease
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, execution_lease_id
        ) REFERENCES crewscope.execution_lease (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_credential_grant_worker
        FOREIGN KEY (organization_id, runtime_environment, runtime_id, worker_id)
        REFERENCES crewscope.runtime_worker (
            organization_id, runtime_environment, runtime_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_credential_grant_principal
        FOREIGN KEY (organization_id, execution_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_credential_grant_policy
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, policy_snapshot_id
        ) REFERENCES crewscope.policy_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_credential_grant_safety_overlay
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, safety_overlay_id, safety_overlay_version
        ) REFERENCES crewscope.safety_enforcement_overlay (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, overlay_version
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_credential_grant_terminated_by
        FOREIGN KEY (organization_id, terminated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_credential_grant_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_credential_grant_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_task_credential_grant_tokens CHECK (
        claim_token_hash ~ '^[0-9a-f]{64}$'
        AND jti_hash ~ '^[0-9a-f]{64}$'
        AND fencing_token > 0
    ),
    CONSTRAINT ck_task_credential_grant_versions CHECK (
        attempt > 0 AND execution_assignment_version >= 0
        AND safety_overlay_version > 0 AND version >= 0
    ),
    CONSTRAINT ck_task_credential_grant_hashes CHECK (
        responsibility_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND policy_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND safety_overlay_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_task_credential_grant_status CHECK (
        status IN ('ACTIVE', 'REVOKED', 'EXPIRED')
    ),
    CONSTRAINT ck_task_credential_grant_usage CHECK (
        use_count >= 0
        AND ((use_count = 0 AND last_used_at IS NULL)
            OR (use_count > 0 AND last_used_at IS NOT NULL))
    ),
    CONSTRAINT ck_task_credential_grant_terminal_shape CHECK (
        (status = 'ACTIVE'
            AND terminated_by_principal_id IS NULL
            AND terminated_at IS NULL
            AND termination_reason IS NULL)
        OR (status IN ('REVOKED', 'EXPIRED')
            AND terminated_by_principal_id IS NOT NULL
            AND terminated_at IS NOT NULL
            AND BTRIM(termination_reason) <> '')
    ),
    CONSTRAINT ck_task_credential_grant_times CHECK (
        issued_at < expires_at
        AND (last_used_at IS NULL OR last_used_at BETWEEN issued_at AND expires_at)
        AND (terminated_at IS NULL OR terminated_at >= issued_at)
        AND updated_at >= created_at
    )
);

CREATE UNIQUE INDEX ux_task_credential_grant_active_execution
    ON crewscope.task_credential_grant (task_execution_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_task_credential_grant_expiry
    ON crewscope.task_credential_grant (
        organization_id, runtime_environment, expires_at, task_execution_id
    ) WHERE status = 'ACTIVE';

CREATE TABLE crewscope.task_credential_grant_tool (
    grant_id UUID NOT NULL,
    tool_key VARCHAR(128) NOT NULL,
    PRIMARY KEY (grant_id, tool_key),
    CONSTRAINT fk_task_credential_grant_tool
        FOREIGN KEY (grant_id) REFERENCES crewscope.task_credential_grant (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_task_credential_grant_tool_key
        CHECK (tool_key ~ '^[a-z][a-z0-9._-]{0,127}$')
);

CREATE TABLE crewscope.task_credential_grant_provider (
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    grant_id UUID NOT NULL,
    provider_binding_id UUID NOT NULL,
    provider_binding_version BIGINT NOT NULL,
    connection_grant_id UUID,
    connection_grant_version BIGINT,
    capabilities JSONB NOT NULL,
    resources JSONB NOT NULL,
    PRIMARY KEY (grant_id, provider_binding_id),
    CONSTRAINT fk_task_credential_grant_provider_grant
        FOREIGN KEY (organization_id, team_id, workspace_id, grant_id)
        REFERENCES crewscope.task_credential_grant (
            organization_id, team_id, workspace_id, id
        )
        ON DELETE CASCADE,
    CONSTRAINT fk_task_credential_grant_provider_binding
        FOREIGN KEY (organization_id, team_id, workspace_id, provider_binding_id)
        REFERENCES crewscope.provider_binding (
            organization_id, team_id, workspace_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_credential_grant_connection_grant
        FOREIGN KEY (connection_grant_id)
        REFERENCES crewscope.connection_grant (id) ON DELETE RESTRICT,
    CONSTRAINT ck_task_credential_grant_provider_versions CHECK (
        provider_binding_version >= 0
        AND ((connection_grant_id IS NULL AND connection_grant_version IS NULL)
            OR (connection_grant_id IS NOT NULL AND connection_grant_version >= 0))
    ),
    CONSTRAINT ck_task_credential_grant_provider_access CHECK (
        JSONB_TYPEOF(capabilities) = 'array'
        AND JSONB_ARRAY_LENGTH(capabilities) > 0
        AND JSONB_TYPEOF(resources) = 'array'
        AND JSONB_ARRAY_LENGTH(resources) > 0
    )
);

-- V7 stored only Personal Conversation sessions. V10 keeps those columns and facts intact while
-- adding a common Agent identity plus mutually exclusive Task-side bindings.
ALTER TABLE crewscope.agent_profile
    ADD CONSTRAINT uk_agent_profile_runtime_identity
        UNIQUE (
            organization_id, team_id, workspace_id, id,
            agent_principal_id, profile_type
        );

ALTER TABLE crewscope.agent_runtime_session
    ADD COLUMN session_purpose VARCHAR(32) NOT NULL DEFAULT 'PERSONAL',
    ADD COLUMN project_id UUID,
    ADD COLUMN task_id UUID,
    ADD COLUMN task_execution_id UUID,
    ADD COLUMN step_execution_id UUID,
    ADD COLUMN agent_principal_id UUID,
    ADD COLUMN agent_principal_type VARCHAR(32),
    ADD COLUMN agent_profile_type VARCHAR(32);

UPDATE crewscope.agent_runtime_session
SET agent_principal_id = personal_agent_principal_id,
    agent_principal_type = 'PERSONAL_AGENT',
    agent_profile_type = 'PERSONAL'
WHERE session_purpose = 'PERSONAL';

ALTER TABLE crewscope.agent_runtime_session
    ALTER COLUMN conversation_id DROP NOT NULL,
    ALTER COLUMN owner_member_id DROP NOT NULL,
    ALTER COLUMN owner_principal_id DROP NOT NULL,
    ALTER COLUMN personal_agent_principal_id DROP NOT NULL,
    ALTER COLUMN agent_principal_id SET NOT NULL,
    ALTER COLUMN agent_principal_type SET NOT NULL,
    ALTER COLUMN agent_profile_type SET NOT NULL,
    ADD CONSTRAINT uk_agent_runtime_session_common_scope_id
        UNIQUE (organization_id, team_id, workspace_id, id),
    ADD CONSTRAINT uk_agent_runtime_session_task_identity
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, agent_principal_id,
            agent_profile_id, agent_profile_version
        ),
    ADD CONSTRAINT uk_agent_runtime_session_snapshot_identity
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, agent_principal_id,
            agent_profile_id, agent_profile_version,
            agent_scope_user_id, agent_scope_session_id
        ),
    ADD CONSTRAINT fk_agent_runtime_session_task
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id, task_id)
        REFERENCES crewscope.task (organization_id, team_id, workspace_id, project_id, id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_agent_runtime_session_task_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id
        ) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_agent_runtime_session_step_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, step_execution_id
        ) REFERENCES crewscope.step_execution (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_agent_runtime_session_agent
        FOREIGN KEY (organization_id, agent_principal_id, agent_principal_type)
        REFERENCES crewscope.principal (organization_id, id, principal_type)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_agent_runtime_session_common_profile
        FOREIGN KEY (
            organization_id, team_id, workspace_id, agent_profile_id,
            agent_principal_id, agent_profile_type
        ) REFERENCES crewscope.agent_profile (
            organization_id, team_id, workspace_id, id,
            agent_principal_id, profile_type
        ) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_agent_runtime_session_purpose CHECK (
        session_purpose IN ('PERSONAL', 'TASK', 'STEP', 'SPECIALIST')
    ),
    ADD CONSTRAINT ck_agent_runtime_session_shape CHECK (
        (session_purpose = 'PERSONAL'
            AND conversation_id IS NOT NULL
            AND owner_member_id IS NOT NULL
            AND owner_principal_id IS NOT NULL
            AND personal_agent_principal_id = agent_principal_id
            AND agent_principal_type = 'PERSONAL_AGENT'
            AND agent_profile_type = 'PERSONAL'
            AND project_id IS NULL
            AND task_id IS NULL
            AND task_execution_id IS NULL
            AND step_execution_id IS NULL)
        OR (session_purpose = 'TASK'
            AND conversation_id IS NULL
            AND owner_member_id IS NULL
            AND owner_principal_id IS NULL
            AND personal_agent_principal_id IS NULL
            AND agent_principal_type = 'TEAM_AGENT'
            AND agent_profile_type = 'TEAM'
            AND project_id IS NOT NULL
            AND task_id IS NOT NULL
            AND task_execution_id IS NOT NULL
            AND step_execution_id IS NULL)
        OR (session_purpose = 'STEP'
            AND conversation_id IS NULL
            AND owner_member_id IS NULL
            AND owner_principal_id IS NULL
            AND personal_agent_principal_id IS NULL
            AND agent_principal_type = 'TEAM_AGENT'
            AND agent_profile_type = 'TEAM'
            AND project_id IS NOT NULL
            AND task_id IS NOT NULL
            AND task_execution_id IS NOT NULL
            AND step_execution_id IS NOT NULL)
        OR (session_purpose = 'SPECIALIST'
            AND conversation_id IS NULL
            AND owner_member_id IS NULL
            AND owner_principal_id IS NULL
            AND personal_agent_principal_id IS NULL
            AND agent_principal_type = 'SPECIALIST_AGENT'
            AND agent_profile_type = 'SPECIALIST'
            AND project_id IS NOT NULL
            AND task_id IS NOT NULL
            AND task_execution_id IS NOT NULL
            AND step_execution_id IS NOT NULL)
    );

CREATE UNIQUE INDEX ux_agent_runtime_session_active_task_binding
    ON crewscope.agent_runtime_session (
        task_execution_id, session_purpose,
        COALESCE(step_execution_id, '00000000-0000-0000-0000-000000000000'::UUID),
        agent_principal_id
    ) WHERE session_purpose <> 'PERSONAL' AND status = 'ACTIVE';

CREATE INDEX ix_agent_runtime_session_task_status
    ON crewscope.agent_runtime_session (
        organization_id, team_id, workspace_id, project_id,
        task_execution_id, status, updated_at DESC
    ) WHERE session_purpose <> 'PERSONAL';

CREATE TABLE crewscope.agent_run (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    step_execution_id UUID,
    runtime_session_id UUID NOT NULL,
    agent_principal_id UUID NOT NULL,
    agent_profile_id UUID NOT NULL,
    agent_profile_version BIGINT NOT NULL,
    run_sequence BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    continuity_previous_run_id UUID,
    continuity_snapshot_id UUID,
    first_missing_checkpoint BIGINT,
    last_missing_checkpoint BIGINT,
    continuity_reason VARCHAR(64),
    continuity_detected_at TIMESTAMPTZ,
    terminal_failure_code VARCHAR(100),
    terminal_result_artifact_id UUID,
    terminal_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_agent_run_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_agent_run_execution_sequence UNIQUE (task_execution_id, run_sequence),
    CONSTRAINT fk_agent_run_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_step
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, step_execution_id
        ) REFERENCES crewscope.step_execution (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_session
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, runtime_session_id,
            agent_principal_id, agent_profile_id, agent_profile_version
        ) REFERENCES crewscope.agent_runtime_session (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id,
            agent_principal_id, agent_profile_id, agent_profile_version
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_agent
        FOREIGN KEY (organization_id, agent_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_previous
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, continuity_previous_run_id
        ) REFERENCES crewscope.agent_run (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_run_sequence CHECK (run_sequence > 0),
    CONSTRAINT ck_agent_run_profile_version CHECK (agent_profile_version >= 0),
    CONSTRAINT ck_agent_run_status CHECK (
        status IN ('RUNNING', 'INTERRUPTED', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_agent_run_continuity_shape CHECK (
        (continuity_previous_run_id IS NULL
            AND continuity_snapshot_id IS NULL
            AND first_missing_checkpoint IS NULL
            AND last_missing_checkpoint IS NULL
            AND continuity_reason IS NULL
            AND continuity_detected_at IS NULL)
        OR (continuity_previous_run_id IS NOT NULL
            AND first_missing_checkpoint > 0
            AND last_missing_checkpoint >= first_missing_checkpoint
            AND continuity_reason IN (
                'SNAPSHOT_MISSING', 'SNAPSHOT_CORRUPT',
                'SNAPSHOT_IDENTITY_MISMATCH', 'REDIS_STATE_LOST', 'UNSAFE_CHECKPOINT'
            )
            AND continuity_detected_at IS NOT NULL)
    ),
    CONSTRAINT ck_agent_run_terminal_shape CHECK (
        (status IN ('COMPLETED', 'FAILED', 'CANCELLED')
            AND terminal_at IS NOT NULL
            AND ((status = 'FAILED'
                    AND terminal_failure_code ~ '^[A-Z][A-Z0-9_]{0,99}$')
                OR (status <> 'FAILED' AND terminal_failure_code IS NULL))
            AND (status <> 'CANCELLED' OR terminal_result_artifact_id IS NULL))
        OR (status IN ('RUNNING', 'INTERRUPTED')
            AND terminal_failure_code IS NULL
            AND terminal_result_artifact_id IS NULL
            AND terminal_at IS NULL)
    ),
    CONSTRAINT ck_agent_run_version CHECK (version >= 0),
    CONSTRAINT ck_agent_run_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX ux_agent_run_active_session
    ON crewscope.agent_run (runtime_session_id)
    WHERE status IN ('RUNNING', 'INTERRUPTED');

CREATE INDEX ix_agent_run_execution_history
    ON crewscope.agent_run (task_execution_id, run_sequence DESC);

CREATE TABLE crewscope.agent_run_segment (
    agent_run_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    resumed_from_interrupt_id UUID,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    PRIMARY KEY (agent_run_id, sequence),
    CONSTRAINT fk_agent_run_segment_run
        FOREIGN KEY (agent_run_id) REFERENCES crewscope.agent_run (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_agent_run_segment_sequence CHECK (sequence > 0),
    CONSTRAINT ck_agent_run_segment_kind CHECK (kind IN ('INVOKE', 'RESUME', 'RECOVERY')),
    CONSTRAINT ck_agent_run_segment_resume_shape CHECK (
        (kind = 'RESUME' AND resumed_from_interrupt_id IS NOT NULL)
        OR (kind IN ('INVOKE', 'RECOVERY') AND resumed_from_interrupt_id IS NULL)
    ),
    CONSTRAINT ck_agent_run_segment_status CHECK (
        status IN ('ACTIVE', 'INTERRUPTED', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_agent_run_segment_terminal_shape CHECK (
        (status = 'ACTIVE' AND ended_at IS NULL)
        OR (status <> 'ACTIVE' AND ended_at IS NOT NULL AND ended_at >= started_at)
    )
);

CREATE UNIQUE INDEX ux_agent_run_segment_active
    ON crewscope.agent_run_segment (agent_run_id)
    WHERE status = 'ACTIVE';

CREATE TABLE crewscope.agent_interrupt (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    agent_run_id UUID NOT NULL,
    segment_sequence BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    interrupt_token_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    resume_request_id UUID,
    response_hash CHAR(64),
    resolved_by_principal_id UUID,
    resolved_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_agent_interrupt_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_agent_interrupt_resume_request UNIQUE (resume_request_id),
    CONSTRAINT uk_agent_interrupt_token_hash UNIQUE (interrupt_token_hash),
    CONSTRAINT fk_agent_interrupt_run
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, agent_run_id
        ) REFERENCES crewscope.agent_run (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_interrupt_segment
        FOREIGN KEY (agent_run_id, segment_sequence)
        REFERENCES crewscope.agent_run_segment (agent_run_id, sequence)
        ON DELETE RESTRICT,
    CONSTRAINT fk_agent_interrupt_resolved_by
        FOREIGN KEY (organization_id, resolved_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_interrupt_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_interrupt_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_interrupt_segment CHECK (segment_sequence > 0),
    CONSTRAINT ck_agent_interrupt_kind CHECK (
        kind IN ('CLARIFICATION', 'PERMISSION', 'APPROVAL', 'PAUSE')
    ),
    CONSTRAINT ck_agent_interrupt_token_hash CHECK (
        interrupt_token_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_agent_interrupt_status CHECK (
        status IN ('PENDING', 'RESOLVED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT ck_agent_interrupt_resolution_shape CHECK (
        (status = 'RESOLVED'
            AND resume_request_id IS NOT NULL
            AND response_hash ~ '^[0-9a-f]{64}$'
            AND resolved_by_principal_id IS NOT NULL
            AND resolved_at IS NOT NULL)
        OR (status <> 'RESOLVED'
            AND resume_request_id IS NULL
            AND response_hash IS NULL
            AND resolved_by_principal_id IS NULL
            AND resolved_at IS NULL)
    ),
    CONSTRAINT ck_agent_interrupt_version CHECK (version >= 0),
    CONSTRAINT ck_agent_interrupt_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX ux_agent_interrupt_pending_run
    ON crewscope.agent_interrupt (agent_run_id)
    WHERE status = 'PENDING';

-- Segment resume references are installed after Interrupt exists. Deferral allows one transaction
-- to close the interrupt and append its RESUME segment without depending on statement order.
ALTER TABLE crewscope.agent_run_segment
    ADD CONSTRAINT fk_agent_run_segment_resume_interrupt
        FOREIGN KEY (resumed_from_interrupt_id)
        REFERENCES crewscope.agent_interrupt (id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE crewscope.runtime_artifact (
    id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL UNIQUE,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    step_execution_id UUID,
    agent_run_id UUID NOT NULL,
    kind VARCHAR(32) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    retention_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_runtime_artifact_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_runtime_artifact_snapshot_identity
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, kind, content_type,
            content_hash, size_bytes
        ),
    CONSTRAINT uk_runtime_artifact_content_identity
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, agent_run_id, id, content_hash, size_bytes
        ),
    CONSTRAINT fk_runtime_artifact_run
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, agent_run_id
        ) REFERENCES crewscope.agent_run (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_runtime_artifact_step
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, step_execution_id
        ) REFERENCES crewscope.step_execution (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_runtime_artifact_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_runtime_artifact_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_runtime_artifact_kind CHECK (kind IN (
        'AGENT_STATE_SNAPSHOT', 'MODEL_RESULT', 'TOOL_RESULT',
        'EXECUTION_LOG', 'PLAN_DRAFT', 'TODO_SNAPSHOT'
    )),
    CONSTRAINT ck_runtime_artifact_content_type CHECK (
        BTRIM(content_type) <> '' AND content_type = LOWER(content_type)
    ),
    CONSTRAINT ck_runtime_artifact_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_runtime_artifact_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_runtime_artifact_retention CHECK (
        retention_until IS NULL OR retention_until > created_at
    ),
    CONSTRAINT ck_runtime_artifact_producer CHECK (
        created_by_principal_id = updated_by_principal_id
    ),
    CONSTRAINT ck_runtime_artifact_version CHECK (version >= 0),
    CONSTRAINT ck_runtime_artifact_timestamps CHECK (updated_at >= created_at)
);

ALTER TABLE crewscope.agent_run
    ADD CONSTRAINT fk_agent_run_terminal_artifact
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, terminal_result_artifact_id
        ) REFERENCES crewscope.runtime_artifact (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE crewscope.agent_state_snapshot (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    agent_run_id UUID NOT NULL,
    runtime_session_id UUID NOT NULL,
    agent_profile_id UUID NOT NULL,
    agent_profile_version BIGINT NOT NULL,
    agent_principal_id UUID NOT NULL,
    agent_name VARCHAR(100) NOT NULL,
    agent_scope_user_id VARCHAR(500) NOT NULL,
    agent_scope_session_id VARCHAR(500) NOT NULL,
    snapshot_sequence BIGINT NOT NULL,
    checkpoint_sequence BIGINT NOT NULL,
    runtime_artifact_id UUID NOT NULL,
    content_hash CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    invalid_reason_code VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_agent_state_snapshot_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_agent_state_snapshot_sequence
        UNIQUE (task_execution_id, snapshot_sequence),
    CONSTRAINT uk_agent_state_snapshot_checkpoint
        UNIQUE (runtime_session_id, checkpoint_sequence),
    CONSTRAINT fk_agent_state_snapshot_run
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, agent_run_id
        ) REFERENCES crewscope.agent_run (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_state_snapshot_session
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, runtime_session_id,
            agent_principal_id, agent_profile_id, agent_profile_version,
            agent_scope_user_id, agent_scope_session_id
        ) REFERENCES crewscope.agent_runtime_session (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id,
            agent_principal_id, agent_profile_id, agent_profile_version,
            agent_scope_user_id, agent_scope_session_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_state_snapshot_artifact
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, agent_run_id, runtime_artifact_id,
            content_hash, size_bytes
        ) REFERENCES crewscope.runtime_artifact (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, agent_run_id, id, content_hash, size_bytes
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_state_snapshot_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_state_snapshot_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_state_snapshot_agent_name CHECK (
        agent_name ~ '^[A-Za-z][A-Za-z0-9_.-]{0,99}$'
    ),
    CONSTRAINT ck_agent_state_snapshot_agentscope_key CHECK (
        agent_scope_user_id LIKE 'crewscope:v1:user:%'
        AND agent_scope_session_id LIKE 'crewscope:v1:session:%'
    ),
    CONSTRAINT ck_agent_state_snapshot_sequence CHECK (
        snapshot_sequence > 0 AND checkpoint_sequence > 0
    ),
    CONSTRAINT ck_agent_state_snapshot_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_state_snapshot_size CHECK (
        size_bytes BETWEEN 1 AND 8388608
    ),
    CONSTRAINT ck_agent_state_snapshot_status CHECK (
        status IN ('CURRENT', 'SUPERSEDED', 'INVALID')
    ),
    CONSTRAINT ck_agent_state_snapshot_invalid_shape CHECK (
        (status = 'INVALID' AND invalid_reason_code ~ '^[A-Z][A-Z0-9_]{0,99}$')
        OR (status <> 'INVALID' AND invalid_reason_code IS NULL)
    ),
    CONSTRAINT ck_agent_state_snapshot_version CHECK (
        agent_profile_version >= 0 AND version >= 0
    ),
    CONSTRAINT ck_agent_state_snapshot_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX ux_agent_state_snapshot_current_session
    ON crewscope.agent_state_snapshot (runtime_session_id)
    WHERE status = 'CURRENT';

CREATE INDEX ix_agent_state_snapshot_recovery
    ON crewscope.agent_state_snapshot (
        runtime_session_id, checkpoint_sequence DESC, snapshot_sequence DESC
    ) WHERE status IN ('CURRENT', 'SUPERSEDED');

ALTER TABLE crewscope.agent_state_snapshot
    ADD CONSTRAINT uk_agent_state_snapshot_run_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, agent_run_id, id
        );

ALTER TABLE crewscope.agent_run
    ADD CONSTRAINT fk_agent_run_continuity_snapshot
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id,
            continuity_previous_run_id, continuity_snapshot_id
        ) REFERENCES crewscope.agent_state_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, agent_run_id, id
        ) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

-- Exact runtime-event receipt. The full event is fingerprinted in memory; only its hash and the
-- sanitized public DomainEvent reference are retained in PostgreSQL.
ALTER TABLE crewscope.agent_run
    ADD CONSTRAINT uk_agent_run_organization_id UNIQUE (organization_id, id);

CREATE TABLE crewscope.agent_run_event_receipt (
    organization_id UUID NOT NULL,
    agent_run_id UUID NOT NULL,
    segment_sequence BIGINT NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_hash CHAR(64) NOT NULL,
    runtime_event_type VARCHAR(100) NOT NULL,
    domain_event_id UUID NOT NULL,
    runtime_occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, agent_run_id, segment_sequence, event_sequence),
    CONSTRAINT uk_agent_run_event_receipt_domain_event
        UNIQUE (organization_id, domain_event_id),
    CONSTRAINT fk_agent_run_event_receipt_run
        FOREIGN KEY (organization_id, agent_run_id)
        REFERENCES crewscope.agent_run (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_event_receipt_segment
        FOREIGN KEY (agent_run_id, segment_sequence)
        REFERENCES crewscope.agent_run_segment (agent_run_id, sequence) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_event_receipt_domain_event
        FOREIGN KEY (organization_id, domain_event_id)
        REFERENCES crewscope.domain_event (organization_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_run_event_receipt_sequence CHECK (
        segment_sequence > 0 AND event_sequence > 0
    ),
    CONSTRAINT ck_agent_run_event_receipt_hash CHECK (
        event_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_agent_run_event_receipt_type CHECK (
        runtime_event_type ~ '^[A-Z][A-Z0-9_]{0,99}$'
    )
);

-- Deliberately no step_execution_lease table: one TaskExecution Lease serializes all MVP Steps.
