-- M5 Review, approved external Action and GitHub delivery persistence.
-- Complex authority snapshots remain inspectable JSON, while every coordinate used for
-- authorization, conflict detection and reconciliation is also a typed, constrained column.

-- Exact historical coordinates used by V21 composite foreign keys.
ALTER TABLE crewscope.agent_profile
    ADD CONSTRAINT uk_agent_profile_review_reference
        UNIQUE (organization_id, id, version, agent_principal_id, template_key, template_version);

ALTER TABLE crewscope.provider_definition
    ADD CONSTRAINT uk_provider_definition_action_reference
        UNIQUE (organization_id, id, version);

ALTER TABLE crewscope.provider_implementation
    ADD CONSTRAINT uk_provider_implementation_action_reference
        UNIQUE (organization_id, id, version);

ALTER TABLE crewscope.connection
    ADD CONSTRAINT uk_connection_action_reference
        UNIQUE (organization_id, id, owner_type, owner_id, connector_key, version);

ALTER TABLE crewscope.connection
    ADD CONSTRAINT uk_connection_github_reference
        UNIQUE (organization_id, id, owner_type, owner_id, version);

ALTER TABLE crewscope.connection_grant
    ADD CONSTRAINT uk_connection_grant_action_reference
        UNIQUE (organization_id, id, connection_id, version);

ALTER TABLE crewscope.provider_binding
    ADD CONSTRAINT uk_provider_binding_action_reference
        UNIQUE (
            organization_id, team_id, workspace_id, id, version,
            provider_definition_id, provider_definition_version,
            provider_implementation_id, provider_implementation_version,
            connection_id, connection_version, connection_grant_id,
            connection_grant_version, execution_identity
        );

ALTER TABLE crewscope.responsibility_assignment
    ADD CONSTRAINT uk_responsibility_assignment_action_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            work_item_id, id, version, role, actor_principal_id
        );

ALTER TABLE crewscope.policy_snapshot
    ADD CONSTRAINT uk_policy_snapshot_review_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, revision, snapshot_hash
        );

ALTER TABLE crewscope.safety_enforcement_overlay
    ADD CONSTRAINT uk_safety_overlay_action_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, overlay_version, overlay_hash
        );

ALTER TABLE crewscope.repository_binding
    ADD CONSTRAINT uk_repository_binding_action_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            id, version, repository_key, default_branch
        );

ALTER TABLE crewscope.diff_artifact
    ADD CONSTRAINT uk_diff_artifact_review_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, final_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash,
            baseline_commit, delivery_commit, diff_generation, manifest_hash,
            patch_artifact_id, patch_size_bytes, patch_sha256
        );

ALTER TABLE crewscope.diff_artifact
    ADD CONSTRAINT uk_diff_artifact_context_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, final_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash,
            diff_generation, manifest_hash
        );

ALTER TABLE crewscope.diff_artifact
    ADD CONSTRAINT uk_diff_artifact_action_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, final_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash,
            baseline_commit, delivery_commit
        );

ALTER TABLE crewscope.test_evidence
    ADD CONSTRAINT uk_test_evidence_review_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, evidence_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash,
            diff_generation, diff_manifest_hash
        );

ALTER TABLE crewscope.command_evidence
    ADD CONSTRAINT uk_command_evidence_review_reference
        UNIQUE (id, evidence_sequence, evidence_hash);

ALTER TABLE crewscope.agent_configuration_version
    ADD CONSTRAINT uk_agent_configuration_review_reference
        UNIQUE (
            organization_id, agent_profile_id, configuration_revision,
            configuration_hash, template_key, template_version, template_content_hash
        );

-- GitHub-specific safe metadata. No token, secret, Provider payload or private endpoint belongs here.
CREATE TABLE crewscope.github_connection_profile (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    connection_version BIGINT NOT NULL,
    connection_owner_type VARCHAR(32) NOT NULL,
    connection_owner_id UUID NOT NULL,
    external_identity VARCHAR(64) NOT NULL,
    authentication_type VARCHAR(32) NOT NULL,
    external_account_id VARCHAR(100) NOT NULL,
    external_account_login VARCHAR(255) NOT NULL,
    granted_permissions JSONB NOT NULL,
    repository_allowlist_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_github_connection_profile_connection
        UNIQUE (organization_id, connection_id),
    CONSTRAINT uk_github_connection_profile_exact
        UNIQUE (organization_id, connection_id, connection_version, external_identity),
    CONSTRAINT uk_github_connection_profile_version
        UNIQUE (organization_id, connection_id, connection_version),
    CONSTRAINT fk_github_connection_profile_connection
        FOREIGN KEY (
            organization_id, connection_id, connection_owner_type,
            connection_owner_id, connection_version
        ) REFERENCES crewscope.connection (
            organization_id, id, owner_type, owner_id, version
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_github_connection_profile_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_github_connection_profile_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_github_connection_profile_identity CHECK (
        (connection_owner_type = 'TEAM'
            AND external_identity = 'TEAM_SERVICE_ACCOUNT'
            AND authentication_type = 'APP_INSTALLATION')
        OR (connection_owner_type = 'USER'
            AND external_identity = 'DELEGATED_USER'
            AND authentication_type = 'OAUTH_USER')
    ),
    CONSTRAINT ck_github_connection_profile_account CHECK (
        BTRIM(external_account_id) <> ''
        AND BTRIM(external_account_login) <> ''
        AND CHAR_LENGTH(external_account_login) <= 255
    ),
    CONSTRAINT ck_github_connection_profile_permissions CHECK (
        JSONB_TYPEOF(granted_permissions) = 'object'
        AND repository_allowlist_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_github_connection_profile_status CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')
    ),
    CONSTRAINT ck_github_connection_profile_version CHECK (
        connection_version >= 0 AND version >= 0 AND updated_at >= created_at
    )
);

CREATE INDEX ix_github_connection_profile_owner_status
    ON crewscope.github_connection_profile (
        organization_id, connection_owner_type, connection_owner_id,
        status, updated_at DESC, id
    );

CREATE TABLE crewscope.github_repository_catalog_entry (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    connection_version BIGINT NOT NULL,
    external_identity VARCHAR(64) NOT NULL,
    external_repository_id VARCHAR(100) NOT NULL,
    owner_login VARCHAR(255) NOT NULL,
    repository_name VARCHAR(255) NOT NULL,
    full_name VARCHAR(511) NOT NULL,
    default_branch VARCHAR(255) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    archived BOOLEAN NOT NULL,
    fork BOOLEAN NOT NULL,
    can_pull BOOLEAN NOT NULL,
    can_push BOOLEAN NOT NULL,
    can_create_pull_request BOOLEAN NOT NULL,
    permissions_hash CHAR(64) NOT NULL,
    etag_hash CHAR(64),
    discovered_at TIMESTAMPTZ NOT NULL,
    cache_expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_github_repository_external_id
        UNIQUE (connection_id, external_repository_id),
    CONSTRAINT uk_github_repository_full_name
        UNIQUE (connection_id, full_name),
    CONSTRAINT uk_github_repository_action_reference
        UNIQUE (organization_id, connection_id, external_repository_id),
    CONSTRAINT fk_github_repository_profile
        FOREIGN KEY (
            organization_id, connection_id, connection_version, external_identity
        ) REFERENCES crewscope.github_connection_profile (
            organization_id, connection_id, connection_version, external_identity
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_github_repository_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_github_repository_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_github_repository_names CHECK (
        BTRIM(external_repository_id) <> '' AND BTRIM(owner_login) <> ''
        AND BTRIM(repository_name) <> ''
        AND full_name = owner_login || '/' || repository_name
    ),
    CONSTRAINT ck_github_repository_branch CHECK (
        CHAR_LENGTH(default_branch) BETWEEN 1 AND 255
        AND default_branch !~ '[[:cntrl:] ~^:?*\[\\]'
        AND default_branch <> '@'
        AND default_branch !~ '(^-|^/|/$|\.$|^refs/|//|\.\.|@\{|(^|/)\.|\.lock($|/))'
    ),
    CONSTRAINT ck_github_repository_visibility CHECK (
        visibility IN ('PUBLIC', 'PRIVATE', 'INTERNAL')
    ),
    CONSTRAINT ck_github_repository_permissions CHECK (
        permissions_hash ~ '^[0-9a-f]{64}$'
        AND (etag_hash IS NULL OR etag_hash ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_github_repository_delivery CHECK (
        status <> 'DELIVERABLE'
        OR (NOT archived AND NOT fork AND can_pull AND can_push
            AND can_create_pull_request)
    ),
    CONSTRAINT ck_github_repository_status CHECK (
        status IN ('DISCOVERED', 'DELIVERABLE', 'BLOCKED', 'STALE')
    ),
    CONSTRAINT ck_github_repository_time CHECK (
        connection_version >= 0 AND version >= 0
        AND discovered_at <= cache_expires_at
        AND created_at <= updated_at
    )
);

CREATE INDEX ix_github_repository_catalog_delivery
    ON crewscope.github_repository_catalog_entry (
        organization_id, connection_id, status, full_name, id
    );

CREATE INDEX ix_github_repository_catalog_expiry
    ON crewscope.github_repository_catalog_entry (cache_expires_at, connection_id, id)
    WHERE status <> 'STALE';

CREATE TABLE crewscope.github_rate_limit_snapshot (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    connection_version BIGINT NOT NULL,
    resource VARCHAR(64) NOT NULL,
    rate_limit BIGINT NOT NULL,
    remaining BIGINT NOT NULL,
    used BIGINT NOT NULL,
    resets_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_github_rate_limit_observation
        UNIQUE (connection_id, resource, observed_at),
    CONSTRAINT fk_github_rate_limit_profile
        FOREIGN KEY (organization_id, connection_id)
        REFERENCES crewscope.github_connection_profile (organization_id, connection_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_github_rate_limit_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_github_rate_limit_values CHECK (
        connection_version >= 0 AND BTRIM(resource) <> ''
        AND rate_limit >= 0 AND remaining >= 0 AND used >= 0
        AND remaining <= rate_limit AND used <= rate_limit
        AND observed_at = created_at
    )
);

CREATE INDEX ix_github_rate_limit_current
    ON crewscope.github_rate_limit_snapshot (
        connection_id, resource, observed_at DESC, id DESC
    );

-- Immutable Review subject closed over the exact M4 Diff authority.
CREATE TABLE crewscope.review_subject (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    diff_artifact_id UUID NOT NULL,
    diff_final_hash CHAR(64) NOT NULL,
    coding_target_snapshot_id UUID NOT NULL,
    coding_target_revision BIGINT NOT NULL,
    coding_target_hash CHAR(64) NOT NULL,
    baseline_commit CHAR(40) NOT NULL,
    delivery_commit CHAR(40) NOT NULL,
    diff_generation BIGINT NOT NULL,
    diff_manifest_hash CHAR(64) NOT NULL,
    patch_artifact_id UUID NOT NULL,
    patch_size_bytes BIGINT NOT NULL,
    patch_sha256 CHAR(64) NOT NULL,
    changed_paths JSONB NOT NULL,
    subject_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_review_subject_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_review_subject_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, subject_type, subject_hash
        ),
    CONSTRAINT uk_review_subject_action_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, subject_hash
        ),
    CONSTRAINT fk_review_subject_execution
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id
        ) REFERENCES crewscope.task_execution (
            organization_id, team_id, workspace_id, project_id, task_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_subject_diff
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, diff_artifact_id, diff_final_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash,
            baseline_commit, delivery_commit, diff_generation, diff_manifest_hash,
            patch_artifact_id, patch_size_bytes, patch_sha256
        ) REFERENCES crewscope.diff_artifact (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, final_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash,
            baseline_commit, delivery_commit, diff_generation, manifest_hash,
            patch_artifact_id, patch_size_bytes, patch_sha256
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_subject_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_subject_type CHECK (subject_type = 'CODE_CHANGE'),
    CONSTRAINT ck_review_subject_shape CHECK (
        attempt BETWEEN 1 AND 100 AND diff_generation > 0 AND patch_size_bytes > 0
        AND JSONB_TYPEOF(changed_paths) = 'array'
        AND JSONB_ARRAY_LENGTH(changed_paths) BETWEEN 1 AND 10000
    ),
    CONSTRAINT ck_review_subject_hashes CHECK (
        subject_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_review_subject_execution
    ON crewscope.review_subject (
        organization_id, team_id, workspace_id, project_id,
        task_id, task_execution_id, created_at DESC, id DESC
    );

CREATE TABLE crewscope.review_context_package (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    package_version BIGINT NOT NULL,
    parent_package_id UUID,
    subject_id UUID NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_hash CHAR(64) NOT NULL,
    diff_artifact_id UUID NOT NULL,
    diff_final_hash CHAR(64) NOT NULL,
    coding_target_snapshot_id UUID NOT NULL,
    coding_target_revision BIGINT NOT NULL,
    coding_target_hash CHAR(64) NOT NULL,
    diff_generation BIGINT NOT NULL,
    diff_manifest_hash CHAR(64) NOT NULL,
    test_evidence_id UUID NOT NULL,
    test_evidence_hash CHAR(64) NOT NULL,
    reviewer_agent_profile_id UUID NOT NULL,
    reviewer_agent_profile_version BIGINT NOT NULL,
    reviewer_agent_principal_id UUID NOT NULL,
    reviewer_owner_member_id UUID,
    subject_owner_member_id UUID,
    reviewer_relationship VARCHAR(32) NOT NULL,
    reviewer_template_key VARCHAR(64) NOT NULL,
    reviewer_template_version BIGINT NOT NULL,
    reviewer_template_hash CHAR(64) NOT NULL,
    reviewer_configuration_revision BIGINT NOT NULL,
    reviewer_configuration_hash CHAR(64) NOT NULL,
    policy_snapshot_id UUID NOT NULL,
    policy_snapshot_revision BIGINT NOT NULL,
    policy_snapshot_hash CHAR(64) NOT NULL,
    context_hash CHAR(64) NOT NULL,
    authority_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_review_context_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_review_context_lineage_version
        UNIQUE (task_execution_id, attempt, package_version),
    CONSTRAINT uk_review_context_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, package_version, context_hash,
            subject_id, subject_type, subject_hash
        ),
    CONSTRAINT uk_review_context_action_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, context_hash
        ),
    CONSTRAINT fk_review_context_subject
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, subject_id, subject_type, subject_hash
        ) REFERENCES crewscope.review_subject (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, subject_type, subject_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_context_parent
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, parent_package_id
        ) REFERENCES crewscope.review_context_package (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_context_diff
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, diff_artifact_id, diff_final_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash,
            diff_generation, diff_manifest_hash
        ) REFERENCES crewscope.diff_artifact (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, final_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash,
            diff_generation, manifest_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_context_test
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, test_evidence_id, test_evidence_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash,
            diff_generation, diff_manifest_hash
        ) REFERENCES crewscope.test_evidence (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, evidence_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash,
            diff_generation, diff_manifest_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_context_agent
        FOREIGN KEY (
            organization_id, reviewer_agent_profile_id,
            reviewer_agent_profile_version, reviewer_agent_principal_id,
            reviewer_template_key, reviewer_template_version
        ) REFERENCES crewscope.agent_profile (
            organization_id, id, version, agent_principal_id,
            template_key, template_version
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_context_configuration
        FOREIGN KEY (
            organization_id, reviewer_agent_profile_id,
            reviewer_configuration_revision, reviewer_configuration_hash,
            reviewer_template_key, reviewer_template_version, reviewer_template_hash
        ) REFERENCES crewscope.agent_configuration_version (
            organization_id, agent_profile_id,
            configuration_revision, configuration_hash,
            template_key, template_version, template_content_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_context_policy
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, policy_snapshot_id,
            policy_snapshot_revision, policy_snapshot_hash
        ) REFERENCES crewscope.policy_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, revision, snapshot_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_context_reviewer_principal
        FOREIGN KEY (organization_id, reviewer_agent_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_context_reviewer_member
        FOREIGN KEY (organization_id, team_id, reviewer_owner_member_id)
        REFERENCES crewscope.team_member (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_context_subject_member
        FOREIGN KEY (organization_id, team_id, subject_owner_member_id)
        REFERENCES crewscope.team_member (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_context_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_context_sequence CHECK (
        package_version > 0
        AND ((package_version = 1 AND parent_package_id IS NULL)
            OR (package_version > 1 AND parent_package_id IS NOT NULL))
    ),
    CONSTRAINT ck_review_context_relationship CHECK (
        (reviewer_relationship = 'SELF_REVIEW'
            AND reviewer_owner_member_id IS NOT NULL
            AND reviewer_owner_member_id = subject_owner_member_id)
        OR (reviewer_relationship = 'INDEPENDENT'
            AND (reviewer_owner_member_id IS NULL
                OR subject_owner_member_id IS NULL
                OR reviewer_owner_member_id <> subject_owner_member_id))
    ),
    CONSTRAINT ck_review_context_versions CHECK (
        attempt BETWEEN 1 AND 100 AND reviewer_agent_profile_version >= 0
        AND reviewer_template_key = 'reviewer' AND reviewer_template_version > 0
        AND reviewer_configuration_revision > 0 AND policy_snapshot_revision > 0
        AND diff_generation > 0
    ),
    CONSTRAINT ck_review_context_hashes CHECK (
        subject_hash ~ '^[0-9a-f]{64}$' AND diff_final_hash ~ '^[0-9a-f]{64}$'
        AND coding_target_hash ~ '^[0-9a-f]{64}$'
        AND diff_manifest_hash ~ '^[0-9a-f]{64}$'
        AND test_evidence_hash ~ '^[0-9a-f]{64}$'
        AND reviewer_template_hash ~ '^[0-9a-f]{64}$'
        AND reviewer_configuration_hash ~ '^[0-9a-f]{64}$'
        AND policy_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND context_hash ~ '^[0-9a-f]{64}$'
        AND JSONB_TYPEOF(authority_snapshot) = 'object'
    )
);

CREATE INDEX ix_review_context_execution_version
    ON crewscope.review_context_package (
        organization_id, team_id, task_execution_id, attempt,
        package_version DESC, id DESC
    );

CREATE TABLE crewscope.review_context_hunk (
    context_package_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    path VARCHAR(1024) NOT NULL,
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    patch_bytes INTEGER NOT NULL,
    patch_hash CHAR(64) NOT NULL,
    PRIMARY KEY (context_package_id, ordinal),
    CONSTRAINT uk_review_context_hunk_range
        UNIQUE (context_package_id, path, start_line, end_line),
    CONSTRAINT fk_review_context_hunk_package
        FOREIGN KEY (context_package_id)
        REFERENCES crewscope.review_context_package (id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_context_hunk_shape CHECK (
        ordinal BETWEEN 1 AND 128 AND BTRIM(path) <> ''
        AND start_line > 0 AND end_line >= start_line
        AND patch_bytes BETWEEN 1 AND 524288
        AND patch_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE crewscope.review_context_command_evidence (
    context_package_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    command_evidence_id UUID NOT NULL,
    evidence_sequence BIGINT NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    command_kind VARCHAR(32) NOT NULL,
    termination VARCHAR(32) NOT NULL,
    exit_code INTEGER,
    summary TEXT NOT NULL,
    PRIMARY KEY (context_package_id, ordinal),
    CONSTRAINT uk_review_context_command_evidence
        UNIQUE (context_package_id, command_evidence_id),
    CONSTRAINT fk_review_context_command_package
        FOREIGN KEY (context_package_id)
        REFERENCES crewscope.review_context_package (id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_context_command_evidence
        FOREIGN KEY (command_evidence_id, evidence_sequence, evidence_hash)
        REFERENCES crewscope.command_evidence (id, evidence_sequence, evidence_hash)
        ON DELETE RESTRICT,
    CONSTRAINT ck_review_context_command_shape CHECK (
        ordinal BETWEEN 1 AND 64 AND evidence_sequence > 0
        AND evidence_hash ~ '^[0-9a-f]{64}$'
        AND command_kind IN ('COMPILE', 'TEST', 'VERIFY', 'FORMAT_CHECK', 'ACCEPTANCE')
        AND termination IN (
            'EXITED', 'TIMED_OUT', 'START_FAILED', 'OUTPUT_LIMIT_EXCEEDED',
            'SANDBOX_POLICY_VIOLATION', 'CANCELLED'
        )
        AND ((termination = 'EXITED' AND exit_code IS NOT NULL)
            OR (termination <> 'EXITED' AND exit_code IS NULL))
        AND OCTET_LENGTH(summary) BETWEEN 1 AND 4096 AND BTRIM(summary) <> ''
    )
);

CREATE TABLE crewscope.review_context_acceptance_result (
    context_package_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    test_evidence_id UUID NOT NULL,
    criterion_index INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary TEXT NOT NULL,
    evidence_coordinates JSONB NOT NULL,
    PRIMARY KEY (context_package_id, ordinal),
    CONSTRAINT uk_review_context_acceptance_criterion
        UNIQUE (context_package_id, criterion_index),
    CONSTRAINT fk_review_context_acceptance_package
        FOREIGN KEY (context_package_id)
        REFERENCES crewscope.review_context_package (id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_context_acceptance_result
        FOREIGN KEY (test_evidence_id, criterion_index)
        REFERENCES crewscope.test_acceptance_result (test_evidence_id, criterion_index)
        ON DELETE RESTRICT,
    CONSTRAINT ck_review_context_acceptance_shape CHECK (
        ordinal BETWEEN 1 AND 100 AND criterion_index > 0
        AND status IN ('PASSED', 'FAILED', 'NOT_EVALUATED')
        AND OCTET_LENGTH(summary) BETWEEN 1 AND 4096 AND BTRIM(summary) <> ''
        AND JSONB_TYPEOF(evidence_coordinates) = 'array'
    )
);

CREATE TABLE crewscope.review_request (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    revision BIGINT NOT NULL,
    predecessor_request_id UUID,
    subject_id UUID NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_hash CHAR(64) NOT NULL,
    context_package_id UUID NOT NULL,
    context_package_version BIGINT NOT NULL,
    context_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    invalidation_reason VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_review_request_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_review_request_revision
        UNIQUE (task_execution_id, attempt, revision),
    CONSTRAINT uk_review_request_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, revision, version,
            request_hash, subject_id, subject_type, subject_hash,
            context_package_id, context_package_version, context_hash
        ),
    CONSTRAINT uk_review_request_decision_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, revision, version, request_hash
        ),
    CONSTRAINT fk_review_request_context
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt,
            context_package_id, context_package_version, context_hash,
            subject_id, subject_type, subject_hash
        ) REFERENCES crewscope.review_context_package (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt,
            id, package_version, context_hash, subject_id, subject_type, subject_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_request_predecessor
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, predecessor_request_id
        ) REFERENCES crewscope.review_request (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_request_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_request_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_request_sequence CHECK (
        revision > 0
        AND ((revision = 1 AND predecessor_request_id IS NULL)
            OR (revision > 1 AND predecessor_request_id IS NOT NULL))
    ),
    CONSTRAINT ck_review_request_status CHECK (
        status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'INVALIDATED')
    ),
    CONSTRAINT ck_review_request_invalidation CHECK (
        (status = 'INVALIDATED' AND invalidation_reason IN (
            'SUBJECT_CHANGED', 'DIFF_CHANGED', 'TEST_EVIDENCE_CHANGED',
            'REVIEWER_CONFIGURATION_CHANGED', 'POLICY_CHANGED', 'CONTEXT_CHANGED'))
        OR (status <> 'INVALIDATED' AND invalidation_reason IS NULL)
    ),
    CONSTRAINT ck_review_request_shape CHECK (
        attempt BETWEEN 1 AND 100 AND context_package_version > 0
        AND request_hash ~ '^[0-9a-f]{64}$' AND version >= 0
        AND updated_at >= created_at
    )
);

CREATE INDEX ix_review_request_queue
    ON crewscope.review_request (
        organization_id, team_id, status, updated_at, id
    ) WHERE status IN ('OPEN', 'IN_PROGRESS');

CREATE INDEX ix_review_request_task_history
    ON crewscope.review_request (
        task_id, task_execution_id, attempt, revision DESC, id DESC
    );

-- Mutable ReviewRequest state is copied to immutable facts so Findings and Decisions can pin the
-- exact optimistic version that authorized them without preventing later legal state transitions.
CREATE TABLE crewscope.review_request_state (
    review_request_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    revision BIGINT NOT NULL,
    request_version BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    invalidation_reason VARCHAR(64),
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by_principal_id UUID NOT NULL,
    PRIMARY KEY (review_request_id, request_version),
    CONSTRAINT uk_review_request_state_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, review_request_id,
            revision, request_version, request_hash
        ),
    CONSTRAINT fk_review_request_state_request
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, review_request_id
        ) REFERENCES crewscope.review_request (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_request_state_actor
        FOREIGN KEY (organization_id, recorded_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_request_state_values CHECK (
        attempt BETWEEN 1 AND 100 AND revision > 0 AND request_version >= 0
        AND request_hash ~ '^[0-9a-f]{64}$'
        AND status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'INVALIDATED')
        AND ((status = 'INVALIDATED' AND invalidation_reason IS NOT NULL)
            OR (status <> 'INVALIDATED' AND invalidation_reason IS NULL))
    )
);

CREATE INDEX ix_review_request_state_history
    ON crewscope.review_request_state (
        review_request_id, request_version DESC, recorded_at DESC
    );

CREATE TABLE crewscope.review_finding (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    review_request_id UUID NOT NULL,
    review_request_revision BIGINT NOT NULL,
    review_request_version BIGINT NOT NULL,
    review_request_hash CHAR(64) NOT NULL,
    reviewer_mode VARCHAR(32) NOT NULL,
    reviewer_relationship VARCHAR(32) NOT NULL,
    reviewer_principal_id UUID NOT NULL,
    severity VARCHAR(32) NOT NULL,
    category VARCHAR(32) NOT NULL,
    title VARCHAR(300) NOT NULL,
    claim TEXT NOT NULL,
    suggested_fix TEXT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    candidate_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_review_finding_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_review_finding_fingerprint
        UNIQUE (review_request_id, fingerprint),
    CONSTRAINT uk_review_finding_reference
        UNIQUE (review_request_id, id, fingerprint, candidate_hash),
    CONSTRAINT fk_review_finding_request
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, review_request_id,
            review_request_revision, review_request_version, review_request_hash
        ) REFERENCES crewscope.review_request_state (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, review_request_id,
            revision, request_version, request_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_finding_reviewer
        FOREIGN KEY (organization_id, reviewer_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_finding_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_finding_modes CHECK (
        reviewer_mode IN ('ADVISORY', 'GATE')
        AND reviewer_relationship IN ('INDEPENDENT', 'SELF_REVIEW')
        AND NOT (reviewer_mode = 'GATE' AND reviewer_relationship = 'SELF_REVIEW')
    ),
    CONSTRAINT ck_review_finding_values CHECK (
        attempt BETWEEN 1 AND 100 AND review_request_revision > 0
        AND review_request_version >= 0
        AND severity IN ('BLOCKER', 'HIGH', 'MEDIUM', 'LOW')
        AND category IN (
            'CORRECTNESS', 'SECURITY', 'RELIABILITY',
            'MAINTAINABILITY', 'TESTING', 'ACCEPTANCE')
        AND BTRIM(title) <> '' AND CHAR_LENGTH(title) <= 300
        AND BTRIM(claim) <> '' AND OCTET_LENGTH(claim) <= 16384
        AND BTRIM(suggested_fix) <> '' AND OCTET_LENGTH(suggested_fix) <= 16384
        AND fingerprint ~ '^[0-9a-f]{64}$' AND candidate_hash ~ '^[0-9a-f]{64}$'
        AND reviewer_principal_id = created_by_principal_id
    )
);

CREATE INDEX ix_review_finding_request_severity
    ON crewscope.review_finding (
        review_request_id, severity, category, created_at, id
    );

CREATE TABLE crewscope.review_finding_evidence (
    review_finding_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    path VARCHAR(1024) NOT NULL,
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    diff_artifact_id UUID NOT NULL,
    diff_manifest_hash CHAR(64) NOT NULL,
    test_evidence_id UUID NOT NULL,
    test_evidence_hash CHAR(64) NOT NULL,
    acceptance_criterion_index INTEGER NOT NULL,
    PRIMARY KEY (review_finding_id, ordinal),
    CONSTRAINT uk_review_finding_evidence_coordinate
        UNIQUE (
            review_finding_id, path, start_line, end_line,
            acceptance_criterion_index
        ),
    CONSTRAINT fk_review_finding_evidence_finding
        FOREIGN KEY (review_finding_id)
        REFERENCES crewscope.review_finding (id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_finding_evidence_acceptance
        FOREIGN KEY (test_evidence_id, acceptance_criterion_index)
        REFERENCES crewscope.test_acceptance_result (test_evidence_id, criterion_index)
        ON DELETE RESTRICT,
    CONSTRAINT ck_review_finding_evidence_shape CHECK (
        ordinal BETWEEN 1 AND 100 AND BTRIM(path) <> ''
        AND start_line > 0 AND end_line >= start_line
        AND acceptance_criterion_index > 0
        AND diff_manifest_hash ~ '^[0-9a-f]{64}$'
        AND test_evidence_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE crewscope.review_finding_observation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    review_request_id UUID NOT NULL,
    review_finding_id UUID NOT NULL,
    finding_fingerprint CHAR(64) NOT NULL,
    first_candidate_hash CHAR(64) NOT NULL,
    observation_number BIGINT NOT NULL,
    candidate_hash CHAR(64) NOT NULL,
    reviewer_principal_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_review_finding_observation_number
        UNIQUE (review_finding_id, observation_number),
    CONSTRAINT fk_review_finding_observation_finding
        FOREIGN KEY (
            review_request_id, review_finding_id,
            finding_fingerprint, first_candidate_hash
        ) REFERENCES crewscope.review_finding (
            review_request_id, id, fingerprint, candidate_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_finding_observation_reviewer
        FOREIGN KEY (organization_id, reviewer_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_finding_observation_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_finding_observation_shape CHECK (
        observation_number >= 2 AND candidate_hash ~ '^[0-9a-f]{64}$'
        AND reviewer_principal_id = created_by_principal_id
    )
);

CREATE INDEX ix_review_finding_observation_history
    ON crewscope.review_finding_observation (
        review_finding_id, observation_number, id
    );

CREATE TABLE crewscope.review_decision (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    review_request_id UUID NOT NULL,
    review_request_revision BIGINT NOT NULL,
    review_request_version BIGINT NOT NULL,
    review_request_hash CHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    predecessor_decision_id UUID,
    reviewer_mode VARCHAR(32) NOT NULL,
    reviewer_principal_id UUID NOT NULL,
    reviewer_member_id UUID NOT NULL,
    eligibility_mode VARCHAR(32) NOT NULL,
    eligibility_reason VARCHAR(128) NOT NULL,
    decision_type VARCHAR(32) NOT NULL,
    rationale TEXT NOT NULL,
    decision_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_review_decision_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_review_decision_revision
        UNIQUE (review_request_id, revision),
    CONSTRAINT uk_review_decision_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            work_item_id, task_id, task_execution_id, id, revision,
            review_request_id, review_request_revision, review_request_version,
            review_request_hash,
            decision_type, decision_hash
        ),
    CONSTRAINT uk_review_decision_round_reference
        UNIQUE (id, revision, decision_type, decision_hash),
    CONSTRAINT fk_review_decision_work_item
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id, work_item_id
        ) REFERENCES crewscope.work_item (
            organization_id, team_id, workspace_id, project_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_decision_request
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, review_request_id,
            review_request_revision, review_request_version, review_request_hash
        ) REFERENCES crewscope.review_request_state (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, review_request_id,
            revision, request_version, request_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_decision_predecessor
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, predecessor_decision_id
        ) REFERENCES crewscope.review_decision (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_decision_reviewer_member
        FOREIGN KEY (
            organization_id, team_id, reviewer_member_id, reviewer_principal_id
        ) REFERENCES crewscope.team_member (
            organization_id, team_id, id, user_principal_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_decision_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_decision_sequence CHECK (
        revision > 0
        AND ((revision = 1 AND predecessor_decision_id IS NULL)
            OR (revision > 1 AND predecessor_decision_id IS NOT NULL))
    ),
    CONSTRAINT ck_review_decision_gate CHECK (
        reviewer_mode = 'GATE'
        AND eligibility_mode IN ('INDEPENDENT_MEMBER', 'EXPLICIT_SELF_REVIEW_OVERRIDE')
        AND BTRIM(eligibility_reason) <> ''
        AND decision_type IN ('COMMENTED', 'APPROVED', 'CHANGES_REQUESTED', 'REJECTED')
        AND BTRIM(rationale) <> '' AND OCTET_LENGTH(rationale) <= 16384
        AND decision_hash ~ '^[0-9a-f]{64}$'
        AND reviewer_principal_id = created_by_principal_id
    )
);

CREATE UNIQUE INDEX ux_review_decision_terminal_gate
    ON crewscope.review_decision (review_request_id)
    WHERE decision_type IN ('APPROVED', 'CHANGES_REQUESTED', 'REJECTED');

CREATE INDEX ix_review_decision_task_history
    ON crewscope.review_decision (
        organization_id, team_id, task_id, created_at DESC, id DESC
    );

CREATE TABLE crewscope.review_modification_round (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    round_number BIGINT NOT NULL,
    predecessor_round_id UUID,
    source_request_id UUID NOT NULL,
    source_request_revision BIGINT NOT NULL,
    source_request_version BIGINT NOT NULL,
    source_request_hash CHAR(64) NOT NULL,
    trigger_decision_id UUID NOT NULL,
    trigger_decision_revision BIGINT NOT NULL,
    trigger_decision_type VARCHAR(32) NOT NULL,
    trigger_decision_hash CHAR(64) NOT NULL,
    round_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_review_modification_round_number
        UNIQUE (task_id, round_number),
    CONSTRAINT uk_review_modification_round_trigger
        UNIQUE (trigger_decision_id),
    CONSTRAINT uk_review_modification_round_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT fk_review_modification_round_predecessor
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, predecessor_round_id
        ) REFERENCES crewscope.review_modification_round (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_modification_round_request
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, source_request_id,
            source_request_revision, source_request_version, source_request_hash
        ) REFERENCES crewscope.review_request_state (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, review_request_id,
            revision, request_version, request_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_modification_round_decision
        FOREIGN KEY (
            trigger_decision_id, trigger_decision_revision,
            trigger_decision_type, trigger_decision_hash
        ) REFERENCES crewscope.review_decision (
            id, revision, decision_type, decision_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_review_modification_round_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_modification_round_sequence CHECK (
        round_number > 0
        AND ((round_number = 1 AND predecessor_round_id IS NULL)
            OR (round_number > 1 AND predecessor_round_id IS NOT NULL))
        AND source_request_revision > 0 AND source_request_version >= 0
        AND source_request_hash ~ '^[0-9a-f]{64}$'
        AND trigger_decision_revision > 0
        AND trigger_decision_type = 'CHANGES_REQUESTED'
        AND trigger_decision_hash ~ '^[0-9a-f]{64}$'
        AND round_hash ~ '^[0-9a-f]{64}$'
    )
);

-- ActionBundle is immutable authority; mutable progress lives in Confirmation and Dispatch.
CREATE TABLE crewscope.action_bundle (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    review_decision_id UUID NOT NULL,
    review_decision_revision BIGINT NOT NULL,
    review_decision_type VARCHAR(32) NOT NULL,
    review_decision_hash CHAR(64) NOT NULL,
    review_request_id UUID NOT NULL,
    review_request_revision BIGINT NOT NULL,
    review_request_version BIGINT NOT NULL,
    review_request_hash CHAR(64) NOT NULL,
    review_subject_id UUID NOT NULL,
    review_subject_hash CHAR(64) NOT NULL,
    review_context_package_id UUID NOT NULL,
    review_context_hash CHAR(64) NOT NULL,
    review_diff_artifact_id UUID NOT NULL,
    review_diff_final_hash CHAR(64) NOT NULL,
    responsibility_assignment_id UUID NOT NULL,
    responsibility_version BIGINT NOT NULL,
    responsibility_role VARCHAR(32) NOT NULL,
    responsibility_principal_id UUID NOT NULL,
    provider_binding_id UUID NOT NULL,
    provider_binding_version BIGINT NOT NULL,
    provider_definition_id UUID NOT NULL,
    provider_definition_version BIGINT NOT NULL,
    provider_implementation_id UUID NOT NULL,
    provider_implementation_version BIGINT NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    provider_execution_identity VARCHAR(64) NOT NULL,
    connection_id UUID NOT NULL,
    connection_version BIGINT NOT NULL,
    connection_grant_id UUID NOT NULL,
    connection_grant_version BIGINT NOT NULL,
    effective_access_hash CHAR(64) NOT NULL,
    policy_snapshot_id UUID NOT NULL,
    policy_snapshot_revision BIGINT NOT NULL,
    policy_snapshot_hash CHAR(64) NOT NULL,
    safety_overlay_id UUID NOT NULL,
    safety_overlay_version BIGINT NOT NULL,
    safety_overlay_hash CHAR(64) NOT NULL,
    repository_binding_id UUID NOT NULL,
    repository_binding_version BIGINT NOT NULL,
    repository_key VARCHAR(63) NOT NULL,
    default_branch VARCHAR(255) NOT NULL,
    coding_target_snapshot_id UUID NOT NULL,
    coding_target_revision BIGINT NOT NULL,
    coding_target_hash CHAR(64) NOT NULL,
    baseline_commit CHAR(40) NOT NULL,
    delivery_commit CHAR(40) NOT NULL,
    authority_snapshot JSONB NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    bundle_digest CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_action_bundle_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id
        ),
    CONSTRAINT uk_action_bundle_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, bundle_digest
        ),
    CONSTRAINT uk_action_bundle_confirmation_reference
        UNIQUE (
            organization_id, team_id, workspace_id, project_id,
            id, bundle_digest
        ),
    CONSTRAINT fk_action_bundle_decision
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            work_item_id, task_id, task_execution_id, review_decision_id,
            review_decision_revision, review_request_id, review_request_revision,
            review_request_version, review_request_hash,
            review_decision_type, review_decision_hash
        ) REFERENCES crewscope.review_decision (
            organization_id, team_id, workspace_id, project_id,
            work_item_id, task_id, task_execution_id, id,
            revision, review_request_id, review_request_revision,
            review_request_version, review_request_hash,
            decision_type, decision_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_subject
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt,
            review_subject_id, review_subject_hash
        ) REFERENCES crewscope.review_subject (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, subject_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_context
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt,
            review_context_package_id, review_context_hash
        ) REFERENCES crewscope.review_context_package (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, context_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_diff
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt,
            review_diff_artifact_id, review_diff_final_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash,
            baseline_commit, delivery_commit
        ) REFERENCES crewscope.diff_artifact (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, attempt, id, final_hash,
            coding_target_snapshot_id, coding_target_revision, coding_target_hash,
            baseline_commit, delivery_commit
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_responsibility
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            work_item_id, responsibility_assignment_id, responsibility_version,
            responsibility_role, responsibility_principal_id
        ) REFERENCES crewscope.responsibility_assignment (
            organization_id, team_id, workspace_id, project_id,
            work_item_id, id, version, role, actor_principal_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_provider_binding
        FOREIGN KEY (
            organization_id, team_id, workspace_id, provider_binding_id,
            provider_binding_version, provider_definition_id, provider_definition_version,
            provider_implementation_id, provider_implementation_version,
            connection_id, connection_version, connection_grant_id,
            connection_grant_version, provider_execution_identity
        ) REFERENCES crewscope.provider_binding (
            organization_id, team_id, workspace_id, id,
            version, provider_definition_id, provider_definition_version,
            provider_implementation_id, provider_implementation_version,
            connection_id, connection_version, connection_grant_id,
            connection_grant_version, execution_identity
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_definition
        FOREIGN KEY (organization_id, provider_definition_id, provider_definition_version)
        REFERENCES crewscope.provider_definition (organization_id, id, version)
        ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_implementation
        FOREIGN KEY (
            organization_id, provider_implementation_id, provider_implementation_version
        ) REFERENCES crewscope.provider_implementation (organization_id, id, version)
        ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_connection
        FOREIGN KEY (organization_id, connection_id, connection_version)
        REFERENCES crewscope.github_connection_profile (
            organization_id, connection_id, connection_version
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_grant
        FOREIGN KEY (
            organization_id, connection_grant_id, connection_id, connection_grant_version
        ) REFERENCES crewscope.connection_grant (
            organization_id, id, connection_id, version
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_policy
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, policy_snapshot_id,
            policy_snapshot_revision, policy_snapshot_hash
        ) REFERENCES crewscope.policy_snapshot (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, revision, snapshot_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_safety
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, safety_overlay_id,
            safety_overlay_version, safety_overlay_hash
        ) REFERENCES crewscope.safety_enforcement_overlay (
            organization_id, team_id, workspace_id, project_id,
            task_id, task_execution_id, id, overlay_version, overlay_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_repository
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            repository_binding_id, repository_binding_version,
            repository_key, default_branch
        ) REFERENCES crewscope.repository_binding (
            organization_id, team_id, workspace_id, project_id,
            id, version, repository_key, default_branch
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_bundle_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_action_bundle_authority CHECK (
        attempt BETWEEN 1 AND 100 AND review_decision_type = 'APPROVED'
        AND review_request_version >= 0 AND responsibility_role = 'OWNER'
        AND provider_type = 'SOURCE_CODE'
        AND provider_execution_identity IN ('DELEGATED_USER', 'TEAM_SERVICE_ACCOUNT')
        AND responsibility_version >= 0 AND provider_binding_version >= 0
        AND provider_definition_version >= 0 AND provider_implementation_version >= 0
        AND connection_version >= 0 AND connection_grant_version >= 0
        AND policy_snapshot_revision > 0 AND safety_overlay_version > 0
        AND repository_binding_version >= 0 AND coding_target_revision > 0
        AND version = 0 AND valid_until > created_at
        AND JSONB_TYPEOF(authority_snapshot) = 'object'
    ),
    CONSTRAINT ck_action_bundle_hashes CHECK (
        review_request_hash ~ '^[0-9a-f]{64}$'
        AND review_decision_hash ~ '^[0-9a-f]{64}$'
        AND review_subject_hash ~ '^[0-9a-f]{64}$'
        AND review_context_hash ~ '^[0-9a-f]{64}$'
        AND review_diff_final_hash ~ '^[0-9a-f]{64}$'
        AND effective_access_hash ~ '^[0-9a-f]{64}$'
        AND policy_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND safety_overlay_hash ~ '^[0-9a-f]{64}$'
        AND coding_target_hash ~ '^[0-9a-f]{64}$'
        AND baseline_commit ~ '^[0-9a-f]{40}$'
        AND delivery_commit ~ '^[0-9a-f]{40}$'
        AND bundle_digest ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_action_bundle_task_created
    ON crewscope.action_bundle (
        organization_id, team_id, task_id, created_at DESC, id DESC
    );

CREATE TABLE crewscope.planned_action (
    id UUID PRIMARY KEY,
    action_bundle_id UUID NOT NULL,
    sequence INTEGER NOT NULL,
    action_kind VARCHAR(32) NOT NULL,
    external_repository_id VARCHAR(100) NOT NULL,
    connection_id UUID NOT NULL,
    branch_full_ref VARCHAR(255),
    delivery_head CHAR(40),
    expected_remote_head CHAR(40),
    pr_head VARCHAR(255),
    pr_base VARCHAR(255),
    pr_head_sha CHAR(40),
    pr_title VARCHAR(256),
    pr_body TEXT,
    pr_draft BOOLEAN,
    parameter_snapshot JSONB NOT NULL,
    risk VARCHAR(32) NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    action_digest CHAR(64) NOT NULL,
    CONSTRAINT uk_planned_action_bundle_sequence
        UNIQUE (action_bundle_id, sequence),
    CONSTRAINT uk_planned_action_bundle_id
        UNIQUE (action_bundle_id, id),
    CONSTRAINT uk_planned_action_reference
        UNIQUE (action_bundle_id, id, sequence, action_digest),
    CONSTRAINT fk_planned_action_bundle
        FOREIGN KEY (action_bundle_id)
        REFERENCES crewscope.action_bundle (id) ON DELETE RESTRICT,
    CONSTRAINT fk_planned_action_repository
        FOREIGN KEY (connection_id, external_repository_id)
        REFERENCES crewscope.github_repository_catalog_entry (
            connection_id, external_repository_id
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_planned_action_sequence CHECK (sequence > 0),
    CONSTRAINT ck_planned_action_kind CHECK (
        action_kind IN ('PUSH_BRANCH', 'CREATE_DRAFT_PR')
    ),
    CONSTRAINT ck_planned_action_push_shape CHECK (
        (action_kind = 'PUSH_BRANCH'
            AND branch_full_ref LIKE 'refs/heads/%'
            AND delivery_head ~ '^[0-9a-f]{40}$'
            AND (expected_remote_head IS NULL
                OR expected_remote_head ~ '^[0-9a-f]{40}$')
            AND pr_head IS NULL AND pr_base IS NULL AND pr_head_sha IS NULL
            AND pr_title IS NULL AND pr_body IS NULL AND pr_draft IS NULL)
        OR (action_kind = 'CREATE_DRAFT_PR'
            AND branch_full_ref IS NULL AND delivery_head IS NULL
            AND expected_remote_head IS NULL
            AND BTRIM(pr_head) <> '' AND BTRIM(pr_base) <> '' AND pr_head <> pr_base
            AND pr_head_sha ~ '^[0-9a-f]{40}$'
            AND BTRIM(pr_title) <> '' AND CHAR_LENGTH(pr_title) <= 256
            AND BTRIM(pr_body) <> '' AND OCTET_LENGTH(pr_body) <= 65536
            AND pr_draft)
    ),
    CONSTRAINT ck_planned_action_values CHECK (
        BTRIM(external_repository_id) <> ''
        AND JSONB_TYPEOF(parameter_snapshot) = 'object'
        AND risk IN ('READ_ONLY', 'LOW_RISK_WRITE', 'HIGH_RISK_WRITE', 'DESTRUCTIVE')
        AND action_digest ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE crewscope.planned_action_dependency (
    action_bundle_id UUID NOT NULL,
    action_id UUID NOT NULL,
    predecessor_action_id UUID NOT NULL,
    PRIMARY KEY (action_bundle_id, action_id, predecessor_action_id),
    CONSTRAINT fk_planned_action_dependency_action
        FOREIGN KEY (action_bundle_id, action_id)
        REFERENCES crewscope.planned_action (action_bundle_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_planned_action_dependency_predecessor
        FOREIGN KEY (action_bundle_id, predecessor_action_id)
        REFERENCES crewscope.planned_action (action_bundle_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_planned_action_dependency_self CHECK (action_id <> predecessor_action_id)
);

CREATE TABLE crewscope.action_confirmation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    action_bundle_id UUID NOT NULL,
    bundle_digest CHAR(64) NOT NULL,
    confirmed_by_principal_id UUID NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    cancellation_reason VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_action_confirmation_bundle UNIQUE (action_bundle_id),
    CONSTRAINT uk_action_confirmation_scope_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, id),
    CONSTRAINT uk_action_confirmation_reference
        UNIQUE (id, action_bundle_id, bundle_digest),
    CONSTRAINT uk_action_confirmation_bundle_reference
        UNIQUE (id, action_bundle_id),
    CONSTRAINT fk_action_confirmation_bundle
        FOREIGN KEY (
            organization_id, team_id, workspace_id, project_id,
            action_bundle_id, bundle_digest
        ) REFERENCES crewscope.action_bundle (
            organization_id, team_id, workspace_id, project_id,
            id, bundle_digest
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_confirmation_actor
        FOREIGN KEY (organization_id, confirmed_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_action_confirmation_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_action_confirmation_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_action_confirmation_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_action_confirmation_cancellation CHECK (
        (status = 'ACTIVE' AND cancellation_reason IS NULL)
        OR (status = 'CANCELLED' AND cancellation_reason IN (
            'CONFIRMATION_CANCELLED', 'MEMBER_CANCELLED', 'DEPENDENCY_FAILED',
            'BUNDLE_EXPIRED', 'AUTHORITY_INVALIDATED'))
    ),
    CONSTRAINT ck_action_confirmation_shape CHECK (
        bundle_digest ~ '^[0-9a-f]{64}$' AND version >= 0
        AND confirmed_at = created_at AND confirmed_at < valid_until
        AND confirmed_by_principal_id = created_by_principal_id
        AND updated_at >= created_at
    )
);

CREATE TABLE crewscope.confirmation_action (
    confirmation_id UUID NOT NULL,
    action_bundle_id UUID NOT NULL,
    action_id UUID NOT NULL,
    sequence INTEGER NOT NULL,
    action_digest CHAR(64) NOT NULL,
    PRIMARY KEY (confirmation_id, sequence),
    CONSTRAINT uk_confirmation_action_exact UNIQUE (confirmation_id, action_id),
    CONSTRAINT fk_confirmation_action_confirmation
        FOREIGN KEY (confirmation_id, action_bundle_id)
        REFERENCES crewscope.action_confirmation (id, action_bundle_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_confirmation_action_planned
        FOREIGN KEY (action_bundle_id, action_id, sequence, action_digest)
        REFERENCES crewscope.planned_action (
            action_bundle_id, id, sequence, action_digest
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_confirmation_action_sequence CHECK (
        sequence > 0 AND action_digest ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE crewscope.action_dispatch (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    action_bundle_id UUID NOT NULL,
    bundle_digest CHAR(64) NOT NULL,
    confirmation_id UUID NOT NULL,
    action_id UUID NOT NULL,
    action_digest CHAR(64) NOT NULL,
    sequence INTEGER NOT NULL,
    idempotency_key CHAR(64) NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    claim_worker_id VARCHAR(200),
    claim_fencing_token BIGINT,
    claim_mode VARCHAR(32),
    claim_acquired_at TIMESTAMPTZ,
    claim_last_heartbeat_at TIMESTAMPTZ,
    claim_lease_until TIMESTAMPTZ,
    last_fencing_token BIGINT NOT NULL DEFAULT 0,
    claim_attempts INTEGER NOT NULL DEFAULT 0,
    reconciliation_attempts INTEGER NOT NULL DEFAULT 0,
    not_before TIMESTAMPTZ NOT NULL,
    receipt_id UUID,
    cancellation_reason VARCHAR(64),
    compensation_disposition VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_action_dispatch_action UNIQUE (action_id),
    CONSTRAINT uk_action_dispatch_idempotency UNIQUE (organization_id, idempotency_key),
    CONSTRAINT uk_action_dispatch_scope_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, id),
    CONSTRAINT uk_action_dispatch_receipt_reference
        UNIQUE (
            organization_id, action_bundle_id, action_id, action_digest,
            idempotency_key, id, last_fencing_token
        ),
    CONSTRAINT fk_action_dispatch_confirmation
        FOREIGN KEY (confirmation_id, action_bundle_id)
        REFERENCES crewscope.action_confirmation (id, action_bundle_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_action_dispatch_action
        FOREIGN KEY (action_bundle_id, action_id, sequence, action_digest)
        REFERENCES crewscope.planned_action (
            action_bundle_id, id, sequence, action_digest
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_dispatch_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_action_dispatch_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_action_dispatch_status CHECK (status IN (
        'READY', 'RUNNING', 'UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW',
        'SUCCEEDED', 'FAILED', 'MANUALLY_SUCCEEDED', 'MANUALLY_FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_action_dispatch_claim CHECK (
        (claim_worker_id IS NULL AND claim_fencing_token IS NULL AND claim_mode IS NULL
            AND claim_acquired_at IS NULL AND claim_last_heartbeat_at IS NULL
            AND claim_lease_until IS NULL)
        OR (claim_worker_id IS NOT NULL AND BTRIM(claim_worker_id) <> ''
            AND claim_fencing_token = last_fencing_token AND claim_fencing_token > 0
            AND claim_mode IN ('EXECUTE', 'RECONCILE')
            AND claim_acquired_at <= claim_last_heartbeat_at
            AND claim_last_heartbeat_at < claim_lease_until
            AND claim_lease_until <= claim_last_heartbeat_at + INTERVAL '5 minutes')
    ),
    CONSTRAINT ck_action_dispatch_terminal CHECK (
        ((status IN (
            'SUCCEEDED', 'FAILED', 'MANUALLY_SUCCEEDED', 'MANUALLY_FAILED', 'CANCELLED'
        )) = (receipt_id IS NOT NULL))
        AND (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL)
        AND (status = 'CANCELLED' OR cancellation_reason IS NULL)
    ),
    CONSTRAINT ck_action_dispatch_values CHECK (
        bundle_digest ~ '^[0-9a-f]{64}$' AND action_digest ~ '^[0-9a-f]{64}$'
        AND idempotency_key ~ '^[0-9a-f]{64}$' AND sequence > 0
        AND last_fencing_token >= 0 AND claim_attempts >= 0
        AND reconciliation_attempts >= 0 AND version >= 0
        AND compensation_disposition IN ('NOT_REQUIRED', 'MANUAL_REVIEW_REQUIRED')
        AND created_at <= updated_at AND created_at <= not_before
    )
);

CREATE INDEX ix_action_dispatch_claimable
    ON crewscope.action_dispatch (status, not_before, created_at, id)
    WHERE status IN ('READY', 'UNKNOWN', 'RECONCILING');

CREATE INDEX ix_action_dispatch_manual_queue
    ON crewscope.action_dispatch (
        organization_id, team_id, updated_at, id
    ) WHERE status = 'MANUAL_REVIEW';

CREATE TABLE crewscope.action_dispatch_dependency (
    action_dispatch_id UUID NOT NULL,
    predecessor_action_id UUID NOT NULL,
    PRIMARY KEY (action_dispatch_id, predecessor_action_id),
    CONSTRAINT fk_action_dispatch_dependency_dispatch
        FOREIGN KEY (action_dispatch_id)
        REFERENCES crewscope.action_dispatch (id) ON DELETE RESTRICT,
    CONSTRAINT fk_action_dispatch_dependency_predecessor
        FOREIGN KEY (predecessor_action_id)
        REFERENCES crewscope.action_dispatch (action_id) ON DELETE RESTRICT
);

CREATE TABLE crewscope.action_receipt (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    action_bundle_id UUID NOT NULL,
    bundle_digest CHAR(64) NOT NULL,
    action_dispatch_id UUID NOT NULL,
    action_id UUID NOT NULL,
    action_digest CHAR(64) NOT NULL,
    idempotency_key CHAR(64) NOT NULL,
    result VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    claim_worker_id VARCHAR(200),
    claim_fencing_token BIGINT,
    connection_id UUID,
    external_object_type VARCHAR(32),
    external_id VARCHAR(500),
    external_business_key VARCHAR(500),
    target_version VARCHAR(500),
    evidence_code VARCHAR(128) NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    evidence_artifact_id UUID,
    resolved_by_principal_id UUID,
    manual_reason VARCHAR(64),
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_action_receipt_action UNIQUE (organization_id, action_id),
    CONSTRAINT uk_action_receipt_dispatch UNIQUE (action_dispatch_id),
    CONSTRAINT uk_action_receipt_idempotency UNIQUE (organization_id, idempotency_key),
    CONSTRAINT uk_action_receipt_reference
        UNIQUE (id, action_id, action_digest, result),
    CONSTRAINT ux_action_receipt_external_id
        UNIQUE (connection_id, external_object_type, external_id),
    CONSTRAINT ux_action_receipt_business_key
        UNIQUE (connection_id, external_object_type, external_business_key),
    CONSTRAINT fk_action_receipt_dispatch
        FOREIGN KEY (
            organization_id, action_bundle_id, action_id, action_digest,
            idempotency_key, action_dispatch_id, claim_fencing_token
        ) REFERENCES crewscope.action_dispatch (
            organization_id, action_bundle_id, action_id, action_digest,
            idempotency_key, id, last_fencing_token
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_action_receipt_resolver
        FOREIGN KEY (organization_id, resolved_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_action_receipt_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_action_receipt_result CHECK (
        result IN ('SUCCEEDED', 'FAILED', 'MANUALLY_SUCCEEDED', 'MANUALLY_FAILED', 'CANCELLED')
        AND source IN ('WRITE_RESPONSE', 'ACTIVE_QUERY', 'WEBHOOK', 'MANUAL', 'CONTROL')
    ),
    CONSTRAINT ck_action_receipt_claim CHECK (
        (source IN ('WRITE_RESPONSE', 'ACTIVE_QUERY')
            AND claim_worker_id IS NOT NULL AND BTRIM(claim_worker_id) <> ''
            AND claim_fencing_token IS NOT NULL AND claim_fencing_token > 0
            AND result IN ('SUCCEEDED', 'FAILED')
            AND resolved_by_principal_id IS NULL AND manual_reason IS NULL)
        OR (source IN ('ACTIVE_QUERY', 'WEBHOOK')
            AND claim_worker_id IS NULL AND claim_fencing_token IS NULL
            AND result IN ('SUCCEEDED', 'FAILED')
            AND resolved_by_principal_id IS NULL AND manual_reason IS NULL)
        OR (source = 'MANUAL' AND result IN ('MANUALLY_SUCCEEDED', 'MANUALLY_FAILED')
            AND claim_worker_id IS NULL AND claim_fencing_token IS NULL
            AND resolved_by_principal_id IS NOT NULL
            AND manual_reason IN (
                'EXTERNAL_OBJECT_VERIFIED', 'PROVIDER_AUDIT_VERIFIED',
                'NO_EXTERNAL_OBJECT_VERIFIED', 'SECURITY_INCIDENT_DECISION'))
        OR (source = 'CONTROL' AND result = 'CANCELLED'
            AND claim_worker_id IS NULL AND claim_fencing_token IS NULL
            AND resolved_by_principal_id IS NOT NULL AND manual_reason IS NULL)
    ),
    CONSTRAINT ck_action_receipt_external CHECK (
        ((connection_id IS NULL AND external_object_type IS NULL
            AND external_id IS NULL AND external_business_key IS NULL
            AND target_version IS NULL AND result IN ('FAILED', 'MANUALLY_FAILED', 'CANCELLED'))
        OR (connection_id IS NOT NULL
            AND external_object_type IN ('BRANCH', 'PULL_REQUEST')
            AND BTRIM(external_id) <> '' AND BTRIM(external_business_key) <> ''
            AND BTRIM(target_version) <> ''))
        AND (result NOT IN ('SUCCEEDED', 'MANUALLY_SUCCEEDED') OR connection_id IS NOT NULL)
    ),
    CONSTRAINT ck_action_receipt_evidence CHECK (
        evidence_code ~ '^[A-Z][A-Z0-9_.-]{0,127}$'
        AND evidence_hash ~ '^[0-9a-f]{64}$'
        AND created_at = received_at
    )
);

ALTER TABLE crewscope.action_dispatch
    ADD CONSTRAINT fk_action_dispatch_receipt
        FOREIGN KEY (receipt_id, action_id, action_digest, status)
        REFERENCES crewscope.action_receipt (id, action_id, action_digest, result)
        ON DELETE RESTRICT;

CREATE TABLE crewscope.external_observation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    action_bundle_id UUID NOT NULL,
    action_id UUID NOT NULL,
    action_digest CHAR(64) NOT NULL,
    observation_key CHAR(64) NOT NULL,
    connection_id UUID NOT NULL,
    external_object_type VARCHAR(32) NOT NULL,
    external_id VARCHAR(500) NOT NULL,
    external_business_key VARCHAR(500) NOT NULL,
    external_status VARCHAR(32) NOT NULL,
    provider_version BIGINT,
    provider_updated_at TIMESTAMPTZ,
    source VARCHAR(32) NOT NULL,
    evidence_code VARCHAR(128) NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    evidence_artifact_id UUID,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_external_observation_connection_key
        UNIQUE (connection_id, observation_key),
    CONSTRAINT uk_external_observation_reference
        UNIQUE (
            connection_id, external_object_type, external_id,
            external_business_key, observation_key
        ),
    CONSTRAINT fk_external_observation_action
        FOREIGN KEY (action_bundle_id, action_id)
        REFERENCES crewscope.planned_action (action_bundle_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_external_observation_connection
        FOREIGN KEY (organization_id, connection_id)
        REFERENCES crewscope.github_connection_profile (organization_id, connection_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_external_observation_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_external_observation_object CHECK (
        (external_object_type = 'BRANCH' AND external_status IN ('PRESENT', 'MISSING'))
        OR (external_object_type = 'PULL_REQUEST'
            AND external_status IN ('OPEN', 'CLOSED', 'MERGED'))
    ),
    CONSTRAINT ck_external_observation_version CHECK (
        (provider_version IS NOT NULL AND provider_version > 0)
        OR provider_updated_at IS NOT NULL
    ),
    CONSTRAINT ck_external_observation_values CHECK (
        BTRIM(external_id) <> '' AND BTRIM(external_business_key) <> ''
        AND action_digest ~ '^[0-9a-f]{64}$'
        AND observation_key ~ '^[0-9a-f]{64}$'
        AND source IN ('WRITE_RESPONSE', 'WEBHOOK', 'ACTIVE_QUERY')
        AND evidence_code ~ '^[A-Z][A-Z0-9_.-]{0,127}$'
        AND evidence_hash ~ '^[0-9a-f]{64}$'
        AND (provider_updated_at IS NULL OR provider_updated_at <= observed_at)
        AND observed_at = created_at
    )
);

CREATE INDEX ix_external_observation_history
    ON crewscope.external_observation (
        connection_id, external_object_type, external_id,
        observed_at DESC, id DESC
    );

CREATE TABLE crewscope.external_result (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    action_bundle_id UUID NOT NULL,
    action_id UUID NOT NULL,
    action_digest CHAR(64) NOT NULL,
    connection_id UUID NOT NULL,
    external_object_type VARCHAR(32) NOT NULL,
    external_id VARCHAR(500) NOT NULL,
    external_business_key VARCHAR(500) NOT NULL,
    external_status VARCHAR(32) NOT NULL,
    provider_version BIGINT,
    provider_updated_at TIMESTAMPTZ,
    last_source VARCHAR(32) NOT NULL,
    last_observation_key CHAR(64) NOT NULL,
    last_evidence_code VARCHAR(128) NOT NULL,
    last_evidence_hash CHAR(64) NOT NULL,
    last_evidence_artifact_id UUID,
    observed_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_external_result_action UNIQUE (organization_id, action_id),
    CONSTRAINT ux_external_result_external_id
        UNIQUE (connection_id, external_object_type, external_id),
    CONSTRAINT ux_external_result_business_key
        UNIQUE (connection_id, external_object_type, external_business_key),
    CONSTRAINT fk_external_result_action
        FOREIGN KEY (action_bundle_id, action_id)
        REFERENCES crewscope.planned_action (action_bundle_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_external_result_observation
        FOREIGN KEY (
            connection_id, external_object_type, external_id,
            external_business_key, last_observation_key
        ) REFERENCES crewscope.external_observation (
            connection_id, external_object_type, external_id,
            external_business_key, observation_key
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_external_result_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_external_result_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_external_result_object CHECK (
        (external_object_type = 'BRANCH' AND external_status IN ('PRESENT', 'MISSING'))
        OR (external_object_type = 'PULL_REQUEST'
            AND external_status IN ('OPEN', 'CLOSED', 'MERGED'))
    ),
    CONSTRAINT ck_external_result_values CHECK (
        BTRIM(external_id) <> '' AND BTRIM(external_business_key) <> ''
        AND action_digest ~ '^[0-9a-f]{64}$'
        AND ((provider_version IS NOT NULL AND provider_version > 0)
            OR provider_updated_at IS NOT NULL)
        AND last_source IN ('WRITE_RESPONSE', 'WEBHOOK', 'ACTIVE_QUERY')
        AND last_observation_key ~ '^[0-9a-f]{64}$'
        AND last_evidence_code ~ '^[A-Z][A-Z0-9_.-]{0,127}$'
        AND last_evidence_hash ~ '^[0-9a-f]{64}$'
        AND version >= 0
        AND (provider_updated_at IS NULL OR provider_updated_at <= observed_at)
        AND updated_at >= created_at
    )
);

CREATE INDEX ix_external_result_reconcile
    ON crewscope.external_result (
        organization_id, team_id, external_object_type,
        external_status, observed_at DESC, id DESC
    );

-- Database-level append-only and controlled-transition guards close persistence bypasses.
CREATE FUNCTION crewscope.reject_v21_append_only_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION crewscope.guard_review_request_v21()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ReviewRequest cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF ROW(
        NEW.organization_id, NEW.team_id, NEW.workspace_id, NEW.project_id,
        NEW.task_id, NEW.task_execution_id, NEW.attempt, NEW.revision,
        NEW.predecessor_request_id, NEW.subject_id, NEW.subject_type,
        NEW.subject_hash, NEW.context_package_id, NEW.context_package_version,
        NEW.context_hash, NEW.request_hash, NEW.created_at, NEW.created_by_principal_id
    ) IS DISTINCT FROM ROW(
        OLD.organization_id, OLD.team_id, OLD.workspace_id, OLD.project_id,
        OLD.task_id, OLD.task_execution_id, OLD.attempt, OLD.revision,
        OLD.predecessor_request_id, OLD.subject_id, OLD.subject_type,
        OLD.subject_hash, OLD.context_package_id, OLD.context_package_version,
        OLD.context_hash, OLD.request_hash, OLD.created_at, OLD.created_by_principal_id
    ) OR NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at
      OR NOT (
        (OLD.status = 'OPEN' AND NEW.status IN ('IN_PROGRESS', 'INVALIDATED'))
        OR (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('COMPLETED', 'INVALIDATED'))
        OR (OLD.status = 'COMPLETED' AND NEW.status = 'INVALIDATED')
      ) THEN
        RAISE EXCEPTION 'invalid ReviewRequest transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION crewscope.capture_review_request_state_v21()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO crewscope.review_request_state (
        review_request_id, organization_id, team_id, workspace_id, project_id,
        task_id, task_execution_id, attempt, revision, request_version,
        request_hash, status, invalidation_reason, recorded_at, recorded_by_principal_id
    ) VALUES (
        NEW.id, NEW.organization_id, NEW.team_id, NEW.workspace_id, NEW.project_id,
        NEW.task_id, NEW.task_execution_id, NEW.attempt, NEW.revision, NEW.version,
        NEW.request_hash, NEW.status, NEW.invalidation_reason, NEW.updated_at,
        NEW.updated_by_principal_id
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION crewscope.guard_action_confirmation_v21()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Confirmation cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'ACTIVE' OR NEW.status <> 'CANCELLED'
      OR NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at
      OR ROW(
        NEW.organization_id, NEW.team_id, NEW.workspace_id, NEW.project_id,
        NEW.action_bundle_id, NEW.bundle_digest, NEW.confirmed_by_principal_id,
        NEW.confirmed_at, NEW.valid_until, NEW.created_at, NEW.created_by_principal_id
      ) IS DISTINCT FROM ROW(
        OLD.organization_id, OLD.team_id, OLD.workspace_id, OLD.project_id,
        OLD.action_bundle_id, OLD.bundle_digest, OLD.confirmed_by_principal_id,
        OLD.confirmed_at, OLD.valid_until, OLD.created_at, OLD.created_by_principal_id
      ) THEN
        RAISE EXCEPTION 'invalid Confirmation transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION crewscope.guard_action_dispatch_v21()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ActionDispatch cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at
      OR NEW.last_fencing_token < OLD.last_fencing_token
      OR ROW(
        NEW.organization_id, NEW.team_id, NEW.workspace_id, NEW.project_id,
        NEW.action_bundle_id, NEW.bundle_digest, NEW.confirmation_id,
        NEW.action_id, NEW.action_digest, NEW.sequence, NEW.idempotency_key,
        NEW.valid_until, NEW.created_at, NEW.created_by_principal_id
      ) IS DISTINCT FROM ROW(
        OLD.organization_id, OLD.team_id, OLD.workspace_id, OLD.project_id,
        OLD.action_bundle_id, OLD.bundle_digest, OLD.confirmation_id,
        OLD.action_id, OLD.action_digest, OLD.sequence, OLD.idempotency_key,
        OLD.valid_until, OLD.created_at, OLD.created_by_principal_id
      ) OR NOT (
        (OLD.status = NEW.status AND OLD.status IN ('RUNNING', 'RECONCILING'))
        OR (OLD.status = 'READY' AND NEW.status IN ('RUNNING', 'CANCELLED'))
        OR (OLD.status = 'RUNNING' AND NEW.status IN (
            'READY', 'UNKNOWN', 'RECONCILING', 'SUCCEEDED', 'FAILED'))
        OR (OLD.status = 'UNKNOWN' AND NEW.status IN (
            'RECONCILING', 'SUCCEEDED', 'FAILED'))
        OR (OLD.status = 'RECONCILING' AND NEW.status IN (
            'UNKNOWN', 'MANUAL_REVIEW', 'SUCCEEDED', 'FAILED'))
        OR (OLD.status = 'MANUAL_REVIEW'
            AND NEW.status IN ('MANUALLY_SUCCEEDED', 'MANUALLY_FAILED'))
      ) OR OLD.status IN (
        'SUCCEEDED', 'FAILED', 'MANUALLY_SUCCEEDED', 'MANUALLY_FAILED', 'CANCELLED'
      ) THEN
        RAISE EXCEPTION 'invalid ActionDispatch transition or fencing token'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION crewscope.guard_external_result_v21()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    manual_terminal BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ExternalResult cannot be deleted' USING ERRCODE = '23514';
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM crewscope.action_receipt receipt
        WHERE receipt.organization_id = OLD.organization_id
          AND receipt.action_id = OLD.action_id
          AND receipt.result IN ('MANUALLY_SUCCEEDED', 'MANUALLY_FAILED')
    ) INTO manual_terminal;
    IF manual_terminal OR NEW.version <> OLD.version + 1
      OR NEW.observed_at < OLD.observed_at OR NEW.updated_at < OLD.updated_at
      OR ROW(
        NEW.organization_id, NEW.team_id, NEW.workspace_id, NEW.project_id,
        NEW.action_bundle_id, NEW.action_id, NEW.action_digest,
        NEW.connection_id, NEW.external_object_type,
        NEW.external_id, NEW.external_business_key,
        NEW.created_at, NEW.created_by_principal_id
      ) IS DISTINCT FROM ROW(
        OLD.organization_id, OLD.team_id, OLD.workspace_id, OLD.project_id,
        OLD.action_bundle_id, OLD.action_id, OLD.action_digest,
        OLD.connection_id, OLD.external_object_type,
        OLD.external_id, OLD.external_business_key,
        OLD.created_at, OLD.created_by_principal_id
      ) OR (OLD.provider_version IS NOT NULL
            AND (NEW.provider_version IS NULL OR NEW.provider_version <= OLD.provider_version))
      OR (OLD.provider_version IS NULL AND NEW.provider_version IS NULL
            AND NEW.provider_updated_at <= OLD.provider_updated_at)
      OR (OLD.external_status = 'MERGED' AND NEW.external_status <> 'MERGED') THEN
        RAISE EXCEPTION 'invalid ExternalResult monotonic merge' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_review_request_guard_v21
    BEFORE UPDATE OR DELETE ON crewscope.review_request
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_review_request_v21();

CREATE TRIGGER trg_review_request_state_capture_v21
    AFTER INSERT OR UPDATE ON crewscope.review_request
    FOR EACH ROW EXECUTE FUNCTION crewscope.capture_review_request_state_v21();

CREATE TRIGGER trg_action_confirmation_guard_v21
    BEFORE UPDATE OR DELETE ON crewscope.action_confirmation
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_action_confirmation_v21();

CREATE TRIGGER trg_action_dispatch_guard_v21
    BEFORE UPDATE OR DELETE ON crewscope.action_dispatch
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_action_dispatch_v21();

CREATE TRIGGER trg_external_result_guard_v21
    BEFORE UPDATE OR DELETE ON crewscope.external_result
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_external_result_v21();

DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'github_rate_limit_snapshot', 'review_subject', 'review_context_package',
        'review_context_hunk', 'review_context_command_evidence',
        'review_context_acceptance_result', 'review_finding',
        'review_request_state',
        'review_finding_evidence', 'review_finding_observation', 'review_decision',
        'review_modification_round', 'action_bundle', 'planned_action',
        'planned_action_dependency', 'confirmation_action',
        'action_dispatch_dependency', 'action_receipt', 'external_observation'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON crewscope.%I '
            || 'FOR EACH ROW EXECUTE FUNCTION crewscope.reject_v21_append_only_mutation()',
            'trg_' || table_name || '_append_only_v21', table_name
        );
    END LOOP;
END;
$$;
