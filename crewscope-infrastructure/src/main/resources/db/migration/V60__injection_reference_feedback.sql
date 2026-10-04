-- M10-I02c: execution reference feedback and claimed references (pure PostgreSQL).
-- Two immutable evidence tables on top of the V59 injection manifest. Feedback rows
-- are a member's "not applicable" judgement of one injected source key: the unique
-- key (execution, source key, member) is the structural idempotency boundary — the
-- same member marking the same evidence twice replays the stored row instead of
-- writing a second one. Claimed references are the model's own receipt of which
-- injected evidence it says it used, one row per (execution, attempt), reconcilable
-- against that attempt's sealed manifest alone. Both tables derive their six scope
-- coordinates from the task_execution row through the same composite RESTRICT
-- foreign key as V59, so no row can ever point at another tenant's execution. The
-- member column stores the principal id without a foreign key: feedback is a
-- historical quality opinion and must not block principal lifecycle cleanup.

CREATE TABLE crewscope.injection_reference_feedback (
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    source_type TEXT NOT NULL,
    source_id TEXT NOT NULL,
    source_version BIGINT NOT NULL,
    source_content_hash CHAR(64) NOT NULL,
    member_principal_id UUID NOT NULL,
    feedback_kind TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id),
    -- The structural idempotency key: one feedback row per (execution, source key,
    -- member). A replay converges on the stored row at the store level.
    CONSTRAINT uk_injection_reference_feedback_key UNIQUE (
        execution_id, source_type, source_id, source_version, source_content_hash,
        member_principal_id),
    CONSTRAINT fk_injection_reference_feedback_execution
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id, task_id, execution_id)
        REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_injection_reference_feedback_version CHECK (source_version >= 1),
    CONSTRAINT ck_injection_reference_feedback_hash CHECK (
        source_content_hash ~ '^[0-9a-f]{64}$'),
    -- S01 froze a single feedback semantics; the kind column reserves the enum slot.
    CONSTRAINT ck_injection_reference_feedback_kind CHECK (
        feedback_kind = 'NOT_APPLICABLE')
);

CREATE TABLE crewscope.injection_claimed_reference (
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    claimed JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id),
    -- One receipt per assembly: the same (execution, attempt) may only claim once;
    -- a differing set is a conflict, an identical set is a replay.
    CONSTRAINT uk_injection_claimed_reference_attempt UNIQUE (execution_id, attempt),
    CONSTRAINT fk_injection_claimed_reference_execution
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id, task_id, execution_id)
        REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_injection_claimed_reference_attempt CHECK (attempt >= 1),
    -- Shape lock only: the element contract lives in the domain record.
    CONSTRAINT ck_injection_claimed_reference_claimed CHECK (
        jsonb_typeof(claimed) = 'array')
);
