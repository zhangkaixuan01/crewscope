-- Identity and platform tables always use organization-scoped keys. The redundant composite
-- uniqueness on V1 tables enables foreign keys to enforce tenant consistency at the database edge.
ALTER TABLE crewscope.team
    ADD CONSTRAINT uk_team_organization_id UNIQUE (organization_id, id);

ALTER TABLE crewscope.workspace
    ADD CONSTRAINT uk_workspace_organization_id UNIQUE (organization_id, id),
    ADD CONSTRAINT uk_workspace_organization_team_id UNIQUE (organization_id, team_id, id);

ALTER TABLE crewscope.work_project
    ADD CONSTRAINT uk_work_project_organization_team_id UNIQUE (organization_id, team_id, id),
    ADD CONSTRAINT uk_work_project_scope_id
        UNIQUE (organization_id, team_id, workspace_id, id);

ALTER TABLE crewscope.domain_event
    ADD CONSTRAINT uk_domain_event_organization_id UNIQUE (organization_id, event_id);

-- V1 single-column foreign keys prove object existence. These composite foreign keys additionally
-- prove that every relationship stays inside the declared Organization, Team and Workspace scope.
ALTER TABLE crewscope.workspace
    ADD CONSTRAINT fk_workspace_team_scope FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT;

ALTER TABLE crewscope.work_project
    ADD CONSTRAINT fk_work_project_team_scope FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_project_workspace_scope
        FOREIGN KEY (organization_id, team_id, workspace_id)
        REFERENCES crewscope.workspace (organization_id, team_id, id) ON DELETE RESTRICT;

ALTER TABLE crewscope.work_item
    ADD CONSTRAINT fk_work_item_team_scope FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_item_workspace_scope
        FOREIGN KEY (organization_id, team_id, workspace_id)
        REFERENCES crewscope.workspace (organization_id, team_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_item_project_scope
        FOREIGN KEY (organization_id, team_id, workspace_id, project_id)
        REFERENCES crewscope.work_project (organization_id, team_id, workspace_id, id)
        ON DELETE RESTRICT;

ALTER TABLE crewscope.domain_event
    ADD CONSTRAINT fk_domain_event_team_scope FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_domain_event_workspace_scope
        FOREIGN KEY (organization_id, team_id, workspace_id)
        REFERENCES crewscope.workspace (organization_id, team_id, id) ON DELETE RESTRICT;

-- Rebuild the V1 index around the actual M0 keyset order and add the common project-board path.
DROP INDEX crewscope.ix_work_item_team_status;

CREATE INDEX ix_work_item_team_updated
    ON crewscope.work_item (organization_id, team_id, updated_at DESC, id DESC);

CREATE INDEX ix_work_item_team_status
    ON crewscope.work_item (organization_id, team_id, status, updated_at DESC, id DESC);

CREATE INDEX ix_work_item_project_status_updated
    ON crewscope.work_item (
        organization_id, team_id, project_id, status, updated_at DESC, id DESC
    );

-- Principal is the common security identity for users, agents and platform services. Agent owners
-- reference another Principal in the same organization; external identity fields are paired.
CREATE TABLE crewscope.principal (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID,
    principal_type VARCHAR(32) NOT NULL,
    owner_principal_id UUID,
    display_name VARCHAR(200) NOT NULL,
    identity_provider VARCHAR(100),
    external_subject VARCHAR(500),
    visibility VARCHAR(32) NOT NULL DEFAULT 'ORGANIZATION',
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_principal_organization_id UNIQUE (organization_id, id),
    CONSTRAINT fk_principal_organization FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_principal_team FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_principal_owner FOREIGN KEY (organization_id, owner_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_principal_type CHECK (
        principal_type IN ('USER', 'PERSONAL_AGENT', 'TEAM_AGENT', 'SPECIALIST_AGENT', 'SERVICE')
    ),
    CONSTRAINT ck_principal_display_name CHECK (BTRIM(display_name) <> ''),
    CONSTRAINT ck_principal_identity_pair CHECK (
        (identity_provider IS NULL AND external_subject IS NULL)
        OR (identity_provider IS NOT NULL
            AND external_subject IS NOT NULL
            AND BTRIM(identity_provider) <> ''
            AND BTRIM(external_subject) <> '')
    ),
    CONSTRAINT ck_principal_visibility CHECK (
        visibility IN ('PRIVATE', 'TEAM', 'ORGANIZATION')
    ),
    CONSTRAINT ck_principal_team_visibility CHECK (visibility <> 'TEAM' OR team_id IS NOT NULL),
    CONSTRAINT ck_principal_status CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'DISABLED', 'ARCHIVED')
    ),
    CONSTRAINT ck_principal_version CHECK (version >= 0),
    CONSTRAINT ck_principal_timestamps CHECK (updated_at >= created_at)
);

-- One external subject maps to one Principal inside an organization. Internal agents and services
-- have no external identity and therefore do not participate in this partial unique index.
CREATE UNIQUE INDEX ux_principal_external_identity
    ON crewscope.principal (organization_id, identity_provider, external_subject)
    WHERE external_subject IS NOT NULL;

CREATE INDEX ix_principal_owner
    ON crewscope.principal (organization_id, owner_principal_id)
    WHERE owner_principal_id IS NOT NULL;

-- V1 could already contain Principal-shaped UUIDs. Disabled placeholders preserve those immutable
-- references without granting access; identity reconciliation can later activate and enrich them.
INSERT INTO crewscope.principal (
    id,
    organization_id,
    principal_type,
    display_name,
    visibility,
    status
)
SELECT
    event_actor.actor_id,
    event_actor.organization_id,
    CASE
        WHEN BOOL_OR(event_actor.actor_type = 'USER') THEN 'USER'
        WHEN BOOL_OR(event_actor.actor_type = 'PERSONAL_AGENT') THEN 'PERSONAL_AGENT'
        WHEN BOOL_OR(event_actor.actor_type = 'TEAM_AGENT') THEN 'TEAM_AGENT'
        WHEN BOOL_OR(event_actor.actor_type = 'SPECIALIST_AGENT') THEN 'SPECIALIST_AGENT'
        ELSE 'SERVICE'
    END,
    'Migrated actor ' || event_actor.actor_id,
    'ORGANIZATION',
    'DISABLED'
FROM crewscope.domain_event event_actor
WHERE event_actor.actor_id IS NOT NULL
GROUP BY event_actor.organization_id, event_actor.actor_id;

INSERT INTO crewscope.principal (
    id,
    organization_id,
    principal_type,
    display_name,
    visibility,
    status
)
SELECT DISTINCT
    workspace_owner.owner_principal_id,
    workspace_owner.organization_id,
    'USER',
    'Migrated workspace owner ' || workspace_owner.owner_principal_id,
    'ORGANIZATION',
    'DISABLED'
FROM crewscope.workspace workspace_owner
WHERE workspace_owner.owner_principal_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM crewscope.principal existing_principal
      WHERE existing_principal.id = workspace_owner.owner_principal_id
  );

-- TeamMember is a user's durable membership in one Team. Membership rows survive suspension and
-- departure so role grants and audit records retain a stable subject.
CREATE TABLE crewscope.team_member (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    user_principal_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    join_method VARCHAR(32) NOT NULL,
    invited_by_principal_id UUID,
    joined_at TIMESTAMPTZ,
    last_active_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_team_member_organization_team_id UNIQUE (organization_id, team_id, id),
    CONSTRAINT uk_team_member_team_user UNIQUE (team_id, user_principal_id),
    CONSTRAINT fk_team_member_team FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_team_member_user FOREIGN KEY (organization_id, user_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_team_member_inviter FOREIGN KEY (organization_id, invited_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_team_member_status CHECK (
        status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'LEFT', 'REMOVED')
    ),
    CONSTRAINT ck_team_member_join_method CHECK (
        join_method IN ('BOOTSTRAP', 'INVITATION', 'OIDC', 'SCIM', 'IMPORT')
    ),
    CONSTRAINT ck_team_member_active_joined CHECK (status <> 'ACTIVE' OR joined_at IS NOT NULL),
    CONSTRAINT ck_team_member_activity_time CHECK (
        last_active_at IS NULL OR (joined_at IS NOT NULL AND last_active_at >= joined_at)
    ),
    CONSTRAINT ck_team_member_version CHECK (version >= 0),
    CONSTRAINT ck_team_member_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_team_member_team_status
    ON crewscope.team_member (team_id, status, updated_at DESC);

CREATE INDEX ix_team_member_user
    ON crewscope.team_member (organization_id, user_principal_id);

-- TeamRole stores stable management permissions. Assignment scope is repeated on TeamMemberRole so
-- each grant remains auditable even when a role definition is later disabled.
CREATE TABLE crewscope.team_role (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    role_key VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    permissions JSONB NOT NULL DEFAULT '[]'::JSONB,
    scope_type VARCHAR(32) NOT NULL DEFAULT 'TEAM',
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_team_role_organization_team_id
        UNIQUE (organization_id, team_id, id, scope_type),
    CONSTRAINT uk_team_role_team_key UNIQUE (team_id, role_key),
    CONSTRAINT fk_team_role_team FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_team_role_key CHECK (BTRIM(role_key) <> ''),
    CONSTRAINT ck_team_role_name CHECK (BTRIM(name) <> ''),
    CONSTRAINT ck_team_role_permissions CHECK (JSONB_TYPEOF(permissions) = 'array'),
    CONSTRAINT ck_team_role_scope CHECK (scope_type IN ('TEAM', 'WORK_PROJECT')),
    CONSTRAINT ck_team_role_status CHECK (status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')),
    CONSTRAINT ck_team_role_version CHECK (version >= 0),
    CONSTRAINT ck_team_role_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_team_role_team_status
    ON crewscope.team_role (team_id, status, built_in DESC);

-- TeamMemberRole is an explicit grant. TEAM grants have no scope_id; WORK_PROJECT grants carry the
-- target project id. Historical revoked/expired grants can coexist with one active grant.
CREATE TABLE crewscope.team_member_role (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    team_member_id UUID NOT NULL,
    team_role_id UUID NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_id UUID,
    granted_by_principal_id UUID NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_team_member_role_member FOREIGN KEY (organization_id, team_id, team_member_id)
        REFERENCES crewscope.team_member (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_team_member_role_role
        FOREIGN KEY (organization_id, team_id, team_role_id, scope_type)
        REFERENCES crewscope.team_role (organization_id, team_id, id, scope_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_team_member_role_project FOREIGN KEY (organization_id, team_id, scope_id)
        REFERENCES crewscope.work_project (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_team_member_role_grantor FOREIGN KEY (organization_id, granted_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_team_member_role_scope CHECK (
        (scope_type = 'TEAM' AND scope_id IS NULL)
        OR (scope_type = 'WORK_PROJECT' AND scope_id IS NOT NULL)
    ),
    CONSTRAINT ck_team_member_role_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_team_member_role_validity CHECK (expires_at IS NULL OR expires_at > valid_from),
    CONSTRAINT ck_team_member_role_revocation CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (status <> 'REVOKED' AND revoked_at IS NULL)
    ),
    CONSTRAINT ck_team_member_role_version CHECK (version >= 0),
    CONSTRAINT ck_team_member_role_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX ux_team_member_role_active_scope
    ON crewscope.team_member_role (
        team_member_id,
        team_role_id,
        scope_type,
        COALESCE(scope_id, '00000000-0000-0000-0000-000000000000'::UUID)
    )
    WHERE status = 'ACTIVE';

CREATE INDEX ix_team_member_role_member_status
    ON crewscope.team_member_role (team_member_id, status, expires_at);

-- Projection checkpoints use an organization/name/partition key and optimistic version. The event
-- triple is either empty for a new partition or complete after a committed projection advance.
CREATE TABLE crewscope.event_projection_checkpoint (
    organization_id UUID NOT NULL,
    projection_name VARCHAR(200) NOT NULL,
    partition_key VARCHAR(200) NOT NULL,
    last_event_id UUID,
    last_event_cursor VARCHAR(500),
    last_event_occurred_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_event_projection_checkpoint
        PRIMARY KEY (organization_id, projection_name, partition_key),
    CONSTRAINT fk_projection_checkpoint_organization FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_projection_checkpoint_event FOREIGN KEY (organization_id, last_event_id)
        REFERENCES crewscope.domain_event (organization_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_projection_checkpoint_name CHECK (BTRIM(projection_name) <> ''),
    CONSTRAINT ck_projection_checkpoint_partition CHECK (BTRIM(partition_key) <> ''),
    CONSTRAINT ck_projection_checkpoint_event CHECK (
        (last_event_id IS NULL AND last_event_cursor IS NULL AND last_event_occurred_at IS NULL)
        OR (last_event_id IS NOT NULL
            AND last_event_cursor IS NOT NULL
            AND BTRIM(last_event_cursor) <> ''
            AND last_event_occurred_at IS NOT NULL)
    ),
    CONSTRAINT ck_projection_checkpoint_version CHECK (version >= 0),
    CONSTRAINT ck_projection_checkpoint_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_projection_checkpoint_updated
    ON crewscope.event_projection_checkpoint (organization_id, updated_at);

-- AuditEvent is an append-only security projection. Explicit principal columns distinguish the
-- initiating human, effective actor and agent while the credential subject remains polymorphic.
CREATE TABLE crewscope.audit_event (
    event_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID,
    workspace_id UUID,
    principal_id UUID,
    initiator_id UUID,
    actor_type VARCHAR(32) NOT NULL,
    actor_id UUID,
    agent_principal_id UUID,
    credential_subject_type VARCHAR(32),
    credential_subject_id UUID,
    event_type VARCHAR(200) NOT NULL,
    subject_type VARCHAR(100) NOT NULL,
    subject_id UUID,
    outcome VARCHAR(32) NOT NULL,
    authorization_context JSONB NOT NULL DEFAULT '{}'::JSONB,
    domain_event_id UUID,
    correlation_id UUID NOT NULL,
    causation_id UUID,
    trace_id VARCHAR(100),
    schema_version VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_event_organization FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_team FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_workspace FOREIGN KEY (organization_id, workspace_id)
        REFERENCES crewscope.workspace (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_principal FOREIGN KEY (organization_id, principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_initiator FOREIGN KEY (organization_id, initiator_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_actor FOREIGN KEY (organization_id, actor_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_agent FOREIGN KEY (organization_id, agent_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_domain_event FOREIGN KEY (organization_id, domain_event_id)
        REFERENCES crewscope.domain_event (organization_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_event_actor_type CHECK (
        actor_type IN ('USER', 'PERSONAL_AGENT', 'TEAM_AGENT', 'SPECIALIST_AGENT', 'SERVICE')
    ),
    CONSTRAINT ck_audit_event_credential_subject CHECK (
        (credential_subject_type IS NULL AND credential_subject_id IS NULL)
        OR (credential_subject_type IS NOT NULL
            AND credential_subject_type IN ('ORGANIZATION', 'TEAM', 'PRINCIPAL')
            AND credential_subject_id IS NOT NULL)
    ),
    CONSTRAINT ck_audit_event_type CHECK (BTRIM(event_type) <> ''),
    CONSTRAINT ck_audit_event_subject_type CHECK (BTRIM(subject_type) <> ''),
    CONSTRAINT ck_audit_event_outcome CHECK (outcome IN ('SUCCEEDED', 'DENIED', 'FAILED')),
    CONSTRAINT ck_audit_event_authorization CHECK (
        JSONB_TYPEOF(authorization_context) = 'object'
    ),
    CONSTRAINT ck_audit_event_schema_version CHECK (BTRIM(schema_version) <> ''),
    CONSTRAINT ck_audit_event_payload CHECK (JSONB_TYPEOF(payload) = 'object')
);

-- One DomainEvent produces at most one effective audit projection row. Direct security events use a
-- null domain_event_id and keep their own event_id.
CREATE UNIQUE INDEX ux_audit_event_domain_event
    ON crewscope.audit_event (organization_id, domain_event_id)
    WHERE domain_event_id IS NOT NULL;

CREATE INDEX ix_audit_event_organization_time
    ON crewscope.audit_event (organization_id, occurred_at DESC);

CREATE INDEX ix_audit_event_team_time
    ON crewscope.audit_event (team_id, occurred_at DESC)
    WHERE team_id IS NOT NULL;

CREATE INDEX ix_audit_event_actor_time
    ON crewscope.audit_event (actor_id, occurred_at DESC)
    WHERE actor_id IS NOT NULL;

CREATE INDEX ix_audit_event_subject_time
    ON crewscope.audit_event (subject_type, subject_id, occurred_at DESC);

CREATE INDEX ix_audit_event_correlation
    ON crewscope.audit_event (correlation_id, occurred_at);

-- CredentialSecret contains ciphertext and encryption metadata only. Subject columns make AAD
-- reconstruction deterministic and enforce organization/team/principal ownership shapes.
CREATE TABLE crewscope.credential_secret (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID,
    principal_id UUID,
    subject_type VARCHAR(32) NOT NULL,
    subject_id UUID NOT NULL,
    credential_key VARCHAR(200) NOT NULL,
    provider_key VARCHAR(100) NOT NULL,
    connection_ref UUID,
    credential_type VARCHAR(64) NOT NULL,
    ciphertext BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    authentication_tag BYTEA NOT NULL,
    key_id VARCHAR(200) NOT NULL,
    algorithm VARCHAR(32) NOT NULL,
    aad_version VARCHAR(32) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ,
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_credential_secret_organization_key UNIQUE (organization_id, credential_key),
    CONSTRAINT fk_credential_secret_organization FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_credential_secret_team FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_credential_secret_principal FOREIGN KEY (organization_id, principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_credential_secret_subject CHECK (
        (subject_type = 'ORGANIZATION'
            AND subject_id = organization_id AND team_id IS NULL AND principal_id IS NULL)
        OR (subject_type = 'TEAM'
            AND subject_id = team_id AND team_id IS NOT NULL AND principal_id IS NULL)
        OR (subject_type = 'PRINCIPAL'
            AND subject_id = principal_id AND principal_id IS NOT NULL)
    ),
    CONSTRAINT ck_credential_secret_key CHECK (BTRIM(credential_key) <> ''),
    CONSTRAINT ck_credential_secret_provider CHECK (BTRIM(provider_key) <> ''),
    CONSTRAINT ck_credential_secret_type CHECK (BTRIM(credential_type) <> ''),
    CONSTRAINT ck_credential_secret_ciphertext CHECK (OCTET_LENGTH(ciphertext) > 0),
    CONSTRAINT ck_credential_secret_nonce CHECK (OCTET_LENGTH(nonce) = 12),
    CONSTRAINT ck_credential_secret_tag CHECK (OCTET_LENGTH(authentication_tag) = 16),
    CONSTRAINT ck_credential_secret_key_id CHECK (BTRIM(key_id) <> ''),
    CONSTRAINT ck_credential_secret_algorithm CHECK (algorithm = 'AES-256-GCM'),
    CONSTRAINT ck_credential_secret_aad_version CHECK (BTRIM(aad_version) <> ''),
    CONSTRAINT ck_credential_secret_metadata CHECK (JSONB_TYPEOF(metadata) = 'object'),
    CONSTRAINT ck_credential_secret_status CHECK (status IN ('ACTIVE', 'ROTATING', 'REVOKED')),
    CONSTRAINT ck_credential_secret_expiry CHECK (expires_at IS NULL OR expires_at > created_at),
    CONSTRAINT ck_credential_secret_revocation CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (status <> 'REVOKED' AND revoked_at IS NULL)
    ),
    CONSTRAINT ck_credential_secret_version CHECK (version >= 0),
    CONSTRAINT ck_credential_secret_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_credential_secret_subject_status
    ON crewscope.credential_secret (subject_type, subject_id, status);

CREATE INDEX ix_credential_secret_provider_connection
    ON crewscope.credential_secret (organization_id, provider_key, connection_ref)
    WHERE status IN ('ACTIVE', 'ROTATING');

-- V1 could declare Principal-shaped UUID columns before Principal existed. V2 closes those trust
-- boundaries with organization-scoped foreign keys.
ALTER TABLE crewscope.workspace
    ADD CONSTRAINT fk_workspace_owner_principal
    FOREIGN KEY (organization_id, owner_principal_id)
    REFERENCES crewscope.principal (organization_id, id)
    ON DELETE RESTRICT;

ALTER TABLE crewscope.domain_event
    ADD CONSTRAINT fk_domain_event_actor_principal
    FOREIGN KEY (organization_id, actor_id)
    REFERENCES crewscope.principal (organization_id, id)
    ON DELETE RESTRICT;

-- Outbox rows are mutable delivery records, so they require optimistic locking and update time.
ALTER TABLE crewscope.outbox_event
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT ck_outbox_retry_count CHECK (retry_count >= 0),
    ADD CONSTRAINT ck_outbox_version CHECK (version >= 0),
    ADD CONSTRAINT ck_outbox_delivery_status CHECK (BTRIM(delivery_status) <> ''),
    ADD CONSTRAINT ck_outbox_delivery_time CHECK (
        delivered_at IS NULL OR delivered_at >= created_at
    ),
    ADD CONSTRAINT ck_outbox_timestamps CHECK (updated_at >= created_at);

-- Existing aggregate versions are guarded at the persistence boundary. Text checks reject values
-- that pass NOT NULL while carrying no usable business value.
ALTER TABLE crewscope.organization
    ADD CONSTRAINT ck_organization_name CHECK (BTRIM(name) <> ''),
    ADD CONSTRAINT ck_organization_status CHECK (BTRIM(status) <> ''),
    ADD CONSTRAINT ck_organization_version CHECK (version >= 0),
    ADD CONSTRAINT ck_organization_timestamps CHECK (updated_at >= created_at);

ALTER TABLE crewscope.team
    ADD CONSTRAINT ck_team_name CHECK (BTRIM(name) <> ''),
    ADD CONSTRAINT ck_team_status CHECK (BTRIM(status) <> ''),
    ADD CONSTRAINT ck_team_version CHECK (version >= 0),
    ADD CONSTRAINT ck_team_timestamps CHECK (updated_at >= created_at);

ALTER TABLE crewscope.workspace
    ADD CONSTRAINT ck_workspace_name CHECK (BTRIM(name) <> ''),
    ADD CONSTRAINT ck_workspace_status CHECK (BTRIM(status) <> ''),
    ADD CONSTRAINT ck_workspace_version CHECK (version >= 0),
    ADD CONSTRAINT ck_workspace_timestamps CHECK (updated_at >= created_at);

ALTER TABLE crewscope.work_project
    ADD CONSTRAINT ck_work_project_key CHECK (BTRIM(project_key) <> ''),
    ADD CONSTRAINT ck_work_project_name CHECK (BTRIM(name) <> ''),
    ADD CONSTRAINT ck_work_project_version CHECK (version >= 0),
    ADD CONSTRAINT ck_work_project_timestamps CHECK (updated_at >= created_at);

ALTER TABLE crewscope.work_item
    ADD CONSTRAINT ck_work_item_key CHECK (BTRIM(item_key) <> ''),
    ADD CONSTRAINT ck_work_item_type CHECK (BTRIM(item_type) <> ''),
    ADD CONSTRAINT ck_work_item_title CHECK (BTRIM(title) <> ''),
    ADD CONSTRAINT ck_work_item_status CHECK (BTRIM(status) <> ''),
    ADD CONSTRAINT ck_work_item_priority CHECK (BTRIM(priority) <> ''),
    ADD CONSTRAINT ck_work_item_source_provider CHECK (BTRIM(source_provider) <> ''),
    ADD CONSTRAINT ck_work_item_version CHECK (version >= 0),
    ADD CONSTRAINT ck_work_item_timestamps CHECK (updated_at >= created_at);

ALTER TABLE crewscope.domain_event
    ADD CONSTRAINT ck_domain_event_type CHECK (BTRIM(event_type) <> ''),
    ADD CONSTRAINT ck_domain_event_schema_version CHECK (BTRIM(schema_version) <> ''),
    ADD CONSTRAINT ck_domain_event_subject_type CHECK (BTRIM(subject_type) <> ''),
    ADD CONSTRAINT ck_domain_event_actor_type CHECK (BTRIM(actor_type) <> ''),
    ADD CONSTRAINT ck_domain_event_payload CHECK (JSONB_TYPEOF(payload) = 'object');

-- A command idempotency key is unique within an organization while null remains available for
-- system facts that do not originate from an idempotent command.
CREATE UNIQUE INDEX ux_domain_event_idempotency
    ON crewscope.domain_event (organization_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Mutable business snapshots expose their current creator and last modifier without replacing the
-- immutable DomainEvent/AuditEvent history. Columns remain nullable for V1 upgrades, bootstrap and
-- projection repair; new Commands require trusted Principal values at the application boundary.
ALTER TABLE crewscope.organization
    ADD COLUMN created_by_principal_id UUID,
    ADD COLUMN updated_by_principal_id UUID,
    ADD CONSTRAINT fk_organization_created_by_principal
        FOREIGN KEY (id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_organization_updated_by_principal
        FOREIGN KEY (id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE crewscope.team
    ADD COLUMN created_by_principal_id UUID,
    ADD COLUMN updated_by_principal_id UUID,
    ADD CONSTRAINT fk_team_created_by_principal
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_team_updated_by_principal
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT;

ALTER TABLE crewscope.workspace
    ADD COLUMN created_by_principal_id UUID,
    ADD COLUMN updated_by_principal_id UUID,
    ADD CONSTRAINT fk_workspace_created_by_principal
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_workspace_updated_by_principal
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT;

ALTER TABLE crewscope.work_project
    ADD COLUMN created_by_principal_id UUID,
    ADD COLUMN updated_by_principal_id UUID,
    ADD CONSTRAINT fk_work_project_created_by_principal
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_project_updated_by_principal
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT;

ALTER TABLE crewscope.work_item
    ADD COLUMN created_by_principal_id UUID,
    ADD COLUMN updated_by_principal_id UUID,
    ADD CONSTRAINT fk_work_item_created_by_principal
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_item_updated_by_principal
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT;

ALTER TABLE crewscope.principal
    ADD COLUMN created_by_principal_id UUID,
    ADD COLUMN updated_by_principal_id UUID,
    ADD CONSTRAINT fk_principal_created_by_principal
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_principal_updated_by_principal
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT;

ALTER TABLE crewscope.team_member
    ADD COLUMN created_by_principal_id UUID,
    ADD COLUMN updated_by_principal_id UUID,
    ADD CONSTRAINT fk_team_member_created_by_principal
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_team_member_updated_by_principal
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT;

ALTER TABLE crewscope.team_role
    ADD COLUMN created_by_principal_id UUID,
    ADD COLUMN updated_by_principal_id UUID,
    ADD CONSTRAINT fk_team_role_created_by_principal
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_team_role_updated_by_principal
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT;

ALTER TABLE crewscope.team_member_role
    ADD COLUMN created_by_principal_id UUID,
    ADD COLUMN updated_by_principal_id UUID,
    ADD CONSTRAINT fk_team_member_role_created_by_principal
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_team_member_role_updated_by_principal
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT;

ALTER TABLE crewscope.credential_secret
    ADD COLUMN created_by_principal_id UUID,
    ADD COLUMN updated_by_principal_id UUID,
    ADD CONSTRAINT fk_credential_secret_created_by_principal
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_credential_secret_updated_by_principal
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT;
