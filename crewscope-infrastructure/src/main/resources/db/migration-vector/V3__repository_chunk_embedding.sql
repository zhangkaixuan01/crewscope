-- M10-I01b: repository chunk embedding vectors (vector chain, references V56).
-- One row per (generation, chunk sequence): the generation pins the exact index
-- coordinate — commit, chunking policy, embedding model revision — so vectors of
-- different generations never mix, and the retention purge inside the activation
-- transaction deletes a generation's vectors together with its generation row.
-- Vectors cross the wire as text literals (see PgVectorKnowledgeEmbeddingStore); the
-- content column exists for recall-side assembly (A01/I02), not for search.
CREATE TABLE crewscope.repository_chunk_embedding (
    index_key CHAR(64) NOT NULL,
    build_sequence BIGINT NOT NULL,
    chunk_seq INT NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    repository_binding_id UUID NOT NULL,
    path VARCHAR(1024) NOT NULL,
    language VARCHAR(40) NOT NULL,
    start_line INT NOT NULL,
    end_line INT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    content TEXT NOT NULL,
    model_key VARCHAR(128) NOT NULL,
    model_revision BIGINT NOT NULL,
    embedding public.vector(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (index_key, build_sequence, chunk_seq),
    CONSTRAINT fk_repository_chunk_generation
        FOREIGN KEY (index_key, build_sequence)
        REFERENCES crewscope.repository_index_generation (index_key, build_sequence)
        ON DELETE RESTRICT,
    CONSTRAINT ck_repository_chunk_seq CHECK (chunk_seq >= 1),
    CONSTRAINT ck_repository_chunk_span CHECK (start_line >= 1 AND end_line >= start_line),
    CONSTRAINT ck_repository_chunk_hash CHECK (
        content_hash ~ '^[0-9a-f]{64}$' AND index_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_repository_chunk_path CHECK (
        btrim(path) <> '' AND length(path) <= 1024),
    CONSTRAINT ck_repository_chunk_language CHECK (
        btrim(language) <> '' AND length(language) <= 40),
    CONSTRAINT ck_repository_chunk_model CHECK (
        model_revision >= 1 AND btrim(model_key) <> '' AND length(model_key) <= 128)
);

CREATE INDEX ix_repository_chunk_embedding_hnsw
    ON crewscope.repository_chunk_embedding USING hnsw (embedding public.vector_cosine_ops);

CREATE INDEX ix_repository_chunk_embedding_scope
    ON crewscope.repository_chunk_embedding (organization_id, team_id, repository_binding_id);
