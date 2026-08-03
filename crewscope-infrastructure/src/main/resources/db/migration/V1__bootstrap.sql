CREATE SCHEMA IF NOT EXISTS crewscope;

CREATE TABLE crewscope.organization (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE crewscope.team (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES crewscope.organization (id),
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_team_organization_name UNIQUE (organization_id, name)
);

CREATE TABLE crewscope.workspace (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES crewscope.organization (id),
    team_id UUID REFERENCES crewscope.team (id),
    workspace_type VARCHAR(16) NOT NULL,
    owner_principal_id UUID,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_workspace_type CHECK (workspace_type IN ('PERSONAL', 'TEAM'))
);

CREATE TABLE crewscope.work_project (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES crewscope.organization (id),
    team_id UUID NOT NULL REFERENCES crewscope.team (id),
    workspace_id UUID NOT NULL REFERENCES crewscope.workspace (id),
    project_key VARCHAR(10) NOT NULL,
    name VARCHAR(200) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_work_project_team_key UNIQUE (team_id, project_key)
);

CREATE TABLE crewscope.work_item (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES crewscope.organization (id),
    team_id UUID NOT NULL REFERENCES crewscope.team (id),
    workspace_id UUID NOT NULL REFERENCES crewscope.workspace (id),
    project_id UUID NOT NULL REFERENCES crewscope.work_project (id),
    item_key VARCHAR(32) NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL,
    priority VARCHAR(32) NOT NULL,
    source_provider VARCHAR(32) NOT NULL DEFAULT 'CREWSCOPE',
    source_ref VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_work_item_project_key UNIQUE (project_id, item_key)
);

CREATE TABLE crewscope.domain_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(200) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID,
    workspace_id UUID,
    subject_type VARCHAR(100) NOT NULL,
    subject_id UUID NOT NULL,
    actor_type VARCHAR(64) NOT NULL,
    actor_id UUID,
    correlation_id UUID NOT NULL,
    causation_id UUID,
    idempotency_key VARCHAR(200),
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL
);

CREATE TABLE crewscope.outbox_event (
    id UUID PRIMARY KEY,
    domain_event_id UUID NOT NULL REFERENCES crewscope.domain_event (event_id),
    topic VARCHAR(200) NOT NULL,
    partition_key VARCHAR(200) NOT NULL,
    delivery_status VARCHAR(32) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_delivery_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMPTZ,
    CONSTRAINT uk_outbox_domain_event UNIQUE (domain_event_id)
);

CREATE INDEX ix_work_item_team_status
    ON crewscope.work_item (team_id, status, updated_at DESC);

CREATE INDEX ix_domain_event_subject
    ON crewscope.domain_event (subject_type, subject_id, occurred_at);

CREATE INDEX ix_outbox_pending
    ON crewscope.outbox_event (delivery_status, next_delivery_at, created_at);
