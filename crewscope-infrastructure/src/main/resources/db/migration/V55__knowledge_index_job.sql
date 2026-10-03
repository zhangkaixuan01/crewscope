-- M10-I01b: durable knowledge-index jobs and their batch checkpoints (pure PostgreSQL).
-- One table serves both sources with a discriminator and a mutually exclusive target
-- shape: KNOWLEDGE_ENTRY targets re-read the effective revision at claim time (the
-- authoritative gate), REPOSITORY targets freeze the full six-tuple index key at
-- enqueue time. The index key is stored as the SHA-256 of the canonical form because
-- the canonical form embeds NUL separators that PostgreSQL text cannot carry; the
-- decomposed columns reconstitute the typed key.
-- claim_token is the monotonic fencing counter (V30 precedent): claim bumps it, every
-- worker write is guarded by it, and terminal states never carry a lease.

CREATE TABLE crewscope.knowledge_index_job (
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    source VARCHAR(20) NOT NULL,
    entry_id UUID,
    project_id UUID,
    repository_binding_id UUID,
    source_commit VARCHAR(64),
    chunk_policy_hash CHAR(64),
    model_key VARCHAR(128),
    model_revision BIGINT,
    index_key CHAR(64),
    status VARCHAR(16) NOT NULL,
    attempt INT NOT NULL,
    chunks_done INT NOT NULL,
    chunks_total INT NOT NULL,
    failure_code VARCHAR(80),
    generation_build_sequence BIGINT NOT NULL DEFAULT 0,
    claimed_by VARCHAR(160),
    claim_token BIGINT NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMPTZ,
    created_by_principal_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_index_job_team
        FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_index_job_entry
        FOREIGN KEY (organization_id, team_id, entry_id)
        REFERENCES crewscope.knowledge_entry (organization_id, team_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_index_job_created_by
        FOREIGN KEY (created_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT ck_knowledge_index_job_source
        CHECK (source IN ('KNOWLEDGE_ENTRY', 'REPOSITORY')),
    CONSTRAINT ck_knowledge_index_job_status
        CHECK (status IN ('QUEUED', 'CHUNKING', 'EMBEDDING', 'ACTIVATING',
                          'READY', 'FAILED', 'CANCELLED')),
    -- Two-branch shape gate: a knowledge job carries exactly its entry, a repository
    -- job carries the project plus the decomposed index-key columns, never both.
    CONSTRAINT ck_knowledge_index_job_target CHECK (
        (source = 'KNOWLEDGE_ENTRY'
            AND entry_id IS NOT NULL
            AND project_id IS NULL AND repository_binding_id IS NULL
            AND source_commit IS NULL AND chunk_policy_hash IS NULL
            AND model_key IS NULL AND model_revision IS NULL
            AND index_key IS NULL)
        OR (source = 'REPOSITORY'
            AND entry_id IS NULL
            AND project_id IS NOT NULL AND repository_binding_id IS NOT NULL
            AND source_commit IS NOT NULL AND chunk_policy_hash IS NOT NULL
            AND model_key IS NOT NULL AND model_revision IS NOT NULL
            AND index_key IS NOT NULL)),
    CONSTRAINT ck_knowledge_index_job_failure_shape CHECK (
        (status IN ('FAILED', 'CANCELLED')) = (failure_code IS NOT NULL)),
    CONSTRAINT ck_knowledge_index_job_counts CHECK (
        chunks_done >= 0 AND chunks_total >= 0 AND chunks_done <= chunks_total),
    CONSTRAINT ck_knowledge_index_job_attempt CHECK (
        attempt >= 0 AND claim_token >= 0 AND generation_build_sequence >= 0),
    CONSTRAINT ck_knowledge_index_job_commit CHECK (
        source_commit IS NULL OR source_commit ~ '^[0-9a-f]{40}$|^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_index_job_hash CHECK (
        chunk_policy_hash IS NULL OR chunk_policy_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_index_job_index_key CHECK (
        index_key IS NULL OR index_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_index_job_model CHECK (
        model_revision IS NULL OR (model_revision >= 1 AND btrim(model_key) <> '')),
    CONSTRAINT ck_knowledge_index_job_lease_pair CHECK (
        (claimed_by IS NULL) = (lease_expires_at IS NULL)
        AND (claimed_by IS NULL OR (btrim(claimed_by) <> '' AND length(claimed_by) <= 160))),
    CONSTRAINT ck_knowledge_index_job_terminal_lease CHECK (
        status NOT IN ('READY', 'FAILED', 'CANCELLED')
        OR (claimed_by IS NULL AND lease_expires_at IS NULL)),
    CONSTRAINT ck_knowledge_index_job_claimed_lease CHECK (
        status IN ('QUEUED', 'READY', 'FAILED', 'CANCELLED')
        OR (claimed_by IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CONSTRAINT ck_knowledge_index_job_timestamps CHECK (updated_at >= created_at)
);

-- One live job per knowledge entry: enqueue idempotency for the invalidation consumer.
CREATE UNIQUE INDEX ux_knowledge_index_job_entry_live
    ON crewscope.knowledge_index_job (organization_id, team_id, entry_id)
    WHERE source = 'KNOWLEDGE_ENTRY'
      AND status IN ('QUEUED', 'CHUNKING', 'EMBEDDING', 'ACTIVATING');

-- One live job per repository index coordinate.
CREATE UNIQUE INDEX ux_knowledge_index_job_index_key_live
    ON crewscope.knowledge_index_job (index_key)
    WHERE source = 'REPOSITORY'
      AND status IN ('QUEUED', 'CHUNKING', 'EMBEDDING', 'ACTIVATING');

-- Claim scan: live jobs by lease state, oldest first (CTE FOR UPDATE SKIP LOCKED).
CREATE INDEX ix_knowledge_index_job_claim
    ON crewscope.knowledge_index_job (status, lease_expires_at, created_at, id)
    WHERE status IN ('QUEUED', 'CHUNKING', 'EMBEDDING', 'ACTIVATING');

-- indexStatus projection: the latest knowledge-entry job regardless of state.
CREATE INDEX ix_knowledge_index_job_entry_latest
    ON crewscope.knowledge_index_job (organization_id, team_id, entry_id, updated_at)
    WHERE source = 'KNOWLEDGE_ENTRY';

-- Batch checkpoints: (job, chunk_seq) resume anchors written only while the claim
-- token still owns the job; chunk_seq is the first sequence of the committed batch.
CREATE TABLE crewscope.knowledge_index_checkpoint (
    job_id UUID NOT NULL,
    chunk_seq INT NOT NULL,
    claim_token BIGINT NOT NULL,
    chunk_count INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, chunk_seq),
    CONSTRAINT fk_knowledge_index_checkpoint_job
        FOREIGN KEY (job_id) REFERENCES crewscope.knowledge_index_job (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_knowledge_index_checkpoint_seq CHECK (
        chunk_seq >= 1 AND chunk_count >= 1 AND claim_token >= 0)
);
