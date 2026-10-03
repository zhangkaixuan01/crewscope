-- M10-I01b: repository index generations (pure PostgreSQL, no vector).
-- One row per (index coordinate, build sequence). The ACTIVE row itself is the
-- activation pointer: a partial unique index allows exactly one ACTIVE generation per
-- index_key, so activation is a single-transaction status flip with no side table to
-- drift. The decomposed six-tuple columns reconstitute the typed key; the vector rows
-- of each generation live in the vector chain (V3) and reference this table.
CREATE TABLE crewscope.repository_index_generation (
    index_key CHAR(64) NOT NULL,
    build_sequence BIGINT NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    repository_binding_id UUID NOT NULL,
    source_commit VARCHAR(64) NOT NULL,
    chunk_policy_hash CHAR(64) NOT NULL,
    model_key VARCHAR(128) NOT NULL,
    model_dimension INT NOT NULL,
    model_revision BIGINT NOT NULL,
    job_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    activated_at TIMESTAMPTZ,
    activated_by_principal_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (index_key, build_sequence),
    CONSTRAINT fk_repository_generation_job
        FOREIGN KEY (job_id) REFERENCES crewscope.knowledge_index_job (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_repository_generation_team
        FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_repository_generation_activated_by
        FOREIGN KEY (activated_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT ck_repository_generation_status
        CHECK (status IN ('BUILDING', 'VALIDATING', 'ACTIVE', 'RETIRED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_repository_generation_active_shape CHECK (
        (status = 'ACTIVE') = (activated_at IS NOT NULL)
        AND (activated_at IS NULL) = (activated_by_principal_id IS NULL)),
    CONSTRAINT ck_repository_generation_sequence CHECK (build_sequence >= 1),
    CONSTRAINT ck_repository_generation_commit CHECK (
        source_commit ~ '^[0-9a-f]{40}$|^[0-9a-f]{64}$'),
    CONSTRAINT ck_repository_generation_hash CHECK (
        chunk_policy_hash ~ '^[0-9a-f]{64}$' AND index_key ~ '^[0-9a-f]{64}$'),
    -- 1024 mirrors TeamEmbeddingService.EMBEDDING_DIMENSION — the dimension S01 froze
    -- for the embedding contract; a new model family needs a new migration, not a toggle.
    CONSTRAINT ck_repository_generation_model CHECK (
        model_dimension = 1024 AND model_revision >= 1
        AND btrim(model_key) <> '' AND length(model_key) <= 128),
    CONSTRAINT ck_repository_generation_timestamps CHECK (updated_at >= created_at)
);

-- The activation pointer: exactly one ACTIVE generation per index coordinate.
CREATE UNIQUE INDEX ux_repository_generation_one_active
    ON crewscope.repository_index_generation (index_key)
    WHERE status = 'ACTIVE';

-- Team-scoped listing and retention scans.
CREATE INDEX ix_repository_generation_team
    ON crewscope.repository_index_generation (organization_id, team_id, repository_binding_id);
