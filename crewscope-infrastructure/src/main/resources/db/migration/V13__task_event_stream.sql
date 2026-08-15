-- Task Event is a rebuildable durable stream index over canonical DomainEvent facts.
CREATE SEQUENCE crewscope.task_event_position_seq AS BIGINT;

CREATE TABLE crewscope.task_event (
    position BIGINT PRIMARY KEY DEFAULT nextval('crewscope.task_event_position_seq'),
    event_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID,
    step_execution_id UUID,
    agent_run_id UUID,
    execution_lease_id UUID,
    domain_event_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_task_event_stream_event UNIQUE (organization_id, event_id),
    CONSTRAINT uk_task_event_domain_event UNIQUE (organization_id, task_id, domain_event_id),
    CONSTRAINT fk_task_event_task FOREIGN KEY (
        organization_id, team_id, workspace_id, project_id, task_id
    ) REFERENCES crewscope.task (
        organization_id, team_id, workspace_id, project_id, id
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_event_execution FOREIGN KEY (
        organization_id, team_id, workspace_id, project_id, task_id, task_execution_id
    ) REFERENCES crewscope.task_execution (
        organization_id, team_id, workspace_id, project_id, task_id, id
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_event_step FOREIGN KEY (
        organization_id, team_id, workspace_id, project_id,
        task_id, task_execution_id, step_execution_id
    ) REFERENCES crewscope.step_execution (
        organization_id, team_id, workspace_id, project_id,
        task_id, task_execution_id, id
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_event_agent_run FOREIGN KEY (
        organization_id, team_id, workspace_id, project_id,
        task_id, task_execution_id, agent_run_id
    ) REFERENCES crewscope.agent_run (
        organization_id, team_id, workspace_id, project_id,
        task_id, task_execution_id, id
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_event_lease FOREIGN KEY (
        organization_id, team_id, workspace_id, project_id,
        task_id, task_execution_id, execution_lease_id
    ) REFERENCES crewscope.execution_lease (
        organization_id, team_id, workspace_id, project_id,
        task_id, task_execution_id, id
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_event_domain_event FOREIGN KEY (organization_id, domain_event_id)
        REFERENCES crewscope.domain_event (organization_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_task_event_position CHECK (position > 0),
    CONSTRAINT ck_task_event_context CHECK (
        (step_execution_id IS NULL AND agent_run_id IS NULL AND execution_lease_id IS NULL)
        OR task_execution_id IS NOT NULL
    )
);

ALTER SEQUENCE crewscope.task_event_position_seq OWNED BY crewscope.task_event.position;

CREATE INDEX ix_task_event_stream_position ON crewscope.task_event (
    organization_id, team_id, task_id, position
);

-- Backfill Task facts created before the stream projection. Only the closed M3 public event set
-- is eligible; arbitrary payload fields cannot associate unrelated events with a Task.
WITH source AS (
    SELECT
        event.*,
        COALESCE(
            CASE WHEN event.subject_type = 'TASK' THEN event.subject_id END,
            NULLIF(event.payload ->> 'taskId', '')::UUID,
            execution.task_id,
            run.task_id,
            lease_execution.task_id
        ) AS task_id,
        COALESCE(execution.id, run.task_execution_id, lease.task_execution_id) AS task_execution_id,
        run.step_execution_id,
        run.id AS agent_run_id,
        lease.id AS execution_lease_id
    FROM crewscope.domain_event event
    LEFT JOIN crewscope.task_execution execution
      ON execution.organization_id = event.organization_id
     AND execution.id = CASE
         WHEN event.subject_type = 'TASK_EXECUTION' THEN event.subject_id
         WHEN event.payload ? 'taskExecutionId'
             THEN NULLIF(event.payload ->> 'taskExecutionId', '')::UUID
         WHEN event.payload ? 'targetExecutionId'
             THEN NULLIF(event.payload ->> 'targetExecutionId', '')::UUID
         ELSE NULL
     END
    LEFT JOIN crewscope.agent_run run
      ON run.organization_id = event.organization_id
     AND run.id = CASE
         WHEN event.subject_type = 'AGENT_RUN' THEN event.subject_id
         WHEN event.payload ? 'agentRunId'
             THEN NULLIF(event.payload ->> 'agentRunId', '')::UUID
         ELSE NULL
     END
    LEFT JOIN crewscope.execution_lease lease
      ON lease.organization_id = event.organization_id
     AND lease.id = CASE
         WHEN event.subject_type = 'EXECUTION_LEASE' THEN event.subject_id
         WHEN event.payload ? 'executionLeaseId'
             THEN NULLIF(event.payload ->> 'executionLeaseId', '')::UUID
         WHEN event.payload ? 'leaseId'
             THEN NULLIF(event.payload ->> 'leaseId', '')::UUID
         ELSE NULL
     END
    LEFT JOIN crewscope.task_execution lease_execution
      ON lease_execution.organization_id = lease.organization_id
     AND lease_execution.id = lease.task_execution_id
    WHERE event.event_type IN (
            'TASK_DELEGATED_TO_AGENT',
            'MEMBER_TASK_PAUSE_ACCEPTED',
            'MEMBER_TASK_RESUME_ACCEPTED',
            'MEMBER_TASK_CANCEL_ACCEPTED',
            'MEMBER_TASK_RETRY_ACCEPTED',
            'WORKER_TASK_PREPARE_ACCEPTED',
            'WORKER_TASK_START_ACCEPTED',
            'WORKER_TASK_HEARTBEAT_ACCEPTED',
            'WORKER_TASK_PROGRESS_ACCEPTED',
            'WORKER_TASK_COMPLETE_ACCEPTED',
            'WORKER_TASK_FAIL_ACCEPTED',
            'TASK_EXECUTION_RECOVERY_STARTED',
            'AGENT_RUN_RESUMED',
            'AGENT_RUN_EVENT_RECORDED'
       )
), eligible AS (
    SELECT
        ROW_NUMBER() OVER (ORDER BY source.occurred_at, source.event_id) AS position,
        md5('CREWSCOPE:REALTIME:TASK:' || source.event_id::TEXT)::UUID AS stream_event_id,
        task.organization_id, task.team_id, task.workspace_id, task.project_id, task.id AS task_id,
        source.task_execution_id, source.step_execution_id, source.agent_run_id,
        source.execution_lease_id, source.event_id AS domain_event_id, source.occurred_at
    FROM source
    JOIN crewscope.task task
      ON task.organization_id = source.organization_id AND task.id = source.task_id
)
INSERT INTO crewscope.task_event (
    position, event_id,
    organization_id, team_id, workspace_id, project_id, task_id,
    task_execution_id, step_execution_id, agent_run_id, execution_lease_id,
    domain_event_id, occurred_at, created_at
)
SELECT
    position, stream_event_id,
    organization_id, team_id, workspace_id, project_id, task_id,
    task_execution_id, step_execution_id, agent_run_id, execution_lease_id,
    domain_event_id, occurred_at, occurred_at
FROM eligible
ORDER BY position;

SELECT setval(
    'crewscope.task_event_position_seq',
    COALESCE(MAX(position), 1),
    MAX(position) IS NOT NULL
)
FROM crewscope.task_event;
