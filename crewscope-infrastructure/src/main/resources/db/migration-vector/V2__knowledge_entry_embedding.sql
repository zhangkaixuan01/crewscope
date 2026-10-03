-- M10-I01a: knowledge entry embedding vectors (vectors + coordinates only).
-- Index lifecycle columns (index_status, generation bookkeeping) ship with I01b; this
-- table is deliberately minimal. One row per (tenant, entry revision, model slot): a
-- changed model revision of the same slot overwrites the row, so vectors of a superseded
-- model revision are never mixed into a search of the new one. Retrieval joins the
-- authoritative knowledge_entry head pointer before the vector top-K (ADR-030 §10.2).
CREATE TABLE crewscope.knowledge_entry_embedding (
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    entry_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    model_key VARCHAR(128) NOT NULL,
    dimension INT NOT NULL,
    model_revision BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    embedding public.vector(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, team_id, entry_id, revision, model_key, dimension),
    CONSTRAINT fk_knowledge_embedding_version
        FOREIGN KEY (organization_id, team_id, entry_id, revision)
        REFERENCES crewscope.knowledge_entry_version (organization_id, team_id, entry_id, revision)
        ON DELETE RESTRICT,
    CONSTRAINT ck_knowledge_embedding_dimension CHECK (dimension = 1024),
    CONSTRAINT ck_knowledge_embedding_revision CHECK (model_revision >= 1),
    CONSTRAINT ck_knowledge_embedding_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_knowledge_entry_embedding_hnsw
    ON crewscope.knowledge_entry_embedding USING hnsw (embedding public.vector_cosine_ops);

CREATE INDEX ix_knowledge_entry_embedding_entry
    ON crewscope.knowledge_entry_embedding (organization_id, team_id, entry_id);
