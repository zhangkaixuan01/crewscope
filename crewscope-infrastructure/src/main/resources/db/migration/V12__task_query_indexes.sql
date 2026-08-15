-- M3-A02 serves Team-scoped Task collections and execution-scoped runtime history through stable
-- keysets and bounded batch reads. These indexes keep the public query paths independent of table
-- growth without changing any business fact.

CREATE INDEX ix_task_team_updated
    ON crewscope.task (organization_id, team_id, updated_at DESC, id DESC);

CREATE INDEX ix_task_team_project_updated
    ON crewscope.task (
        organization_id, team_id, project_id, updated_at DESC, id DESC
    );

CREATE INDEX ix_task_team_status_updated
    ON crewscope.task (
        organization_id, team_id, status, updated_at DESC, id DESC
    );

CREATE INDEX ix_task_team_project_status_updated
    ON crewscope.task (
        organization_id, team_id, project_id, status, updated_at DESC, id DESC
    );

CREATE INDEX ix_agent_interrupt_execution_created
    ON crewscope.agent_interrupt (
        organization_id, task_execution_id, created_at, id
    );

CREATE INDEX ix_agent_state_snapshot_execution_sequence
    ON crewscope.agent_state_snapshot (
        organization_id, task_execution_id, snapshot_sequence, id
    );

CREATE INDEX ix_execution_lease_execution_acquired
    ON crewscope.execution_lease (
        organization_id, task_execution_id, acquired_at, id
    );
