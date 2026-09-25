CREATE TABLE crewscope.project_execution_defaults (
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    repository_binding_id UUID,
    repository_binding_version BIGINT,
    branch VARCHAR(255),
    build_profile_key VARCHAR(128),
    build_profile_version BIGINT,
    build_profile_hash CHAR(64),
    agent_profile_id UUID,
    agent_profile_revision BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, team_id, workspace_id, project_id),
    CONSTRAINT fk_execution_defaults_project
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id)
        REFERENCES crewscope.work_project (organization_id, team_id, workspace_id, id)
        ON DELETE CASCADE,
    CONSTRAINT fk_execution_defaults_binding
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id, repository_binding_id)
        REFERENCES crewscope.repository_binding (organization_id, team_id, workspace_id, project_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_execution_defaults_version CHECK (version >= 0),
    CONSTRAINT ck_execution_defaults_binding_pair CHECK (
        (repository_binding_id IS NULL AND repository_binding_version IS NULL)
        OR (repository_binding_id IS NOT NULL AND repository_binding_version IS NOT NULL
            AND repository_binding_version >= 0)
    ),
    CONSTRAINT ck_execution_defaults_profile_pair CHECK (
        (build_profile_key IS NULL AND build_profile_version IS NULL AND build_profile_hash IS NULL)
        OR (build_profile_key IS NOT NULL AND build_profile_version IS NOT NULL AND build_profile_hash IS NOT NULL)
    ),
    CONSTRAINT ck_execution_defaults_branch CHECK (branch IS NULL OR CHAR_LENGTH(branch) BETWEEN 1 AND 255),
    CONSTRAINT ck_execution_defaults_agent_pair CHECK (
        (agent_profile_id IS NULL AND agent_profile_revision IS NULL)
        OR (agent_profile_id IS NOT NULL AND agent_profile_revision IS NOT NULL AND agent_profile_revision >= 0)
    )
);

CREATE INDEX ix_execution_defaults_project
    ON crewscope.project_execution_defaults (organization_id, team_id, project_id);
