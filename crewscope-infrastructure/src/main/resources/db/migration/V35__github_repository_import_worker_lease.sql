-- M8-Q02 Worker lease and authority snapshot for isolated GitHub repository imports.
ALTER TABLE crewscope.github_repository_import_job
    ADD COLUMN created_by_platform_administrator BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN lease_owner VARCHAR(160),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_github_import_lease_pair CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    );

CREATE INDEX ix_github_import_claim
    ON crewscope.github_repository_import_job (status, lease_expires_at, created_at)
    WHERE status IN ('REQUESTED', 'PREFLIGHTING', 'IMPORTING');
