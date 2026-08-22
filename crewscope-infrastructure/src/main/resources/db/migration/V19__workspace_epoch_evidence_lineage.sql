-- Evidence records the Workspace fingerprint of the producing ownership epoch. Recovery advances
-- ownership and therefore the current fingerprint, while historical evidence remains immutable.
-- The append-only epoch relation preserves every fingerprint that actually existed; artifact
-- foreign keys must never degrade to Workspace identity without the producing epoch.
ALTER TABLE crewscope.execution_workspace
    ADD CONSTRAINT uk_execution_workspace_immutable_lineage UNIQUE (
        organization_id,
        team_id,
        workspace_id,
        project_id,
        task_id,
        task_execution_id,
        attempt,
        id,
        coding_target_snapshot_id,
        coding_target_revision,
        coding_target_hash
    );

CREATE TABLE crewscope.execution_workspace_epoch (
    execution_workspace_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    workspace_fingerprint CHAR(64) NOT NULL,
    coding_target_snapshot_id UUID NOT NULL,
    coding_target_revision BIGINT NOT NULL,
    coding_target_hash CHAR(64) NOT NULL,
    recovery_generation BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_execution_workspace_epoch PRIMARY KEY (
        execution_workspace_id,
        workspace_fingerprint
    ),
    CONSTRAINT uk_execution_workspace_epoch_lineage UNIQUE (
        organization_id,
        team_id,
        workspace_id,
        project_id,
        task_id,
        task_execution_id,
        attempt,
        execution_workspace_id,
        workspace_fingerprint,
        coding_target_snapshot_id,
        coding_target_revision,
        coding_target_hash
    ),
    CONSTRAINT fk_execution_workspace_epoch_workspace FOREIGN KEY (
        organization_id,
        team_id,
        workspace_id,
        project_id,
        task_id,
        task_execution_id,
        attempt,
        execution_workspace_id,
        coding_target_snapshot_id,
        coding_target_revision,
        coding_target_hash
    ) REFERENCES crewscope.execution_workspace (
        organization_id,
        team_id,
        workspace_id,
        project_id,
        task_id,
        task_execution_id,
        attempt,
        id,
        coding_target_snapshot_id,
        coding_target_revision,
        coding_target_hash
    ) ON DELETE RESTRICT,
    CONSTRAINT ck_execution_workspace_epoch_shape CHECK (
        attempt BETWEEN 1 AND 100
        AND workspace_fingerprint ~ '^[0-9a-f]{64}$'
        AND coding_target_hash ~ '^[0-9a-f]{64}$'
        AND recovery_generation >= 0
    )
);

INSERT INTO crewscope.execution_workspace_epoch (
    execution_workspace_id,
    organization_id,
    team_id,
    workspace_id,
    project_id,
    task_id,
    task_execution_id,
    attempt,
    workspace_fingerprint,
    coding_target_snapshot_id,
    coding_target_revision,
    coding_target_hash,
    recovery_generation,
    recorded_at
)
SELECT
    id,
    organization_id,
    team_id,
    workspace_id,
    project_id,
    task_id,
    task_execution_id,
    attempt,
    workspace_fingerprint,
    coding_target_snapshot_id,
    coding_target_revision,
    coding_target_hash,
    recovery_generation,
    updated_at
FROM crewscope.execution_workspace;

CREATE FUNCTION crewscope.capture_execution_workspace_epoch()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, crewscope
AS $$
BEGIN
    INSERT INTO crewscope.execution_workspace_epoch (
        execution_workspace_id,
        organization_id,
        team_id,
        workspace_id,
        project_id,
        task_id,
        task_execution_id,
        attempt,
        workspace_fingerprint,
        coding_target_snapshot_id,
        coding_target_revision,
        coding_target_hash,
        recovery_generation,
        recorded_at
    ) VALUES (
        NEW.id,
        NEW.organization_id,
        NEW.team_id,
        NEW.workspace_id,
        NEW.project_id,
        NEW.task_id,
        NEW.task_execution_id,
        NEW.attempt,
        NEW.workspace_fingerprint,
        NEW.coding_target_snapshot_id,
        NEW.coding_target_revision,
        NEW.coding_target_hash,
        NEW.recovery_generation,
        NEW.updated_at
    ) ON CONFLICT (execution_workspace_id, workspace_fingerprint) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_execution_workspace_epoch
AFTER INSERT OR UPDATE OF workspace_fingerprint
ON crewscope.execution_workspace
FOR EACH ROW
EXECUTE FUNCTION crewscope.capture_execution_workspace_epoch();

ALTER TABLE crewscope.command_evidence
    DROP CONSTRAINT fk_command_evidence_workspace;
ALTER TABLE crewscope.test_evidence
    DROP CONSTRAINT fk_test_evidence_workspace;
ALTER TABLE crewscope.diff_artifact
    DROP CONSTRAINT fk_diff_artifact_workspace;
ALTER TABLE crewscope.coding_checkpoint
    DROP CONSTRAINT fk_coding_checkpoint_workspace;

ALTER TABLE crewscope.command_evidence
    ADD CONSTRAINT fk_command_evidence_workspace
        FOREIGN KEY (
            organization_id,
            team_id,
            workspace_id,
            project_id,
            task_id,
            task_execution_id,
            attempt,
            execution_workspace_id,
            workspace_fingerprint,
            coding_target_snapshot_id,
            coding_target_revision,
            coding_target_hash
        ) REFERENCES crewscope.execution_workspace_epoch (
            organization_id,
            team_id,
            workspace_id,
            project_id,
            task_id,
            task_execution_id,
            attempt,
            execution_workspace_id,
            workspace_fingerprint,
            coding_target_snapshot_id,
            coding_target_revision,
            coding_target_hash
        ) ON DELETE RESTRICT;

ALTER TABLE crewscope.test_evidence
    ADD CONSTRAINT fk_test_evidence_workspace
        FOREIGN KEY (
            organization_id,
            team_id,
            workspace_id,
            project_id,
            task_id,
            task_execution_id,
            attempt,
            execution_workspace_id,
            workspace_fingerprint,
            coding_target_snapshot_id,
            coding_target_revision,
            coding_target_hash
        ) REFERENCES crewscope.execution_workspace_epoch (
            organization_id,
            team_id,
            workspace_id,
            project_id,
            task_id,
            task_execution_id,
            attempt,
            execution_workspace_id,
            workspace_fingerprint,
            coding_target_snapshot_id,
            coding_target_revision,
            coding_target_hash
        ) ON DELETE RESTRICT;

ALTER TABLE crewscope.diff_artifact
    ADD CONSTRAINT fk_diff_artifact_workspace
        FOREIGN KEY (
            organization_id,
            team_id,
            workspace_id,
            project_id,
            task_id,
            task_execution_id,
            attempt,
            execution_workspace_id,
            workspace_fingerprint,
            coding_target_snapshot_id,
            coding_target_revision,
            coding_target_hash
        ) REFERENCES crewscope.execution_workspace_epoch (
            organization_id,
            team_id,
            workspace_id,
            project_id,
            task_id,
            task_execution_id,
            attempt,
            execution_workspace_id,
            workspace_fingerprint,
            coding_target_snapshot_id,
            coding_target_revision,
            coding_target_hash
        ) ON DELETE RESTRICT;

ALTER TABLE crewscope.coding_checkpoint
    ADD CONSTRAINT fk_coding_checkpoint_workspace
        FOREIGN KEY (
            organization_id,
            team_id,
            workspace_id,
            project_id,
            task_id,
            task_execution_id,
            attempt,
            execution_workspace_id,
            workspace_fingerprint,
            coding_target_snapshot_id,
            coding_target_revision,
            coding_target_hash
        ) REFERENCES crewscope.execution_workspace_epoch (
            organization_id,
            team_id,
            workspace_id,
            project_id,
            task_id,
            task_execution_id,
            attempt,
            execution_workspace_id,
            workspace_fingerprint,
            coding_target_snapshot_id,
            coding_target_revision,
            coding_target_hash
        ) ON DELETE RESTRICT;
