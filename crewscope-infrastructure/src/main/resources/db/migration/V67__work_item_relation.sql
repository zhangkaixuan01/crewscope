-- M11-D01: WorkGraph relation edges (ADR-033).
-- BLOCKS(src, dst) means src blocks dst and is the only canonical stored direction;
-- DEPENDS_ON(X, Y) is normalized by the application into BLOCKS(Y, X) before insert, so this
-- table never holds a second blocking direction. SPLIT_FROM(child, source) shares the table
-- (child on the src side) and stays out of scheduling cycle detection.
-- The primary concurrency defense is the application transaction protocol: lock both WorkProject
-- rows with IN (:p1,:p2) ORDER BY id FOR UPDATE, probe with a recursive CTE inside the lock,
-- then insert. The constraints below are the database backstop: the forward unique index rejects
-- duplicate same-direction edges, the undirected partial unique index (least/greatest over both
-- endpoints, BLOCKS only) rejects an opposite blocking edge — plain ordered unique indexes on
-- (src,dst) and (dst,src) cannot do that, since the two rows key as (A,B) and (B,A) in both —
-- the partial unique index keeps the split forest single-parented, and CHECKs reject self edges.
-- A direct SPLIT_FROM ring (A from B while B from A) has distinct children on both sides, so it
-- stays protocol-enforced like every longer cycle. Cascade deletion is the application's
-- same-transaction edge removal (with events), so the FKs stay ON DELETE RESTRICT like other
-- product tables.
CREATE TABLE crewscope.work_item_relation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    src_workspace_id UUID NOT NULL,
    src_project_id UUID NOT NULL,
    src_work_item_id UUID NOT NULL,
    dst_workspace_id UUID NOT NULL,
    dst_project_id UUID NOT NULL,
    dst_work_item_id UUID NOT NULL,
    relation_kind VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_work_item_relation_scope_id UNIQUE (organization_id, team_id, id),
    CONSTRAINT fk_work_item_relation_src
        FOREIGN KEY (organization_id, team_id, src_workspace_id, src_project_id, src_work_item_id)
        REFERENCES crewscope.work_item (organization_id, team_id, workspace_id, project_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_work_item_relation_dst
        FOREIGN KEY (organization_id, team_id, dst_workspace_id, dst_project_id, dst_work_item_id)
        REFERENCES crewscope.work_item (organization_id, team_id, workspace_id, project_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_work_item_relation_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_item_relation_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_work_item_relation_kind CHECK (relation_kind IN ('BLOCKS', 'SPLIT_FROM')),
    CONSTRAINT ck_work_item_relation_not_self CHECK (src_work_item_id <> dst_work_item_id),
    CONSTRAINT ck_work_item_relation_immutable_audit CHECK (
        updated_at = created_at AND updated_by_principal_id = created_by_principal_id
    )
);

-- Directional backstop (ADR-033): the forward unique index rejects duplicate same-direction
-- edges and leads the forward adjacency lookups; the undirected partial unique index below is
-- what actually makes opposite blocking edges impossible to coexist (see header). Longer cycles
-- remain the protocol's job — they cannot be expressed as an index at all.
CREATE UNIQUE INDEX ux_work_item_relation_forward
    ON crewscope.work_item_relation (src_work_item_id, dst_work_item_id);
CREATE UNIQUE INDEX ux_work_item_relation_block_pair
    ON crewscope.work_item_relation (
        least(src_work_item_id, dst_work_item_id), greatest(src_work_item_id, dst_work_item_id)
    ) WHERE relation_kind = 'BLOCKS';
-- Reverse-direction adjacency (upstream/blocking-chain walks anchor on the dst column).
CREATE INDEX ix_work_item_relation_reverse
    ON crewscope.work_item_relation (dst_work_item_id, src_work_item_id);
-- Split forest: at most one SPLIT_FROM source per child (the child sits on the src side).
CREATE UNIQUE INDEX ux_work_item_relation_split_child
    ON crewscope.work_item_relation (src_work_item_id) WHERE relation_kind = 'SPLIT_FROM';
-- Team-level edge listing and graph query projections.
CREATE INDEX ix_work_item_relation_team_kind
    ON crewscope.work_item_relation (
        organization_id, team_id, relation_kind, created_at DESC, id DESC
    );
