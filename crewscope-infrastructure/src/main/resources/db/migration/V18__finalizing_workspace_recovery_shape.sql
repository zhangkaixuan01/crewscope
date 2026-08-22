-- A RECOVERING Workspace retains the completion fact when FINALIZING was interrupted.
-- This keeps the relational invariant aligned with ExecutionWorkspace.beginRecovery().
ALTER TABLE crewscope.execution_workspace
    DROP CONSTRAINT ck_execution_workspace_terminal_shape;

ALTER TABLE crewscope.execution_workspace
    ADD CONSTRAINT ck_execution_workspace_terminal_shape CHECK (
        (status IN ('FINALIZING', 'COMPLETED')
            AND completion_reason IN ('SUCCEEDED', 'CANCELLED')
            AND failure_code IS NULL)
        OR (status = 'RECOVERING'
            AND failure_code IS NULL
            AND ((recovery_target_status = 'FINALIZING'
                    AND completion_reason IN ('SUCCEEDED', 'CANCELLED'))
                OR (recovery_target_status <> 'FINALIZING'
                    AND completion_reason IS NULL)))
        OR (status = 'FAILED'
            AND completion_reason IS NULL
            AND failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
        OR (status = 'ARCHIVED'
            AND ((completion_reason IN ('SUCCEEDED', 'CANCELLED') AND failure_code IS NULL)
                OR (completion_reason IS NULL
                    AND failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$')))
        OR (status NOT IN (
                'FINALIZING', 'COMPLETED', 'RECOVERING', 'FAILED', 'ARCHIVED')
            AND completion_reason IS NULL
            AND failure_code IS NULL)
    );
