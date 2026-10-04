-- M10-I02b: prompt injection manifest (pure PostgreSQL, no vector).
-- One row per (execution, attempt): the durable evidence of what one prompt assembly
-- considered, injected, trimmed and degraded, sealed before the first model call. The
-- composite FK rides the task_execution scope unique key so a manifest can never point
-- at another tenant's execution. The evidence columns are JSONB with shape-locked
-- CHECKs; the reference column is named source_references to avoid the reserved word
-- REFERENCES. Trims and candidates are sealed with the injected set (stage field), so
-- a later round of the same attempt reconciles against this row alone.

CREATE TABLE crewscope.injection_manifest (
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    source_references JSONB NOT NULL,
    trims JSONB NOT NULL,
    budget JSONB NOT NULL,
    degradations JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id),
    -- The idempotency key: exactly one sealed manifest per attempt, enforced at the
    -- store so a racing append surfaces as a unique violation to be converged.
    CONSTRAINT uk_injection_manifest_attempt UNIQUE (execution_id, attempt),
    CONSTRAINT fk_injection_manifest_execution
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id, task_id, execution_id)
        REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_injection_manifest_attempt CHECK (attempt >= 1),
    -- Shape locks only: field-level contracts live in the domain records.
    CONSTRAINT ck_injection_manifest_sources CHECK (
        jsonb_typeof(source_references) = 'array'),
    CONSTRAINT ck_injection_manifest_trims CHECK (jsonb_typeof(trims) = 'array'),
    CONSTRAINT ck_injection_manifest_budget CHECK (jsonb_typeof(budget) = 'object'),
    CONSTRAINT ck_injection_manifest_degradations CHECK (
        jsonb_typeof(degradations) = 'array')
);
