-- M9b-A07: responsibility handover job/item (ADR-038 §1).
--
-- Removing or suspending a member must not orphan their WorkItem responsibilities. A handover
-- job pins the source member's active assignments of one role together with each assignment's
-- optimistic version, and processes them item by item in independent short transactions by
-- replaying the ordinary responsibility commands. The unique keys make every step replay-safe:
-- one command id can queue at most one job, and one job processes each assignment at most once
-- (uk_handover_item_assignment), so an interrupted run resumes at the first PENDING item without
-- re-sending a DONE transfer.
--
-- No foreign keys to team_member or responsibility_assignment on purpose: the job and its items
-- are pinned historical facts. The source assignment may be released by a concurrent command —
-- the item then lands in CONFLICT via the version expectation instead of blocking the job.
CREATE TABLE crewscope.responsibility_handover_job (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    source_member_id UUID NOT NULL,
    target_principal_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    command_id VARCHAR(200) NOT NULL,
    created_by_principal_id UUID NOT NULL,
    source_authorization_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT ck_handover_job_role CHECK (role IN ('OWNER', 'EXECUTOR', 'REVIEWER')),
    CONSTRAINT ck_handover_job_status CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_handover_job_source_authorization_version
        CHECK (source_authorization_version >= 1),
    CONSTRAINT ux_handover_job_command UNIQUE (command_id)
);

CREATE INDEX ix_handover_job_team_status
    ON crewscope.responsibility_handover_job (team_id, status);

CREATE TABLE crewscope.responsibility_handover_item (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    job_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    expected_assignment_version BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    result_assignment_id UUID,
    error_code VARCHAR(100),
    processed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT fk_handover_item_job
        FOREIGN KEY (job_id) REFERENCES crewscope.responsibility_handover_job (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_handover_item_state CHECK (state IN ('PENDING', 'DONE', 'CONFLICT', 'DENIED')),
    CONSTRAINT ck_handover_item_terminal_consistency CHECK (
        (state = 'PENDING') = (processed_at IS NULL AND result_assignment_id IS NULL
            AND error_code IS NULL)),
    CONSTRAINT ck_handover_item_stopped_reason CHECK (
        state IN ('PENDING', 'DONE') OR error_code IS NOT NULL),
    CONSTRAINT uk_handover_item_assignment UNIQUE (job_id, assignment_id)
);

CREATE INDEX ix_handover_item_job_state
    ON crewscope.responsibility_handover_item (job_id, state);
