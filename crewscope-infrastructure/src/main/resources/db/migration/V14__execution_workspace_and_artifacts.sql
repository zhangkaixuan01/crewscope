-- M4 Coding execution facts. Tenant-owned roots repeat the complete WorkItem scope so PostgreSQL,
-- rather than repository convention, rejects cross-organization, cross-team and cross-project
-- relationships. Host repository and Worktree paths intentionally never enter this schema.

-- V14 children need the exact TaskExecution attempt and ExecutionLease ownership epoch. These
-- alternate keys add no new data and make the mirrored coordinates enforceable by foreign keys.
ALTER TABLE crewscope.task_execution
    ADD CONSTRAINT uk_task_execution_scope_attempt
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, id, attempt
        );

ALTER TABLE crewscope.execution_lease
    ADD CONSTRAINT uk_execution_lease_workspace_owner
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt,
            runtime_environment, runtime_id, worker_id, id, fencing_token
        );

-- Coding artifacts reuse the M3 ArtifactStore metadata row. V14 reserves stable kinds and exact
-- identity keys for Diff patches, command logs and test reports.
ALTER TABLE crewscope.runtime_artifact
    DROP CONSTRAINT ck_runtime_artifact_kind,
    ADD CONSTRAINT ck_runtime_artifact_kind CHECK (kind IN (
        'AGENT_STATE_SNAPSHOT', 'MODEL_RESULT', 'TOOL_RESULT',
        'EXECUTION_LOG', 'PLAN_DRAFT', 'TODO_SNAPSHOT',
        'DIFF_PATCH', 'COMMAND_LOG', 'TEST_REPORT'
    )),
    ADD CONSTRAINT uk_runtime_artifact_coding_identity
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, artifact_id,
            kind, size_bytes, content_hash
        ),
    ADD CONSTRAINT uk_runtime_artifact_evidence_identity
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, artifact_id,
            kind, content_type, size_bytes, content_hash
        );

CREATE TABLE crewscope.repository_binding (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    repository_kind VARCHAR(32) NOT NULL,
    repository_key VARCHAR(63) NOT NULL,
    default_branch VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_repository_binding_scope_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, id),
    CONSTRAINT uk_repository_binding_project_key
        UNIQUE (organization_id, team_id, workspace_id, project_id, repository_key),
    CONSTRAINT fk_repository_binding_project
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id)
        REFERENCES crewscope.work_project (organization_id, team_id, workspace_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_repository_binding_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_repository_binding_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_repository_binding_kind
        CHECK (repository_kind = 'LOCAL_MANAGED'),
    CONSTRAINT ck_repository_binding_key
        CHECK (repository_key ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    CONSTRAINT ck_repository_binding_branch CHECK (
        CHAR_LENGTH(default_branch) BETWEEN 1 AND 255
        AND default_branch !~ '[[:cntrl:] ~^:?*\[\\]'
        AND default_branch <> '@'
        AND default_branch !~ '(^-|^/|/$|\.$|^refs/|//|\.\.|@\{|(^|/)\.|\.lock($|/))'
    ),
    CONSTRAINT ck_repository_binding_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_repository_binding_version CHECK (version >= 0),
    CONSTRAINT ck_repository_binding_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_repository_binding_project_status
    ON crewscope.repository_binding (
        organization_id, team_id, workspace_id, project_id,
        status, updated_at DESC, id DESC
    );

CREATE TABLE crewscope.coding_target_snapshot (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_brief_hash CHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    parent_snapshot_id UUID,
    change_reason VARCHAR(32) NOT NULL,
    repository_binding_id UUID NOT NULL,
    repository_binding_version BIGINT NOT NULL,
    repository_kind VARCHAR(32) NOT NULL,
    repository_key VARCHAR(63) NOT NULL,
    baseline_ref VARCHAR(255) NOT NULL,
    baseline_commit CHAR(40) NOT NULL,
    allowed_paths JSONB NOT NULL,
    build_profile_key VARCHAR(128) NOT NULL,
    build_profile_version BIGINT NOT NULL,
    build_profile_hash CHAR(64) NOT NULL,
    acceptance_criteria JSONB NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_coding_target_snapshot_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, id
        ),
    CONSTRAINT uk_coding_target_snapshot_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, id, revision, snapshot_hash
        ),
    CONSTRAINT uk_coding_target_snapshot_revision UNIQUE (task_id, revision),
    CONSTRAINT fk_coding_target_snapshot_task
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id, task_id)
        REFERENCES crewscope.task (organization_id, team_id, workspace_id, project_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_coding_target_snapshot_parent
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, parent_snapshot_id
        ) REFERENCES crewscope.coding_target_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_coding_target_snapshot_binding
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            repository_binding_id
        ) REFERENCES crewscope.repository_binding (
            organization_id, team_id, workspace_id, project_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_coding_target_snapshot_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_coding_target_snapshot_revision CHECK (revision > 0),
    CONSTRAINT ck_coding_target_snapshot_parent_shape CHECK (
        (revision = 1 AND parent_snapshot_id IS NULL AND change_reason = 'TASK_CREATED')
        OR (revision > 1 AND parent_snapshot_id IS NOT NULL
            AND change_reason = 'RETRY_TARGET_UPDATED')
    ),
    CONSTRAINT ck_coding_target_snapshot_binding_version
        CHECK (repository_binding_version >= 0),
    CONSTRAINT ck_coding_target_snapshot_repository CHECK (
        repository_kind = 'LOCAL_MANAGED'
        AND repository_key ~ '^[a-z0-9][a-z0-9-]{0,62}$'
    ),
    CONSTRAINT ck_coding_target_snapshot_git CHECK (
        baseline_commit ~ '^[0-9a-f]{40}$'
        AND CHAR_LENGTH(baseline_ref) BETWEEN 1 AND 255
        AND baseline_ref !~ '[[:cntrl:] ~^:?*\[\\]'
        AND baseline_ref <> '@'
        AND baseline_ref !~ '(^-|^/|/$|\.$|^refs/|//|\.\.|@\{|(^|/)\.|\.lock($|/))'
    ),
    CONSTRAINT ck_coding_target_snapshot_paths CHECK (
        JSONB_TYPEOF(allowed_paths) = 'array'
        AND JSONB_ARRAY_LENGTH(allowed_paths) BETWEEN 1 AND 200
    ),
    CONSTRAINT ck_coding_target_snapshot_build_profile CHECK (
        build_profile_key ~ '^[a-z][a-z0-9._-]{0,127}$'
        AND build_profile_version > 0
    ),
    CONSTRAINT ck_coding_target_snapshot_acceptance CHECK (
        JSONB_TYPEOF(acceptance_criteria) = 'array'
        AND JSONB_ARRAY_LENGTH(acceptance_criteria) BETWEEN 1 AND 100
    ),
    CONSTRAINT ck_coding_target_snapshot_hashes CHECK (
        task_brief_hash ~ '^[0-9a-f]{64}$'
        AND build_profile_hash ~ '^[0-9a-f]{64}$'
        AND snapshot_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_coding_target_snapshot_task_revision
    ON crewscope.coding_target_snapshot (
        organization_id, team_id, workspace_id, project_id,
        task_id, revision DESC
    );

CREATE TABLE crewscope.execution_workspace (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    coding_target_snapshot_id UUID NOT NULL,
    coding_target_revision BIGINT NOT NULL,
    coding_target_hash CHAR(64) NOT NULL,
    repository_binding_id UUID NOT NULL,
    repository_binding_version BIGINT NOT NULL,
    repository_key VARCHAR(63) NOT NULL,
    baseline_commit CHAR(40) NOT NULL,
    workspace_key VARCHAR(48) NOT NULL,
    managed_branch VARCHAR(96) NOT NULL,
    archive_reference VARCHAR(128) NOT NULL,
    runtime_environment VARCHAR(64) NOT NULL,
    runtime_id UUID NOT NULL,
    worker_id UUID NOT NULL,
    execution_lease_id UUID NOT NULL,
    fencing_token BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    recovery_target_status VARCHAR(16),
    recovery_generation BIGINT NOT NULL DEFAULT 0,
    completion_reason VARCHAR(16),
    failure_code VARCHAR(64),
    retain_until TIMESTAMPTZ NOT NULL,
    workspace_fingerprint CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_execution_workspace_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_execution_workspace_attempt UNIQUE (task_execution_id, attempt),
    CONSTRAINT uk_execution_workspace_key UNIQUE (workspace_key),
    CONSTRAINT uk_execution_workspace_branch UNIQUE (repository_key, managed_branch),
    CONSTRAINT uk_execution_workspace_archive UNIQUE (archive_reference),
    CONSTRAINT uk_execution_workspace_fingerprint
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, workspace_fingerprint,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash
        ),
    CONSTRAINT fk_execution_workspace_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id,
            task_id, id, attempt
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_execution_workspace_target
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, coding_target_snapshot_id, coding_target_revision, coding_target_hash
        ) REFERENCES crewscope.coding_target_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, id, revision, snapshot_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_execution_workspace_binding
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            repository_binding_id
        ) REFERENCES crewscope.repository_binding (
            organization_id, team_id, workspace_id, project_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_execution_workspace_owner
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt,
            runtime_environment, runtime_id, worker_id, execution_lease_id, fencing_token
        ) REFERENCES crewscope.execution_lease (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt,
            runtime_environment, runtime_id, worker_id, id, fencing_token
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_execution_workspace_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_execution_workspace_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_execution_workspace_attempt CHECK (attempt BETWEEN 1 AND 100),
    CONSTRAINT ck_execution_workspace_binding CHECK (
        repository_binding_version >= 0
        AND repository_key ~ '^[a-z0-9][a-z0-9-]{0,62}$'
    ),
    CONSTRAINT ck_execution_workspace_commit
        CHECK (baseline_commit ~ '^[0-9a-f]{40}$'),
    CONSTRAINT ck_execution_workspace_keys CHECK (
        workspace_key ~ '^ws-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}-a([1-9]|[1-9][0-9]|100)$'
        AND managed_branch ~ '^crewscope/tasks/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/attempt-([1-9]|[1-9][0-9]|100)$'
        AND archive_reference = 'refs/crewscope/archives/' || workspace_key
    ),
    CONSTRAINT ck_execution_workspace_owner CHECK (
        runtime_environment ~ '^[a-z][a-z0-9._-]{0,63}$'
        AND fencing_token > 0
    ),
    CONSTRAINT ck_execution_workspace_status CHECK (status IN (
        'PENDING', 'PROVISIONING', 'READY', 'ACTIVE', 'FINALIZING',
        'COMPLETED', 'RECOVERING', 'FAILED', 'ARCHIVED'
    )),
    CONSTRAINT ck_execution_workspace_recovery CHECK (
        (status = 'RECOVERING'
            AND recovery_generation > 0
            AND recovery_target_status IN ('PROVISIONING', 'READY', 'ACTIVE', 'FINALIZING'))
        OR (status <> 'RECOVERING' AND recovery_target_status IS NULL)
    ),
    CONSTRAINT ck_execution_workspace_terminal_shape CHECK (
        (status IN ('FINALIZING', 'COMPLETED')
            AND completion_reason IN ('SUCCEEDED', 'CANCELLED')
            AND failure_code IS NULL)
        OR (status = 'FAILED'
            AND completion_reason IS NULL
            AND failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
        OR (status = 'ARCHIVED'
            AND ((completion_reason IN ('SUCCEEDED', 'CANCELLED') AND failure_code IS NULL)
                OR (completion_reason IS NULL
                    AND failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$')))
        OR (status NOT IN ('FINALIZING', 'COMPLETED', 'FAILED', 'ARCHIVED')
            AND completion_reason IS NULL AND failure_code IS NULL)
    ),
    CONSTRAINT ck_execution_workspace_hashes CHECK (
        coding_target_hash ~ '^[0-9a-f]{64}$'
        AND workspace_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_execution_workspace_version CHECK (
        recovery_generation >= 0 AND version >= 0
    ),
    CONSTRAINT ck_execution_workspace_retention CHECK (retain_until > created_at),
    CONSTRAINT ck_execution_workspace_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_execution_workspace_task_execution
    ON crewscope.execution_workspace (
        organization_id, team_id, workspace_id, project_id,
        task_execution_id, attempt
    );

CREATE INDEX ix_execution_workspace_worker_status
    ON crewscope.execution_workspace (
        organization_id, runtime_environment, runtime_id, worker_id,
        status, updated_at
    ) WHERE status NOT IN ('COMPLETED', 'FAILED', 'ARCHIVED');

CREATE INDEX ix_execution_workspace_retention
    ON crewscope.execution_workspace (status, retain_until, id)
    WHERE status IN ('COMPLETED', 'FAILED');

-- WorkspacePolicy is immutable; runtime tightening is append-only in WorkspacePolicyOverlay.
-- Command catalogs remain JSON value collections because BuildProfile is an application-owned
-- catalog, while hashes and exact PolicySnapshot/Target coordinates remain relational facts.
CREATE TABLE crewscope.workspace_policy (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    coding_target_snapshot_id UUID NOT NULL,
    coding_target_revision BIGINT NOT NULL,
    coding_target_hash CHAR(64) NOT NULL,
    policy_snapshot_id UUID NOT NULL,
    policy_snapshot_hash CHAR(64) NOT NULL,
    allowed_paths JSONB NOT NULL,
    build_profile_key VARCHAR(128) NOT NULL,
    build_profile_version BIGINT NOT NULL,
    build_profile_hash CHAR(64) NOT NULL,
    command_catalog JSONB NOT NULL,
    sandbox_network_mode VARCHAR(32) NOT NULL,
    sandbox_cpu_count INTEGER NOT NULL,
    sandbox_memory_mib INTEGER NOT NULL,
    sandbox_pids INTEGER NOT NULL,
    sandbox_max_command_duration_seconds INTEGER NOT NULL,
    sandbox_max_command_output_bytes BIGINT NOT NULL,
    sandbox_read_only_root_filesystem BOOLEAN NOT NULL,
    max_command_calls INTEGER NOT NULL,
    max_changed_files INTEGER NOT NULL,
    max_single_file_bytes BIGINT NOT NULL,
    max_write_operations INTEGER NOT NULL,
    max_written_bytes BIGINT NOT NULL,
    max_diff_bytes BIGINT NOT NULL,
    max_test_repair_rounds INTEGER NOT NULL,
    policy_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_workspace_policy_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_workspace_policy_execution UNIQUE (task_execution_id),
    CONSTRAINT uk_workspace_policy_hash
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, policy_hash
        ),
    CONSTRAINT uk_workspace_policy_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, policy_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash
        ),
    CONSTRAINT fk_workspace_policy_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id,
            task_id, id, attempt
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_workspace_policy_target
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, coding_target_snapshot_id, coding_target_revision, coding_target_hash
        ) REFERENCES crewscope.coding_target_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, id, revision, snapshot_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_workspace_policy_snapshot
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, policy_snapshot_id
        ) REFERENCES crewscope.policy_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_workspace_policy_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_workspace_policy_attempt CHECK (attempt BETWEEN 1 AND 100),
    CONSTRAINT ck_workspace_policy_paths CHECK (
        JSONB_TYPEOF(allowed_paths) = 'array'
        AND JSONB_ARRAY_LENGTH(allowed_paths) BETWEEN 1 AND 200
    ),
    CONSTRAINT ck_workspace_policy_build_profile CHECK (
        build_profile_key ~ '^[a-z][a-z0-9._-]{0,127}$'
        AND build_profile_version > 0
    ),
    CONSTRAINT ck_workspace_policy_catalog CHECK (
        JSONB_TYPEOF(command_catalog) = 'object'
        AND command_catalog <> '{}'::JSONB
    ),
    CONSTRAINT ck_workspace_policy_sandbox CHECK (
        sandbox_network_mode = 'NONE'
        AND sandbox_cpu_count >= 1
        AND sandbox_memory_mib >= 64
        AND sandbox_pids >= 1
        AND sandbox_max_command_duration_seconds BETWEEN 1 AND 3600
        AND sandbox_max_command_output_bytes > 0
        AND sandbox_read_only_root_filesystem
    ),
    CONSTRAINT ck_workspace_policy_operations CHECK (
        max_command_calls > 0
        AND max_changed_files > 0
        AND max_single_file_bytes > 0
        AND max_write_operations > 0
        AND max_written_bytes > 0
        AND max_diff_bytes > 0
        AND max_test_repair_rounds >= 0
        AND max_single_file_bytes <= max_written_bytes
    ),
    CONSTRAINT ck_workspace_policy_hashes CHECK (
        coding_target_hash ~ '^[0-9a-f]{64}$'
        AND policy_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND build_profile_hash ~ '^[0-9a-f]{64}$'
        AND policy_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_workspace_policy_task_execution
    ON crewscope.workspace_policy (
        organization_id, team_id, workspace_id, project_id, task_execution_id
    );

CREATE TABLE crewscope.workspace_policy_overlay (
    id UUID NOT NULL,
    overlay_version BIGINT NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    workspace_policy_id UUID NOT NULL,
    workspace_policy_hash CHAR(64) NOT NULL,
    parent_overlay_hash CHAR(64),
    allowed_paths JSONB NOT NULL,
    command_catalog JSONB NOT NULL,
    sandbox_network_mode VARCHAR(32) NOT NULL,
    sandbox_cpu_count INTEGER NOT NULL,
    sandbox_memory_mib INTEGER NOT NULL,
    sandbox_pids INTEGER NOT NULL,
    sandbox_max_command_duration_seconds INTEGER NOT NULL,
    sandbox_max_command_output_bytes BIGINT NOT NULL,
    sandbox_read_only_root_filesystem BOOLEAN NOT NULL,
    max_command_calls INTEGER NOT NULL,
    max_changed_files INTEGER NOT NULL,
    max_single_file_bytes BIGINT NOT NULL,
    max_write_operations INTEGER NOT NULL,
    max_written_bytes BIGINT NOT NULL,
    max_diff_bytes BIGINT NOT NULL,
    max_test_repair_rounds INTEGER NOT NULL,
    overlay_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    PRIMARY KEY (id, overlay_version),
    CONSTRAINT uk_workspace_policy_overlay_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, overlay_version
        ),
    CONSTRAINT uk_workspace_policy_overlay_version
        UNIQUE (workspace_policy_id, overlay_version),
    CONSTRAINT uk_workspace_policy_overlay_hash
        UNIQUE (workspace_policy_id, id, overlay_hash),
    CONSTRAINT fk_workspace_policy_overlay_policy
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt,
            workspace_policy_id, workspace_policy_hash
        ) REFERENCES crewscope.workspace_policy (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, policy_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_workspace_policy_overlay_parent
        FOREIGN KEY (workspace_policy_id, id, parent_overlay_hash)
        REFERENCES crewscope.workspace_policy_overlay (
            workspace_policy_id, id, overlay_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_workspace_policy_overlay_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_workspace_policy_overlay_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_workspace_policy_overlay_version CHECK (
        overlay_version > 0
        AND ((overlay_version = 1 AND parent_overlay_hash IS NULL)
            OR (overlay_version > 1 AND parent_overlay_hash ~ '^[0-9a-f]{64}$'))
    ),
    CONSTRAINT ck_workspace_policy_overlay_paths CHECK (
        JSONB_TYPEOF(allowed_paths) = 'array'
        AND JSONB_ARRAY_LENGTH(allowed_paths) BETWEEN 1 AND 200
    ),
    CONSTRAINT ck_workspace_policy_overlay_catalog CHECK (
        JSONB_TYPEOF(command_catalog) = 'object'
        AND command_catalog <> '{}'::JSONB
    ),
    CONSTRAINT ck_workspace_policy_overlay_sandbox CHECK (
        sandbox_network_mode = 'NONE'
        AND sandbox_cpu_count >= 1
        AND sandbox_memory_mib >= 64
        AND sandbox_pids >= 1
        AND sandbox_max_command_duration_seconds BETWEEN 1 AND 3600
        AND sandbox_max_command_output_bytes > 0
        AND sandbox_read_only_root_filesystem
    ),
    CONSTRAINT ck_workspace_policy_overlay_operations CHECK (
        max_command_calls > 0
        AND max_changed_files > 0
        AND max_single_file_bytes > 0
        AND max_write_operations > 0
        AND max_written_bytes > 0
        AND max_diff_bytes > 0
        AND max_test_repair_rounds >= 0
        AND max_single_file_bytes <= max_written_bytes
    ),
    CONSTRAINT ck_workspace_policy_overlay_hashes CHECK (
        workspace_policy_hash ~ '^[0-9a-f]{64}$'
        AND overlay_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_workspace_policy_overlay_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_workspace_policy_overlay_current
    ON crewscope.workspace_policy_overlay (
        organization_id, team_id, workspace_id, project_id,
        workspace_policy_id, overlay_version DESC
    );

CREATE TABLE crewscope.diff_artifact (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    execution_workspace_id UUID NOT NULL,
    workspace_fingerprint CHAR(64) NOT NULL,
    coding_target_snapshot_id UUID NOT NULL,
    coding_target_revision BIGINT NOT NULL,
    coding_target_hash CHAR(64) NOT NULL,
    baseline_commit CHAR(40) NOT NULL,
    delivery_commit CHAR(40) NOT NULL,
    diff_generation BIGINT NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    file_count INTEGER NOT NULL,
    additions BIGINT NOT NULL,
    deletions BIGINT NOT NULL,
    patch_artifact_id UUID NOT NULL,
    patch_artifact_kind VARCHAR(32) NOT NULL DEFAULT 'DIFF_PATCH',
    patch_size_bytes BIGINT NOT NULL,
    patch_sha256 CHAR(64) NOT NULL,
    final_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_diff_artifact_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_diff_artifact_workspace UNIQUE (execution_workspace_id),
    CONSTRAINT uk_diff_artifact_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, final_hash,
            execution_workspace_id, diff_generation, manifest_hash
        ),
    CONSTRAINT fk_diff_artifact_workspace
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, execution_workspace_id,
            workspace_fingerprint,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash
        ) REFERENCES crewscope.execution_workspace (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, workspace_fingerprint,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_diff_artifact_patch
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, patch_artifact_id,
            patch_artifact_kind, patch_size_bytes, patch_sha256
        ) REFERENCES crewscope.runtime_artifact (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, artifact_id,
            kind, size_bytes, content_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_diff_artifact_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_diff_artifact_attempt CHECK (attempt BETWEEN 1 AND 100),
    CONSTRAINT ck_diff_artifact_commits CHECK (
        baseline_commit ~ '^[0-9a-f]{40}$'
        AND delivery_commit ~ '^[0-9a-f]{40}$'
    ),
    CONSTRAINT ck_diff_artifact_generation CHECK (diff_generation > 0),
    CONSTRAINT ck_diff_artifact_statistics CHECK (
        file_count BETWEEN 0 AND 10000
        AND additions >= 0 AND deletions >= 0
    ),
    CONSTRAINT ck_diff_artifact_patch CHECK (
        patch_artifact_kind = 'DIFF_PATCH'
        AND patch_size_bytes >= 0
        AND ((file_count = 0) = (patch_size_bytes = 0))
    ),
    CONSTRAINT ck_diff_artifact_hashes CHECK (
        workspace_fingerprint ~ '^[0-9a-f]{64}$'
        AND coding_target_hash ~ '^[0-9a-f]{64}$'
        AND manifest_hash ~ '^[0-9a-f]{64}$'
        AND patch_sha256 ~ '^[0-9a-f]{64}$'
        AND final_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_diff_artifact_task_execution
    ON crewscope.diff_artifact (
        organization_id, team_id, workspace_id, project_id,
        task_execution_id, created_at DESC, id DESC
    );

CREATE TABLE crewscope.diff_file_entry (
    diff_artifact_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    path VARCHAR(1024) NOT NULL,
    old_path VARCHAR(1024),
    change_kind VARCHAR(16) NOT NULL,
    additions BIGINT NOT NULL,
    deletions BIGINT NOT NULL,
    is_binary BOOLEAN NOT NULL,
    patch_truncated BOOLEAN NOT NULL,
    patch_sha256 CHAR(64) NOT NULL,
    patch_preview TEXT,
    PRIMARY KEY (diff_artifact_id, ordinal),
    CONSTRAINT uk_diff_file_entry_path UNIQUE (diff_artifact_id, path),
    CONSTRAINT fk_diff_file_entry_artifact
        FOREIGN KEY (diff_artifact_id) REFERENCES crewscope.diff_artifact (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_diff_file_entry_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_diff_file_entry_paths CHECK (
        CHAR_LENGTH(path) BETWEEN 1 AND 1024
        AND path !~ '(^/|(^|/)\.\.(/|$)|\\|[[:cntrl:]])'
        AND (old_path IS NULL OR (
            CHAR_LENGTH(old_path) BETWEEN 1 AND 1024
            AND old_path <> path
            AND old_path !~ '(^/|(^|/)\.\.(/|$)|\\|[[:cntrl:]])'
        ))
    ),
    CONSTRAINT ck_diff_file_entry_kind CHECK (
        change_kind IN ('ADDED', 'MODIFIED', 'DELETED', 'RENAMED', 'COPIED', 'TYPE_CHANGED')
    ),
    CONSTRAINT ck_diff_file_entry_old_path CHECK (
        (change_kind IN ('RENAMED', 'COPIED') AND old_path IS NOT NULL)
        OR (change_kind NOT IN ('RENAMED', 'COPIED') AND old_path IS NULL)
    ),
    CONSTRAINT ck_diff_file_entry_statistics CHECK (
        additions >= 0 AND deletions >= 0
        AND (NOT is_binary OR (additions = 0 AND deletions = 0))
    ),
    CONSTRAINT ck_diff_file_entry_preview CHECK (
        (NOT is_binary OR (patch_truncated AND patch_preview IS NULL))
        AND (patch_preview IS NULL OR (
            OCTET_LENGTH(patch_preview) <= 262144
        ))
    ),
    CONSTRAINT ck_diff_file_entry_hash CHECK (patch_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE crewscope.command_evidence (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    execution_workspace_id UUID NOT NULL,
    workspace_fingerprint CHAR(64) NOT NULL,
    coding_target_snapshot_id UUID NOT NULL,
    coding_target_revision BIGINT NOT NULL,
    coding_target_hash CHAR(64) NOT NULL,
    evidence_sequence BIGINT NOT NULL,
    workspace_policy_id UUID NOT NULL,
    workspace_policy_hash CHAR(64) NOT NULL,
    build_profile_key VARCHAR(128) NOT NULL,
    build_profile_version BIGINT NOT NULL,
    build_profile_hash CHAR(64) NOT NULL,
    command_kind VARCHAR(32) NOT NULL,
    tool_key VARCHAR(128) NOT NULL,
    argv JSONB NOT NULL,
    working_directory VARCHAR(1024) NOT NULL,
    timeout_seconds INTEGER NOT NULL,
    sandbox_image VARCHAR(1024) NOT NULL,
    command_spec_hash CHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    termination VARCHAR(32) NOT NULL,
    exit_code INTEGER,
    summary TEXT NOT NULL,
    command_log_artifact_id UUID NOT NULL,
    command_log_kind VARCHAR(32) NOT NULL DEFAULT 'COMMAND_LOG',
    command_log_content_type VARCHAR(255) NOT NULL,
    command_log_size_bytes BIGINT NOT NULL,
    command_log_content_hash CHAR(64) NOT NULL,
    failure_classification VARCHAR(64),
    evidence_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_command_evidence_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_command_evidence_workspace_sequence
        UNIQUE (execution_workspace_id, evidence_sequence),
    CONSTRAINT uk_command_evidence_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, evidence_sequence, evidence_hash
        ),
    CONSTRAINT fk_command_evidence_workspace
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, execution_workspace_id,
            workspace_fingerprint,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash
        ) REFERENCES crewscope.execution_workspace (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, workspace_fingerprint,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_command_evidence_policy
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt,
            workspace_policy_id, workspace_policy_hash
        ) REFERENCES crewscope.workspace_policy (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, policy_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_command_evidence_log
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, command_log_artifact_id,
            command_log_kind, command_log_content_type,
            command_log_size_bytes, command_log_content_hash
        ) REFERENCES crewscope.runtime_artifact (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, artifact_id,
            kind, content_type, size_bytes, content_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_command_evidence_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_command_evidence_sequence CHECK (
        attempt BETWEEN 1 AND 100 AND evidence_sequence > 0
    ),
    CONSTRAINT ck_command_evidence_profile CHECK (
        build_profile_key ~ '^[a-z][a-z0-9._-]{0,127}$'
        AND build_profile_version > 0
    ),
    CONSTRAINT ck_command_evidence_kind CHECK (
        command_kind IN ('COMPILE', 'TEST', 'VERIFY', 'FORMAT_CHECK', 'ACCEPTANCE')
    ),
    CONSTRAINT ck_command_evidence_command CHECK (
        tool_key ~ '^[a-z][A-Za-z0-9._-]{0,127}$'
        AND JSONB_TYPEOF(argv) = 'array'
        AND JSONB_ARRAY_LENGTH(argv) BETWEEN 1 AND 128
        AND CHAR_LENGTH(working_directory) BETWEEN 1 AND 1024
        AND working_directory !~ '(^/|(^|/)\.\.(/|$)|\\|[[:cntrl:]])'
        AND timeout_seconds BETWEEN 1 AND 3600
        AND sandbox_image ~ '^[^[:space:]@]+@sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_command_evidence_termination CHECK (
        termination IN (
            'EXITED', 'TIMED_OUT', 'START_FAILED', 'OUTPUT_LIMIT_EXCEEDED',
            'SANDBOX_POLICY_VIOLATION', 'CANCELLED'
        )
        AND ((termination = 'EXITED' AND exit_code IS NOT NULL)
            OR (termination <> 'EXITED' AND exit_code IS NULL))
    ),
    CONSTRAINT ck_command_evidence_failure CHECK (
        (termination = 'EXITED' AND exit_code = 0 AND failure_classification IS NULL)
        OR (termination = 'EXITED' AND exit_code <> 0
            AND failure_classification = 'COMMAND_NON_ZERO_EXIT')
        OR (termination = 'TIMED_OUT'
            AND failure_classification = 'COMMAND_TIMED_OUT')
        OR (termination = 'START_FAILED'
            AND failure_classification = 'COMMAND_START_FAILED')
        OR (termination = 'OUTPUT_LIMIT_EXCEEDED'
            AND failure_classification = 'COMMAND_OUTPUT_LIMIT_EXCEEDED')
        OR (termination = 'SANDBOX_POLICY_VIOLATION'
            AND failure_classification = 'COMMAND_SANDBOX_POLICY_VIOLATION')
        OR (termination = 'CANCELLED'
            AND failure_classification = 'COMMAND_CANCELLED')
    ),
    CONSTRAINT ck_command_evidence_log CHECK (
        command_log_kind = 'COMMAND_LOG'
        AND command_log_content_type = LOWER(command_log_content_type)
        AND BTRIM(command_log_content_type) <> ''
        AND command_log_size_bytes >= 0
    ),
    CONSTRAINT ck_command_evidence_summary CHECK (
        OCTET_LENGTH(summary) BETWEEN 1 AND 4096
        AND BTRIM(summary) <> ''
    ),
    CONSTRAINT ck_command_evidence_times CHECK (
        started_at <= finished_at AND finished_at <= created_at
    ),
    CONSTRAINT ck_command_evidence_hashes CHECK (
        workspace_fingerprint ~ '^[0-9a-f]{64}$'
        AND coding_target_hash ~ '^[0-9a-f]{64}$'
        AND workspace_policy_hash ~ '^[0-9a-f]{64}$'
        AND build_profile_hash ~ '^[0-9a-f]{64}$'
        AND command_spec_hash ~ '^[0-9a-f]{64}$'
        AND command_log_content_hash ~ '^[0-9a-f]{64}$'
        AND evidence_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_command_evidence_task_execution
    ON crewscope.command_evidence (
        organization_id, team_id, workspace_id, project_id,
        task_execution_id, evidence_sequence
    );

CREATE TABLE crewscope.test_evidence (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    execution_workspace_id UUID NOT NULL,
    workspace_fingerprint CHAR(64) NOT NULL,
    coding_target_snapshot_id UUID NOT NULL,
    coding_target_revision BIGINT NOT NULL,
    coding_target_hash CHAR(64) NOT NULL,
    diff_generation BIGINT NOT NULL,
    diff_manifest_hash CHAR(64) NOT NULL,
    evidence_sequence BIGINT NOT NULL,
    workspace_policy_id UUID NOT NULL,
    workspace_policy_hash CHAR(64) NOT NULL,
    test_total BIGINT NOT NULL,
    test_passed BIGINT NOT NULL,
    test_failed BIGINT NOT NULL,
    test_errors BIGINT NOT NULL,
    test_skipped BIGINT NOT NULL,
    test_report_artifact_id UUID,
    test_report_kind VARCHAR(32),
    test_report_content_type VARCHAR(255),
    test_report_size_bytes BIGINT,
    test_report_content_hash CHAR(64),
    summary TEXT NOT NULL,
    failure_classification VARCHAR(64),
    evidence_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_test_evidence_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_test_evidence_workspace_sequence
        UNIQUE (execution_workspace_id, evidence_sequence),
    CONSTRAINT uk_test_evidence_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, execution_workspace_id,
            id, evidence_hash, diff_generation, diff_manifest_hash
        ),
    CONSTRAINT fk_test_evidence_workspace
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, execution_workspace_id,
            workspace_fingerprint,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash
        ) REFERENCES crewscope.execution_workspace (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, workspace_fingerprint,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_test_evidence_policy
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt,
            workspace_policy_id, workspace_policy_hash
        ) REFERENCES crewscope.workspace_policy (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, policy_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_test_evidence_report
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, test_report_artifact_id,
            test_report_kind, test_report_content_type,
            test_report_size_bytes, test_report_content_hash
        ) REFERENCES crewscope.runtime_artifact (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, artifact_id,
            kind, content_type, size_bytes, content_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_test_evidence_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_test_evidence_sequence CHECK (
        attempt BETWEEN 1 AND 100
        AND diff_generation > 0
        AND evidence_sequence > 0
    ),
    CONSTRAINT ck_test_evidence_statistics CHECK (
        test_total >= 0 AND test_passed >= 0 AND test_failed >= 0
        AND test_errors >= 0 AND test_skipped >= 0
        AND test_total = test_passed + test_failed + test_errors + test_skipped
    ),
    CONSTRAINT ck_test_evidence_report CHECK (
        (test_report_artifact_id IS NULL
            AND test_report_kind IS NULL
            AND test_report_content_type IS NULL
            AND test_report_size_bytes IS NULL
            AND test_report_content_hash IS NULL)
        OR (test_report_artifact_id IS NOT NULL
            AND test_report_kind = 'TEST_REPORT'
            AND test_report_content_type = LOWER(test_report_content_type)
            AND BTRIM(test_report_content_type) <> ''
            AND test_report_size_bytes >= 0
            AND test_report_content_hash ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_test_evidence_failure CHECK (
        failure_classification IS NULL OR failure_classification IN (
            'COMMAND_START_FAILED', 'COMMAND_TIMED_OUT',
            'COMMAND_OUTPUT_LIMIT_EXCEEDED', 'COMMAND_SANDBOX_POLICY_VIOLATION',
            'COMMAND_CANCELLED', 'COMMAND_NON_ZERO_EXIT', 'TEST_REPORT_MISSING',
            'NO_TESTS_EXECUTED', 'TESTS_FAILED', 'ACCEPTANCE_INCOMPLETE',
            'ACCEPTANCE_FAILED'
        )
    ),
    CONSTRAINT ck_test_evidence_summary CHECK (
        OCTET_LENGTH(summary) BETWEEN 1 AND 4096
        AND BTRIM(summary) <> ''
    ),
    CONSTRAINT ck_test_evidence_hashes CHECK (
        workspace_fingerprint ~ '^[0-9a-f]{64}$'
        AND coding_target_hash ~ '^[0-9a-f]{64}$'
        AND diff_manifest_hash ~ '^[0-9a-f]{64}$'
        AND workspace_policy_hash ~ '^[0-9a-f]{64}$'
        AND evidence_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_test_evidence_task_execution
    ON crewscope.test_evidence (
        organization_id, team_id, workspace_id, project_id,
        task_execution_id, evidence_sequence
    );

-- Ordered references are relational instead of opaque JSON so every TestEvidence can prove that
-- all command and acceptance evidence belongs to the same complete TaskExecution scope.
CREATE TABLE crewscope.test_evidence_command (
    test_evidence_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    command_evidence_id UUID NOT NULL,
    PRIMARY KEY (test_evidence_id, ordinal),
    CONSTRAINT uk_test_evidence_command_reference
        UNIQUE (test_evidence_id, command_evidence_id),
    CONSTRAINT fk_test_evidence_command_parent
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, test_evidence_id
        ) REFERENCES crewscope.test_evidence (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE CASCADE,
    CONSTRAINT fk_test_evidence_command_evidence
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, command_evidence_id
        ) REFERENCES crewscope.command_evidence (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_test_evidence_command_ordinal CHECK (ordinal >= 0)
);

CREATE TABLE crewscope.test_acceptance_result (
    test_evidence_id UUID NOT NULL,
    criterion_index INTEGER NOT NULL,
    criterion TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary TEXT NOT NULL,
    PRIMARY KEY (test_evidence_id, criterion_index),
    CONSTRAINT fk_test_acceptance_result_parent
        FOREIGN KEY (test_evidence_id) REFERENCES crewscope.test_evidence (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_test_acceptance_result_index CHECK (criterion_index > 0),
    CONSTRAINT ck_test_acceptance_result_criterion CHECK (
        CHAR_LENGTH(BTRIM(criterion)) BETWEEN 1 AND 2000
    ),
    CONSTRAINT ck_test_acceptance_result_status
        CHECK (status IN ('PASSED', 'FAILED', 'NOT_EVALUATED')),
    CONSTRAINT ck_test_acceptance_result_summary CHECK (
        OCTET_LENGTH(summary) BETWEEN 1 AND 4096
        AND BTRIM(summary) <> ''
    )
);

CREATE TABLE crewscope.test_acceptance_evidence (
    test_evidence_id UUID NOT NULL,
    criterion_index INTEGER NOT NULL,
    ordinal INTEGER NOT NULL,
    command_evidence_id UUID NOT NULL,
    PRIMARY KEY (test_evidence_id, criterion_index, ordinal),
    CONSTRAINT uk_test_acceptance_evidence_command
        UNIQUE (test_evidence_id, criterion_index, command_evidence_id),
    CONSTRAINT fk_test_acceptance_evidence_result
        FOREIGN KEY (test_evidence_id, criterion_index)
        REFERENCES crewscope.test_acceptance_result (test_evidence_id, criterion_index)
        ON DELETE CASCADE,
    CONSTRAINT fk_test_acceptance_evidence_command
        FOREIGN KEY (test_evidence_id, command_evidence_id)
        REFERENCES crewscope.test_evidence_command (
            test_evidence_id, command_evidence_id
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_test_acceptance_evidence_ordinal CHECK (ordinal >= 0)
);

-- Checkpoint foreign keys bind the exact run, plan and snapshot revisions, not only their IDs.
ALTER TABLE crewscope.agent_run
    ADD CONSTRAINT uk_agent_run_checkpoint_identity
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, run_sequence
        );

ALTER TABLE crewscope.plan_version
    ADD CONSTRAINT uk_plan_version_checkpoint_identity
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, version_hash
        );

ALTER TABLE crewscope.agent_state_snapshot
    ADD CONSTRAINT uk_agent_state_snapshot_checkpoint_identity
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, agent_run_id, id,
            snapshot_sequence, content_hash, checkpoint_sequence
        );

CREATE TABLE crewscope.coding_checkpoint (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    coding_target_snapshot_id UUID NOT NULL,
    coding_target_revision BIGINT NOT NULL,
    coding_target_hash CHAR(64) NOT NULL,
    execution_workspace_id UUID NOT NULL,
    workspace_fingerprint CHAR(64) NOT NULL,
    workspace_policy_id UUID NOT NULL,
    workspace_policy_hash CHAR(64) NOT NULL,
    agent_run_id UUID NOT NULL,
    agent_run_sequence BIGINT NOT NULL,
    segment_sequence BIGINT NOT NULL,
    plan_version_id UUID,
    plan_version_hash CHAR(64),
    step_execution_id UUID,
    plan_markdown TEXT NOT NULL,
    todos JSONB NOT NULL,
    work_state_hash CHAR(64) NOT NULL,
    diff_generation BIGINT NOT NULL,
    diff_manifest_hash CHAR(64) NOT NULL,
    test_evidence_id UUID,
    test_evidence_hash CHAR(64),
    agent_state_snapshot_id UUID NOT NULL,
    snapshot_sequence BIGINT NOT NULL,
    snapshot_content_hash CHAR(64) NOT NULL,
    checkpoint_sequence BIGINT NOT NULL,
    checkpoint_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_coding_checkpoint_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_coding_checkpoint_workspace_sequence
        UNIQUE (execution_workspace_id, checkpoint_sequence),
    CONSTRAINT fk_coding_checkpoint_workspace
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, execution_workspace_id,
            workspace_fingerprint,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash
        ) REFERENCES crewscope.execution_workspace (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, workspace_fingerprint,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_coding_checkpoint_policy
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt,
            workspace_policy_id, workspace_policy_hash
        ) REFERENCES crewscope.workspace_policy (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, policy_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_coding_checkpoint_run
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, agent_run_id, agent_run_sequence
        ) REFERENCES crewscope.agent_run (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, run_sequence
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_coding_checkpoint_segment
        FOREIGN KEY (agent_run_id, segment_sequence)
        REFERENCES crewscope.agent_run_segment (agent_run_id, sequence)
        ON DELETE RESTRICT,
    CONSTRAINT fk_coding_checkpoint_plan
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, plan_version_id, plan_version_hash
        ) REFERENCES crewscope.plan_version (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, version_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_coding_checkpoint_step
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, step_execution_id
        ) REFERENCES crewscope.step_execution (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_coding_checkpoint_evidence
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, execution_workspace_id,
            test_evidence_id, test_evidence_hash,
            diff_generation, diff_manifest_hash
        ) REFERENCES crewscope.test_evidence (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, execution_workspace_id,
            id, evidence_hash, diff_generation, diff_manifest_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_coding_checkpoint_snapshot
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, agent_run_id, agent_state_snapshot_id,
            snapshot_sequence, snapshot_content_hash, checkpoint_sequence
        ) REFERENCES crewscope.agent_state_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, agent_run_id, id,
            snapshot_sequence, content_hash, checkpoint_sequence
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_coding_checkpoint_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_coding_checkpoint_sequences CHECK (
        attempt BETWEEN 1 AND 100
        AND agent_run_sequence > 0
        AND segment_sequence > 0
        AND diff_generation > 0
        AND snapshot_sequence > 0
        AND checkpoint_sequence > 0
    ),
    CONSTRAINT ck_coding_checkpoint_plan_shape CHECK (
        (plan_version_id IS NULL) = (plan_version_hash IS NULL)
    ),
    CONSTRAINT ck_coding_checkpoint_evidence_shape CHECK (
        (test_evidence_id IS NULL) = (test_evidence_hash IS NULL)
    ),
    CONSTRAINT ck_coding_checkpoint_work_state CHECK (
        CHAR_LENGTH(BTRIM(plan_markdown)) BETWEEN 1 AND 50000
        AND JSONB_TYPEOF(todos) = 'array'
        AND JSONB_ARRAY_LENGTH(todos) <= 200
    ),
    CONSTRAINT ck_coding_checkpoint_hashes CHECK (
        coding_target_hash ~ '^[0-9a-f]{64}$'
        AND workspace_fingerprint ~ '^[0-9a-f]{64}$'
        AND workspace_policy_hash ~ '^[0-9a-f]{64}$'
        AND (plan_version_hash IS NULL OR plan_version_hash ~ '^[0-9a-f]{64}$')
        AND work_state_hash ~ '^[0-9a-f]{64}$'
        AND diff_manifest_hash ~ '^[0-9a-f]{64}$'
        AND (test_evidence_hash IS NULL OR test_evidence_hash ~ '^[0-9a-f]{64}$')
        AND snapshot_content_hash ~ '^[0-9a-f]{64}$'
        AND checkpoint_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_coding_checkpoint_recovery
    ON crewscope.coding_checkpoint (
        organization_id, team_id, workspace_id, project_id,
        task_execution_id, checkpoint_sequence DESC, id DESC
    );

CREATE INDEX ix_coding_checkpoint_run
    ON crewscope.coding_checkpoint (agent_run_id, checkpoint_sequence DESC);
