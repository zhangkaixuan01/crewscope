-- Durable pre-effect reservations prevent filesystem budgets from resetting across Worker loss.
CREATE TABLE crewscope.workspace_write_budget_usage (
    execution_workspace_id UUID PRIMARY KEY,
    workspace_policy_id UUID NOT NULL,
    policy_hash CHAR(64) NOT NULL,
    write_operations INTEGER NOT NULL,
    written_bytes BIGINT NOT NULL,
    changed_paths JSONB NOT NULL,
    reservation_sequence BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_workspace_write_budget_workspace
        FOREIGN KEY (execution_workspace_id)
        REFERENCES crewscope.execution_workspace (id) ON DELETE RESTRICT,
    CONSTRAINT fk_workspace_write_budget_policy
        FOREIGN KEY (workspace_policy_id)
        REFERENCES crewscope.workspace_policy (id) ON DELETE RESTRICT,
    CONSTRAINT ck_workspace_write_budget_hash
        CHECK (policy_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workspace_write_budget_counters CHECK (
        write_operations >= 0
        AND written_bytes >= 0
        AND reservation_sequence >= 0
        AND version >= 0
        AND JSONB_TYPEOF(changed_paths) = 'array'
        AND JSONB_ARRAY_LENGTH(changed_paths) <= write_operations
        AND updated_at >= created_at
    )
);

CREATE INDEX ix_workspace_write_budget_policy
    ON crewscope.workspace_write_budget_usage (workspace_policy_id, execution_workspace_id);
