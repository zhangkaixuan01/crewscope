-- M1 closes Team initialization references after TeamMember and Workspace already exist. The
-- deferred foreign keys allow Team, Workspace and owner membership to be inserted in one
-- transaction while still proving the complete scope at commit time.
ALTER TABLE crewscope.principal
    ADD CONSTRAINT uk_principal_organization_team_id
        UNIQUE (organization_id, team_id, id),
    ADD CONSTRAINT uk_principal_organization_id_type
        UNIQUE (organization_id, id, principal_type);

ALTER TABLE crewscope.team_member
    ADD CONSTRAINT uk_team_member_scope_user
        UNIQUE (organization_id, team_id, id, user_principal_id);

ALTER TABLE crewscope.team
    ADD COLUMN owner_member_id UUID,
    ADD COLUMN default_workspace_id UUID,
    ADD CONSTRAINT ck_team_initialization_references CHECK (
        (owner_member_id IS NULL AND default_workspace_id IS NULL)
        OR (owner_member_id IS NOT NULL AND default_workspace_id IS NOT NULL)
    ),
    ADD CONSTRAINT fk_team_owner_member
        FOREIGN KEY (organization_id, id, owner_member_id)
        REFERENCES crewscope.team_member (organization_id, team_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_team_default_workspace
        FOREIGN KEY (organization_id, id, default_workspace_id)
        REFERENCES crewscope.workspace (organization_id, team_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX ix_team_owner_member
    ON crewscope.team (organization_id, owner_member_id)
    WHERE owner_member_id IS NOT NULL;

-- AgentProfile is the durable product configuration identity. Runtime model, prompt, tool and
-- memory configuration is introduced by M2; V6 stores only the stable M1 identity and lifecycle.
CREATE TABLE crewscope.agent_profile (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    agent_principal_id UUID NOT NULL,
    owner_member_id UUID,
    profile_type VARCHAR(32) NOT NULL,
    default_profile BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_agent_profile_scope_id UNIQUE (organization_id, team_id, id),
    CONSTRAINT uk_agent_profile_agent UNIQUE (organization_id, agent_principal_id),
    CONSTRAINT fk_agent_profile_workspace
        FOREIGN KEY (organization_id, team_id, workspace_id)
        REFERENCES crewscope.workspace (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_profile_agent
        FOREIGN KEY (organization_id, team_id, agent_principal_id)
        REFERENCES crewscope.principal (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_profile_owner_member
        FOREIGN KEY (organization_id, team_id, owner_member_id)
        REFERENCES crewscope.team_member (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_profile_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_profile_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_profile_type CHECK (
        profile_type IN ('PERSONAL', 'TEAM', 'SPECIALIST')
    ),
    CONSTRAINT ck_agent_profile_owner CHECK (
        profile_type <> 'PERSONAL' OR owner_member_id IS NOT NULL
    ),
    CONSTRAINT ck_agent_profile_default CHECK (
        NOT default_profile OR (profile_type = 'PERSONAL' AND owner_member_id IS NOT NULL)
    ),
    CONSTRAINT ck_agent_profile_status CHECK (
        status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')
    ),
    CONSTRAINT ck_agent_profile_version CHECK (version >= 0),
    CONSTRAINT ck_agent_profile_timestamps CHECK (updated_at >= created_at)
);

-- The partial key is the database concurrency verdict for initializeIfAbsent. The agent key above
-- additionally prevents a Principal from being represented by multiple profiles.
CREATE UNIQUE INDEX ux_agent_profile_active_default_personal
    ON crewscope.agent_profile (organization_id, team_id, owner_member_id)
    WHERE profile_type = 'PERSONAL' AND default_profile AND status = 'ACTIVE';

CREATE INDEX ix_agent_profile_team_status
    ON crewscope.agent_profile (organization_id, team_id, status, updated_at DESC);

CREATE INDEX ix_agent_profile_owner_status
    ON crewscope.agent_profile (owner_member_id, status)
    WHERE owner_member_id IS NOT NULL;

-- Existing projects become active M1 projects. Existing V5 rows remain readable while every new
-- row receives the explicit lifecycle value.
ALTER TABLE crewscope.work_project
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT ck_work_project_status CHECK (status IN ('ACTIVE', 'ARCHIVED'));

CREATE INDEX ix_work_project_team_status
    ON crewscope.work_project (
        organization_id, team_id, status, updated_at DESC, id DESC
    );

-- Labels are a compact M1 value collection. A complete WorkItem scope key supports child-table
-- foreign keys and makes accidental cross-project relations impossible at the database edge.
ALTER TABLE crewscope.work_item
    ADD COLUMN labels JSONB NOT NULL DEFAULT '[]'::JSONB,
    ADD COLUMN due_at TIMESTAMPTZ,
    ADD CONSTRAINT uk_work_item_scope_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, id),
    ADD CONSTRAINT ck_work_item_description_length
        CHECK (description IS NULL OR CHAR_LENGTH(description) <= 100000) NOT VALID,
    ADD CONSTRAINT ck_work_item_type_values
        CHECK (item_type IN ('TASK', 'BUG', 'FEATURE', 'INCIDENT')) NOT VALID,
    ADD CONSTRAINT ck_work_item_status_values CHECK (
        status IN (
            'BACKLOG', 'READY', 'IN_PROGRESS', 'IN_REVIEW',
            'BLOCKED', 'DONE', 'CANCELLED', 'ARCHIVED'
        )
    ) NOT VALID,
    ADD CONSTRAINT ck_work_item_priority_values
        CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')) NOT VALID,
    ADD CONSTRAINT ck_work_item_source_values
        CHECK (source_provider IN ('CREWSCOPE', 'JIRA', 'ZENTAO', 'TAPD')) NOT VALID,
    ADD CONSTRAINT ck_work_item_source_reference CHECK (
        (source_provider = 'CREWSCOPE' AND source_ref IS NULL)
        OR (source_provider <> 'CREWSCOPE'
            AND source_ref IS NOT NULL
            AND BTRIM(source_ref) <> '')
    ) NOT VALID,
    ADD CONSTRAINT ck_work_item_labels CHECK (
        JSONB_TYPEOF(labels) = 'array' AND JSONB_ARRAY_LENGTH(labels) <= 20
    );

CREATE INDEX ix_work_item_labels
    ON crewscope.work_item USING GIN (labels);

CREATE INDEX ix_work_item_team_due
    ON crewscope.work_item (
        organization_id, team_id, due_at, updated_at DESC, id DESC
    )
    WHERE due_at IS NOT NULL AND status NOT IN ('DONE', 'CANCELLED', 'ARCHIVED');

-- Comments and resource links are immutable collaboration facts. They retain complete scope and
-- current-row provenance so D08 can reconstitute AuditMetadata without implicit joins.
CREATE TABLE crewscope.work_item_comment (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    author_principal_id UUID NOT NULL,
    content TEXT NOT NULL,
    source_provider VARCHAR(32) NOT NULL DEFAULT 'CREWSCOPE',
    external_id VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_work_item_comment_scope_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, work_item_id, id),
    CONSTRAINT fk_work_item_comment_work_item
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id, work_item_id)
        REFERENCES crewscope.work_item (
            organization_id, team_id, workspace_id, project_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_work_item_comment_author
        FOREIGN KEY (organization_id, author_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_item_comment_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_item_comment_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_work_item_comment_content CHECK (
        BTRIM(content) <> '' AND CHAR_LENGTH(content) <= 50000
    ),
    CONSTRAINT ck_work_item_comment_source CHECK (
        source_provider IN ('CREWSCOPE', 'JIRA', 'ZENTAO', 'TAPD')
    ),
    CONSTRAINT ck_work_item_comment_external CHECK (
        (source_provider = 'CREWSCOPE' AND external_id IS NULL)
        OR (source_provider <> 'CREWSCOPE'
            AND external_id IS NOT NULL
            AND BTRIM(external_id) <> '')
    ),
    CONSTRAINT ck_work_item_comment_author_audit CHECK (
        author_principal_id = created_by_principal_id
    ),
    CONSTRAINT ck_work_item_comment_immutable_audit CHECK (
        updated_at = created_at AND updated_by_principal_id = created_by_principal_id
    )
);

CREATE UNIQUE INDEX ux_work_item_comment_external
    ON crewscope.work_item_comment (work_item_id, source_provider, external_id)
    WHERE external_id IS NOT NULL;

CREATE INDEX ix_work_item_comment_work_item_time
    ON crewscope.work_item_comment (work_item_id, created_at, id);

CREATE INDEX ix_work_item_comment_author_time
    ON crewscope.work_item_comment (
        organization_id, author_principal_id, created_at DESC, id DESC
    );

CREATE TABLE crewscope.work_item_resource_link (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_reference VARCHAR(2000) NOT NULL,
    label VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_work_item_resource_link_scope_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, work_item_id, id),
    CONSTRAINT fk_work_item_resource_link_work_item
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id, work_item_id)
        REFERENCES crewscope.work_item (
            organization_id, team_id, workspace_id, project_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_work_item_resource_link_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_item_resource_link_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_work_item_resource_link_type CHECK (
        resource_type IN (
            'TASK', 'CONVERSATION', 'REPOSITORY', 'BRANCH',
            'COMMIT', 'PULL_REQUEST', 'ARTIFACT', 'EXTERNAL_URL'
        )
    ),
    CONSTRAINT ck_work_item_resource_link_reference CHECK (
        BTRIM(resource_reference) <> ''
    ),
    CONSTRAINT ck_work_item_resource_link_label CHECK (
        label IS NULL OR BTRIM(label) <> ''
    ),
    CONSTRAINT ck_work_item_resource_link_immutable_audit CHECK (
        updated_at = created_at AND updated_by_principal_id = created_by_principal_id
    )
);

CREATE INDEX ix_work_item_resource_link_work_item
    ON crewscope.work_item_resource_link (work_item_id, resource_type, created_at, id);

CREATE INDEX ix_work_item_resource_link_resource
    ON crewscope.work_item_resource_link (resource_type, resource_reference);

-- ResponsibilityAssignment is the M1 responsibility source of truth. The member/principal
-- composite key proves that a USER actor's membership belongs to that exact user and Team.
CREATE TABLE crewscope.responsibility_assignment (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    actor_principal_id UUID NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_member_id UUID,
    status VARCHAR(32) NOT NULL,
    assigned_by_principal_id UUID NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    released_by_principal_id UUID,
    released_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_responsibility_assignment_scope_id
        UNIQUE (organization_id, team_id, workspace_id, project_id, work_item_id, id),
    CONSTRAINT fk_responsibility_assignment_work_item
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id, work_item_id)
        REFERENCES crewscope.work_item (
            organization_id, team_id, workspace_id, project_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_responsibility_assignment_actor
        FOREIGN KEY (organization_id, actor_principal_id, actor_type)
        REFERENCES crewscope.principal (organization_id, id, principal_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_responsibility_assignment_actor_member
        FOREIGN KEY (organization_id, team_id, actor_member_id, actor_principal_id)
        REFERENCES crewscope.team_member (
            organization_id, team_id, id, user_principal_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_responsibility_assignment_assigned_by
        FOREIGN KEY (organization_id, assigned_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_responsibility_assignment_released_by
        FOREIGN KEY (organization_id, released_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_responsibility_assignment_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_responsibility_assignment_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_responsibility_assignment_role CHECK (
        role IN ('OWNER', 'EXECUTOR', 'REVIEWER')
    ),
    CONSTRAINT ck_responsibility_assignment_actor_member CHECK (
        (actor_type = 'USER' AND actor_member_id IS NOT NULL)
        OR (actor_type <> 'USER' AND actor_member_id IS NULL)
    ),
    CONSTRAINT ck_responsibility_assignment_role_actor CHECK (
        (role = 'OWNER' AND actor_type = 'USER')
        OR (role = 'EXECUTOR'
            AND actor_type IN (
                'USER', 'PERSONAL_AGENT', 'TEAM_AGENT', 'SPECIALIST_AGENT'
            ))
        OR (role = 'REVIEWER'
            AND actor_type IN ('USER', 'SPECIALIST_AGENT'))
    ),
    CONSTRAINT ck_responsibility_assignment_status CHECK (
        status IN ('ACTIVE', 'RELEASED')
    ),
    CONSTRAINT ck_responsibility_assignment_release CHECK (
        (status = 'ACTIVE'
            AND released_by_principal_id IS NULL
            AND released_at IS NULL)
        OR (status = 'RELEASED'
            AND released_by_principal_id IS NOT NULL
            AND released_at IS NOT NULL)
    ),
    CONSTRAINT ck_responsibility_assignment_times CHECK (
        accepted_at >= assigned_at
        AND (released_at IS NULL OR released_at >= accepted_at)
    ),
    CONSTRAINT ck_responsibility_assignment_audit CHECK (
        created_by_principal_id = assigned_by_principal_id
        AND (
            (status = 'ACTIVE' AND updated_by_principal_id = assigned_by_principal_id)
            OR (status = 'RELEASED'
                AND updated_by_principal_id = released_by_principal_id)
        )
    ),
    CONSTRAINT ck_responsibility_assignment_version CHECK (version >= 0),
    CONSTRAINT ck_responsibility_assignment_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX ux_responsibility_assignment_active_owner
    ON crewscope.responsibility_assignment (work_item_id)
    WHERE role = 'OWNER' AND status = 'ACTIVE';

CREATE UNIQUE INDEX ux_responsibility_assignment_active_role_actor
    ON crewscope.responsibility_assignment (work_item_id, role, actor_principal_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_responsibility_assignment_subject_role_status
    ON crewscope.responsibility_assignment (
        organization_id, team_id, work_item_id, role, status, assigned_at, id
    );

CREATE INDEX ix_responsibility_assignment_actor_status
    ON crewscope.responsibility_assignment (
        organization_id, actor_principal_id, status, role, updated_at DESC, id DESC
    );
