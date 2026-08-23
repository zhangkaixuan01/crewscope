-- M5-I07 closes lossless Review Decision recovery and adds a rebuildable query projection.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM crewscope.review_decision
        WHERE eligibility_mode = 'EXPLICIT_SELF_REVIEW_OVERRIDE'
    ) THEN
        RAISE EXCEPTION
            'V24 cannot infer missing override authority from a pre-I07 ReviewDecision';
    END IF;
END;
$$;

ALTER TABLE crewscope.review_decision
    ADD COLUMN eligibility_conflicting_roles JSONB NOT NULL DEFAULT '[]'::JSONB,
    ADD COLUMN eligibility_policy_pack_id UUID,
    ADD COLUMN eligibility_policy_pack_version BIGINT,
    ADD COLUMN eligibility_override_reason TEXT;

ALTER TABLE crewscope.review_decision
    ALTER COLUMN eligibility_conflicting_roles DROP DEFAULT,
    ADD CONSTRAINT ck_review_decision_eligibility_authority_v24 CHECK (
        JSONB_TYPEOF(eligibility_conflicting_roles) = 'array'
        AND (
            eligibility_mode = 'INDEPENDENT_MEMBER'
            AND JSONB_ARRAY_LENGTH(eligibility_conflicting_roles) = 0
            AND eligibility_policy_pack_id IS NULL
            AND eligibility_policy_pack_version IS NULL
            AND eligibility_override_reason IS NULL
            OR
            eligibility_mode = 'EXPLICIT_SELF_REVIEW_OVERRIDE'
            AND JSONB_ARRAY_LENGTH(eligibility_conflicting_roles) BETWEEN 1 AND 2
            AND eligibility_conflicting_roles <@ '["OWNER", "EXECUTOR"]'::JSONB
            AND eligibility_policy_pack_id IS NOT NULL
            AND eligibility_policy_pack_version IS NOT NULL
            AND eligibility_policy_pack_version > 0
            AND eligibility_override_reason IS NOT NULL
            AND BTRIM(eligibility_override_reason) <> ''
            AND CHAR_LENGTH(eligibility_override_reason) <= 1000
        )
    );

CREATE TABLE crewscope.review_request_projection (
    review_request_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    request_revision BIGINT NOT NULL,
    request_version BIGINT NOT NULL,
    request_status VARCHAR(32) NOT NULL,
    invalidation_reason VARCHAR(64),
    context_hash CHAR(64) NOT NULL,
    finding_count INTEGER NOT NULL,
    duplicate_observation_count INTEGER NOT NULL,
    blocker_count INTEGER NOT NULL,
    high_count INTEGER NOT NULL,
    latest_decision_id UUID,
    latest_decision_revision BIGINT,
    latest_decision_type VARCHAR(32),
    modification_round BIGINT NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_review_request_projection_scope
        UNIQUE (organization_id, review_request_id),
    CONSTRAINT fk_review_request_projection_request
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, review_request_id
        ) REFERENCES crewscope.review_request (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_review_request_projection_values CHECK (
        attempt BETWEEN 1 AND 100
        AND request_revision > 0 AND request_version >= 0
        AND request_status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'INVALIDATED')
        AND ((request_status = 'INVALIDATED' AND invalidation_reason IS NOT NULL)
            OR (request_status <> 'INVALIDATED' AND invalidation_reason IS NULL))
        AND context_hash ~ '^[0-9a-f]{64}$'
        AND finding_count >= 0 AND duplicate_observation_count >= 0
        AND blocker_count >= 0 AND high_count >= 0
        AND modification_round >= 0
        AND ((latest_decision_id IS NULL
                AND latest_decision_revision IS NULL
                AND latest_decision_type IS NULL)
            OR (latest_decision_id IS NOT NULL
                AND latest_decision_revision > 0
                AND latest_decision_type IN (
                    'COMMENTED', 'APPROVED', 'CHANGES_REQUESTED', 'REJECTED')))
    )
);

CREATE INDEX ix_review_request_projection_task_history
    ON crewscope.review_request_projection (
        organization_id, task_id, request_revision DESC, review_request_id DESC
    );

CREATE INDEX ix_review_request_projection_execution
    ON crewscope.review_request_projection (
        organization_id, task_execution_id, attempt,
        request_revision DESC, review_request_id DESC
    );

