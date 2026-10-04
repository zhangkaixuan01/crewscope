-- M10-A03a: team skill catalog and approval (pure PostgreSQL, no vector).
-- Two tables back the ADR-031 §4 aggregate, shaped after the V52 knowledge pair: an
-- optimistic-locked skill head carrying the effective-version pointer later executions
-- authorize against, and append-only version rows. Three deliberate divergences from
-- knowledge: there is no tombstone (a skill is disabled, never deleted, and a DISABLED
-- head keeps its last effective revision as historical evidence); version rows carry no
-- unique content hash, because a rollback re-publishes historical content as a new
-- revision and the digest must stay equal to the historical one; and the draft is one
-- SKILL.md document (frontmatter name/description plus Markdown body) rather than a
-- title/content pair. The origin columns ship now and stay fully NULL until the A03b
-- distillation entry point fills them, so that backfill needs no migration.

CREATE TABLE crewscope.team_skill (
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    skill_key VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    effective_revision BIGINT,
    latest_revision BIGINT NOT NULL,
    disable_reason VARCHAR(200),
    draft_content TEXT,
    source_task_execution_id UUID,
    source_execution_attempt INTEGER,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_team_skill_tenant_id
        UNIQUE (organization_id, team_id, id),
    CONSTRAINT uk_team_skill_tenant_key
        UNIQUE (organization_id, team_id, skill_key),
    CONSTRAINT fk_team_skill_team
        FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_team_skill_created_by
        FOREIGN KEY (created_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT fk_team_skill_updated_by
        FOREIGN KEY (updated_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT ck_team_skill_key CHECK (skill_key ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    CONSTRAINT ck_team_skill_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED')),
    -- Two-branch shape gate (mirrors TeamSkill.requireShape): a DRAFT never carries a
    -- pointer, a PUBLISHED head always does; a DISABLED head keeps whatever it had.
    CONSTRAINT ck_team_skill_effective_shape CHECK (
        (status <> 'DRAFT' OR effective_revision IS NULL)
        AND (status <> 'PUBLISHED' OR effective_revision IS NOT NULL)),
    -- The disable reason only rides on a DISABLED head (the third requireShape branch).
    CONSTRAINT ck_team_skill_disable_shape CHECK (
        disable_reason IS NULL OR status = 'DISABLED'),
    CONSTRAINT ck_team_skill_revision_span CHECK (
        effective_revision IS NULL
        OR (effective_revision >= 1 AND effective_revision <= latest_revision)),
    CONSTRAINT ck_team_skill_latest_revision CHECK (latest_revision >= 0),
    CONSTRAINT ck_team_skill_disable_reason CHECK (
        disable_reason IS NULL OR (BTRIM(disable_reason) <> '' AND length(disable_reason) <= 200)),
    CONSTRAINT ck_team_skill_draft_content CHECK (
        draft_content IS NULL OR (BTRIM(draft_content) <> '' AND length(draft_content) <= 65536)),
    -- Soft attribution pair, same-present/same-absent (V54 pattern): distillation
    -- attribution outlives the task lifecycle, so no foreign key, but the two columns
    -- always travel together once A03b starts writing them.
    CONSTRAINT ck_team_skill_origin_pair CHECK (
        (source_task_execution_id IS NULL) = (source_execution_attempt IS NULL)),
    CONSTRAINT ck_team_skill_origin_attempt CHECK (
        source_execution_attempt IS NULL OR source_execution_attempt >= 1),
    CONSTRAINT ck_team_skill_version CHECK (version >= 0),
    CONSTRAINT ck_team_skill_timestamps CHECK (updated_at >= created_at)
);

-- Keyset listing cursor: catalog pages order by skill_key inside the tenant.
CREATE INDEX ix_team_skill_team_listing
    ON crewscope.team_skill (organization_id, team_id, skill_key);

-- Partial index for the loading gate: only PUBLISHED heads are ever resolved by later
-- executions (A03b constructs ManifestSourceRef from the (key, revision, hash) triple).
CREATE INDEX ix_team_skill_effective
    ON crewscope.team_skill (organization_id, team_id, skill_key, effective_revision)
    WHERE status = 'PUBLISHED';

CREATE TABLE crewscope.team_skill_version (
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    skill_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    previous_revision BIGINT,
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    PRIMARY KEY (organization_id, team_id, skill_id, revision),
    -- Deliberately no unique constraint on content_hash: a rollback appends a new
    -- revision carrying the historical document verbatim, so equal digests across
    -- revisions of one skill are legal by design (ADR-031 §4).
    CONSTRAINT fk_team_skill_version_skill
        FOREIGN KEY (organization_id, team_id, skill_id)
        REFERENCES crewscope.team_skill (organization_id, team_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_team_skill_version_created_by
        FOREIGN KEY (created_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT ck_team_skill_version_sequence CHECK (revision >= 1),
    -- Chain rule, NULL-safe (V52 comment): COALESCE keeps a revision > 1 honest even
    -- though a bare NULL comparison would pass the CHECK.
    CONSTRAINT ck_team_skill_version_chain CHECK (
        (revision > 1 OR previous_revision IS NULL)
        AND COALESCE(previous_revision = revision - 1, revision = 1)),
    CONSTRAINT ck_team_skill_version_content
        CHECK (BTRIM(content) <> '' AND length(content) <= 65536),
    CONSTRAINT ck_team_skill_version_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_team_skill_version_skill_created
    ON crewscope.team_skill_version (skill_id, created_at DESC, revision DESC);

-- Backfill SKILL_MANAGE onto TEAM_ADMIN (TEAM_OWNER already received it in V52): the
-- M10-A03a grant widens skill authorship from owner-only to owner and admin, and
-- runtime grants reading from the database rows must stay aligned with the
-- BuiltInTeamRole definition. Idempotent JSONB union; custom roles are untouched.
UPDATE crewscope.team_role
SET permissions = permissions || '["SKILL_MANAGE"]'::jsonb,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE built_in
  AND role_key = 'TEAM_ADMIN'
  AND NOT permissions ? 'SKILL_MANAGE';
