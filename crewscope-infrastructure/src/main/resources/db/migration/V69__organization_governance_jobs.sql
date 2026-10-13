-- M12-A01: organization governance workflows (ADR-035, S01 freeze sections 3.4/3.6).
-- Controlled export and account deletion run as resumable jobs pinned at creation time, the
-- responsibility-handover job pattern (V45): one command id queues at most one job, each item
-- settles in its own short transaction, and an interrupted run resumes at the first PENDING
-- item without replaying a DONE step. The GET /data-exports/{id} manifest and the deletion item
-- list are the reconciliation surfaces required by the governance contract.
--
-- Export artifacts land as database rows on purpose (not in the content-addressed ArtifactStore):
-- the single-node database is itself the backup surface, so artifacts travel with pg_dump and
-- no DB/filesystem dual-write consistency face exists. Bounded spirit follows the audit export
-- 10k-row precedent — item row counts are tiered by resource width and each artifact is capped
-- at 4 MB by CHECK. Physical packaging (blob content) is a declared later increment; artifact
-- references export descriptors only.
--
-- Deletion is account-scoped (organization lifecycle is a hosted increment) and deliberately
-- keeps bare UUIDs for account_id/target_principal_id: they name the rows being removed, so a
-- foreign key would break the terminal DELETE_BINDING_CHAIN step. The two-step confirmation
-- (create + confirm with a username echo token) is enforced by the status vocabulary; the token
-- hash is the only confirmation fact stored.
CREATE TABLE crewscope.organization_data_export_job (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    requested_by_principal_id UUID NOT NULL,
    command_id VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    include_vectors BOOLEAN NOT NULL,
    manifest JSONB,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT fk_organization_data_export_job_organization FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_data_export_job_requester
        FOREIGN KEY (organization_id, requested_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_organization_data_export_job_status CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT ck_organization_data_export_job_manifest CHECK (
        (status = 'COMPLETED') = (manifest IS NOT NULL)
    ),
    CONSTRAINT ck_organization_data_export_job_version CHECK (version >= 0),
    CONSTRAINT ck_organization_data_export_job_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ux_organization_data_export_job_command UNIQUE (command_id)
);

CREATE INDEX ix_organization_data_export_job_organization_status
    ON crewscope.organization_data_export_job (organization_id, status);

CREATE TABLE crewscope.organization_data_export_item (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    job_id UUID NOT NULL,
    resource_family VARCHAR(64) NOT NULL,
    batch_sequence INT NOT NULL,
    sort_cursor VARCHAR(256),
    row_count INT NOT NULL,
    checksum CHAR(64),
    state VARCHAR(16) NOT NULL,
    error_code VARCHAR(100),
    processed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT fk_organization_data_export_item_job
        FOREIGN KEY (job_id) REFERENCES crewscope.organization_data_export_job (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_organization_data_export_item_state CHECK (
        state IN ('PENDING', 'DONE', 'SKIPPED')
    ),
    CONSTRAINT ck_organization_data_export_item_terminal_consistency CHECK (
        (state = 'PENDING') = (processed_at IS NULL AND checksum IS NULL
            AND error_code IS NULL)
    ),
    -- SKIPPED carries its reason (e.g. VECTOR_STORE_DISABLED): an export item failure is a
    -- data-plane fact reported to the manifest for reconciliation, not a job-blocking conflict.
    CONSTRAINT ck_organization_data_export_item_stopped_reason CHECK (
        state IN ('PENDING', 'DONE') OR error_code IS NOT NULL
    ),
    CONSTRAINT ck_organization_data_export_item_counts CHECK (row_count >= 0),
    CONSTRAINT ck_organization_data_export_item_version CHECK (version >= 0),
    CONSTRAINT ck_organization_data_export_item_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT uk_organization_data_export_item_batch
        UNIQUE (job_id, resource_family, batch_sequence)
);

CREATE INDEX ix_organization_data_export_item_job_state
    ON crewscope.organization_data_export_item (job_id, state);

CREATE TABLE crewscope.organization_data_export_artifact (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    job_id UUID NOT NULL,
    item_id UUID NOT NULL,
    artifact_sequence INT NOT NULL,
    -- TEXT, not JSONB: the content is a newline-framed stream of JSON lines and the checksum
    -- pins its exact bytes — JSONB normalization would silently break byte-level verification.
    content TEXT NOT NULL,
    byte_size INT NOT NULL,
    checksum CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    CONSTRAINT fk_organization_data_export_artifact_job
        FOREIGN KEY (job_id) REFERENCES crewscope.organization_data_export_job (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_organization_data_export_artifact_item
        FOREIGN KEY (item_id) REFERENCES crewscope.organization_data_export_item (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_organization_data_export_artifact_bytes CHECK (
        byte_size BETWEEN 1 AND 4194304
    ),
    -- The sequence numbers batches within one item; every family's export restarts it at
    -- zero, so uniqueness needs the item key alongside the job.
    CONSTRAINT uk_organization_data_export_artifact_sequence
        UNIQUE (job_id, item_id, artifact_sequence)
);

CREATE TABLE crewscope.organization_account_deletion_job (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    account_id UUID NOT NULL,
    target_principal_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    confirmation_token_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    command_id VARCHAR(200) NOT NULL,
    reservation_check JSONB NOT NULL,
    backup_retention_statement TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT fk_organization_account_deletion_job_organization FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT ck_organization_account_deletion_job_status CHECK (
        status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'RUNNING', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT ck_organization_account_deletion_job_version CHECK (version >= 0),
    CONSTRAINT ck_organization_account_deletion_job_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ux_organization_account_deletion_job_command UNIQUE (command_id)
);

CREATE INDEX ix_organization_account_deletion_job_organization_status
    ON crewscope.organization_account_deletion_job (organization_id, status);

CREATE TABLE crewscope.organization_account_deletion_item (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    job_id UUID NOT NULL,
    step_key VARCHAR(64) NOT NULL,
    target_ref VARCHAR(200) NOT NULL,
    expected_count INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    result_count INT,
    error_code VARCHAR(100),
    processed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT fk_organization_account_deletion_item_job
        FOREIGN KEY (job_id) REFERENCES crewscope.organization_account_deletion_job (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_organization_account_deletion_item_state CHECK (
        state IN ('PENDING', 'DONE', 'CONFLICT', 'DENIED')
    ),
    CONSTRAINT ck_organization_account_deletion_item_terminal_consistency CHECK (
        (state = 'PENDING') = (processed_at IS NULL AND result_count IS NULL
            AND error_code IS NULL)
    ),
    CONSTRAINT ck_organization_account_deletion_item_stopped_reason CHECK (
        state IN ('PENDING', 'DONE') OR error_code IS NOT NULL
    ),
    CONSTRAINT ck_organization_account_deletion_item_expected CHECK (expected_count >= 0),
    CONSTRAINT ck_organization_account_deletion_item_version CHECK (version >= 0),
    CONSTRAINT ck_organization_account_deletion_item_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT uk_organization_account_deletion_item_step
        UNIQUE (job_id, step_key, target_ref)
);

CREATE INDEX ix_organization_account_deletion_item_job_state
    ON crewscope.organization_account_deletion_item (job_id, state);

-- Audit retention carve-out (S01 freeze 3.4): audit_event stays append-only against UPDATE
-- (tamper resistance), while the retention sweep becomes the sanctioned DELETE face. The
-- SECURITY-category exception moves below the application layer: governance self-facts are
-- retained regardless of any retention window, enforced by trigger rather than by convention.
-- Replaces trg_audit_event_append_only_v27 (V27).
DROP TRIGGER trg_audit_event_append_only_v27 ON crewscope.audit_event;

CREATE FUNCTION crewscope.guard_audit_event_governance_m12a01()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'audit_event is append-only' USING ERRCODE = '23514';
    END IF;
    IF OLD.event_category = 'SECURITY' THEN
        RAISE EXCEPTION 'SECURITY audit facts outlive every retention window'
            USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_audit_event_governance_m12a01
    BEFORE UPDATE OR DELETE ON crewscope.audit_event
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_audit_event_governance_m12a01();
