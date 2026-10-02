-- M10-D01: team knowledge foundation (pure PostgreSQL, no vector).
-- Two tables back the ADR-030 aggregate: an optimistic-locked entry head carrying the
-- authoritative effective-version pointer, and append-only version rows. Version rows
-- deliberately have no status column: whether a revision is retrievable is decided
-- solely by the head pointer, so replaying old events can never resurrect a retired
-- version. The S01 prototype dataset stored a per-version status; the production gate
-- is this head pointer, exposed through KnowledgeRepository.findEffectiveVersion.
-- Index, memory, skill and usage-fact tables ship with their own responsibility
-- packages (I01/I02/A03/F03); generation, pointer and manifest tables are not here.

CREATE TABLE crewscope.knowledge_entry (
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    entry_key VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    effective_revision BIGINT,
    latest_revision BIGINT NOT NULL,
    draft_title VARCHAR(200),
    draft_content TEXT,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_entry_tenant_id
        UNIQUE (organization_id, team_id, id),
    CONSTRAINT uk_knowledge_entry_tenant_key
        UNIQUE (organization_id, team_id, entry_key),
    CONSTRAINT fk_knowledge_entry_team
        FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_entry_created_by
        FOREIGN KEY (created_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_entry_updated_by
        FOREIGN KEY (updated_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT ck_knowledge_entry_key CHECK (entry_key ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    CONSTRAINT ck_knowledge_entry_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED', 'DELETED')),
    -- Two-branch shape gate: a DRAFT never carries a pointer, a PUBLISHED head always
    -- does, and RETIRED/DELETED heads keep their last effective revision for source
    -- attribution (mirrors KnowledgeEntry.requireShape).
    CONSTRAINT ck_knowledge_entry_effective_shape CHECK (
        (status <> 'DRAFT' OR effective_revision IS NULL)
        AND (status <> 'PUBLISHED' OR effective_revision IS NOT NULL)),
    CONSTRAINT ck_knowledge_entry_revision_span CHECK (
        effective_revision IS NULL
        OR (effective_revision >= 1 AND effective_revision <= latest_revision)),
    CONSTRAINT ck_knowledge_entry_latest_revision CHECK (latest_revision >= 0),
    CONSTRAINT ck_knowledge_entry_draft_pair CHECK (
        (draft_title IS NULL) = (draft_content IS NULL)),
    CONSTRAINT ck_knowledge_entry_draft_title CHECK (
        draft_title IS NULL OR (BTRIM(draft_title) <> '' AND length(draft_title) <= 200)),
    CONSTRAINT ck_knowledge_entry_draft_content CHECK (
        draft_content IS NULL OR (BTRIM(draft_content) <> '' AND length(draft_content) <= 65536)),
    CONSTRAINT ck_knowledge_entry_version CHECK (version >= 0),
    CONSTRAINT ck_knowledge_entry_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_knowledge_entry_team_status
    ON crewscope.knowledge_entry (organization_id, team_id, status, entry_key);

-- Partial index for the retrieval gate: only PUBLISHED heads are ever resolved.
CREATE INDEX ix_knowledge_entry_effective
    ON crewscope.knowledge_entry (organization_id, team_id, entry_key, effective_revision)
    WHERE status = 'PUBLISHED';

CREATE TABLE crewscope.knowledge_entry_version (
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    entry_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    previous_revision BIGINT,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    PRIMARY KEY (organization_id, team_id, entry_id, revision),
    CONSTRAINT uk_knowledge_entry_version_content
        UNIQUE (organization_id, team_id, entry_id, content_hash),
    CONSTRAINT fk_knowledge_entry_version_entry
        FOREIGN KEY (organization_id, team_id, entry_id)
        REFERENCES crewscope.knowledge_entry (organization_id, team_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_entry_version_created_by
        FOREIGN KEY (created_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT ck_knowledge_entry_version_sequence CHECK (revision >= 1),
    -- Chain rule, written NULL-safe: a bare predicate over a NULL previous_revision
    -- evaluates to NULL and PostgreSQL CHECKs pass on NULL, so a revision > 1 with a
    -- missing predecessor would otherwise slip through.
    CONSTRAINT ck_knowledge_entry_version_chain CHECK (
        (revision > 1 OR previous_revision IS NULL)
        AND COALESCE(previous_revision = revision - 1, revision = 1)),
    CONSTRAINT ck_knowledge_entry_version_title
        CHECK (BTRIM(title) <> '' AND length(title) <= 200),
    CONSTRAINT ck_knowledge_entry_version_content
        CHECK (BTRIM(content) <> '' AND length(content) <= 65536),
    CONSTRAINT ck_knowledge_entry_version_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_knowledge_entry_version_entry_created
    ON crewscope.knowledge_entry_version (entry_id, created_at DESC, revision DESC);

-- Backfill the two M10 permissions onto existing built-in roles so runtime grants read
-- from the database rows stay aligned with the BuiltInTeamRole definitions. Idempotent
-- JSONB union: custom roles are untouched, and rows that already carry the permission
-- keep version and updated_at stable.
UPDATE crewscope.team_role
SET permissions = permissions || '["KNOWLEDGE_MANAGE"]'::jsonb,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE built_in
  AND role_key IN ('TEAM_OWNER', 'TEAM_ADMIN')
  AND NOT permissions ? 'KNOWLEDGE_MANAGE';

UPDATE crewscope.team_role
SET permissions = permissions || '["SKILL_MANAGE"]'::jsonb,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE built_in
  AND role_key = 'TEAM_OWNER'
  AND NOT permissions ? 'SKILL_MANAGE';
