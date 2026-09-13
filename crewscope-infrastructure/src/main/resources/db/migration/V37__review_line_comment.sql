CREATE TABLE crewscope.review_line_comment (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID NOT NULL,
    task_execution_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    review_request_id UUID NOT NULL,
    file_path TEXT NOT NULL,
    side VARCHAR(8) NOT NULL,
    line_number INTEGER NOT NULL,
    hunk_header VARCHAR(1000) NOT NULL,
    line_content_hash CHAR(64) NOT NULL,
    diff_generation BIGINT NOT NULL,
    content TEXT NOT NULL,
    author_principal_id UUID NOT NULL,
    anchor_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_review_line_comment_idempotency UNIQUE (organization_id, idempotency_key),
    CONSTRAINT fk_review_line_comment_request FOREIGN KEY (organization_id, team_id, workspace_id, project_id, task_id, task_execution_id, review_request_id)
        REFERENCES crewscope.review_request (organization_id, team_id, workspace_id, project_id, task_id, task_execution_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_line_comment_author FOREIGN KEY (organization_id, author_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_line_comment_created_by FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_line_comment_updated_by FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_line_comment_shape CHECK (
        attempt BETWEEN 1 AND 100 AND line_number > 0 AND diff_generation > 0 AND version >= 0
        AND side IN ('OLD', 'NEW') AND anchor_state IN ('ACTIVE', 'OUTDATED')
        AND line_content_hash ~ '^[0-9a-f]{64}$' AND OCTET_LENGTH(content) BETWEEN 1 AND 80000
        AND updated_at >= created_at
    )
);

CREATE INDEX ix_review_line_comment_request ON crewscope.review_line_comment
    (organization_id, review_request_id, file_path, line_number, id);
CREATE INDEX ix_review_line_comment_execution ON crewscope.review_line_comment
    (organization_id, task_execution_id, id);
