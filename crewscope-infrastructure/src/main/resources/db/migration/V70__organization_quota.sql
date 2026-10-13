-- M12-A02: organization quota configuration, the monthly token reservation ledger and the
-- task intake COUNT support index (ADR-037 sections 1-4, S01 freeze 3.2/3.3).
--
-- organization_quota_configuration follows the V68 organization_setting typed-column
-- precedent: no seed row, the default state is "no row" (both dimensions unconfigured),
-- materialized by the application on first write; NULL per dimension means that dimension
-- is not configured and the admission path skips it entirely (zero-overhead freeze: no
-- advisory lock unless the intake limit exists, no ledger row unless the token budget
-- exists). Limits enforce >= 1 because zero/negative is a configuration error, never a
-- "block everything" switch.
--
-- The intake dimension has NO counter column on purpose: crewscope.task is the
-- authoritative count (ClaimQuota precedent — counters drift, authoritative rows do not).
-- A cancellation or failure intentionally does not give the slot back: the month's intake
-- counts admitted tasks, not surviving ones.
--
-- The token ledger carries the commitment semantics frozen in S01: reserved at admission
-- for the task's creationPolicy budget upper bound, never refunded at terminal state, and
-- each reporting month starts from a fresh zero row (no rollover). Reservations happen
-- only inside the admission transaction, so a rollback (crash included) leaves no row
-- behind — there is no orphan surface to reconcile.
CREATE TABLE crewscope.organization_quota_configuration (
    organization_id UUID NOT NULL,
    monthly_task_intake_limit INT,
    monthly_token_budget BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    PRIMARY KEY (organization_id),
    CONSTRAINT fk_organization_quota_configuration_organization FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_quota_configuration_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_quota_configuration_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_organization_quota_configuration_intake CHECK (
        monthly_task_intake_limit IS NULL OR monthly_task_intake_limit >= 1
    ),
    CONSTRAINT ck_organization_quota_configuration_token CHECK (
        monthly_token_budget IS NULL OR monthly_token_budget >= 1
    ),
    CONSTRAINT ck_organization_quota_configuration_version CHECK (version >= 0),
    CONSTRAINT ck_organization_quota_configuration_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE crewscope.organization_quota_token_ledger (
    organization_id UUID NOT NULL,
    usage_month CHAR(7) NOT NULL,
    reserved_tokens BIGINT NOT NULL DEFAULT 0,
    reservation_sequence BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    PRIMARY KEY (organization_id, usage_month),
    CONSTRAINT fk_organization_quota_token_ledger_organization FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_quota_token_ledger_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_quota_token_ledger_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_organization_quota_token_ledger_month CHECK (
        usage_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'
    ),
    CONSTRAINT ck_organization_quota_token_ledger_counters CHECK (
        reserved_tokens >= 0
        AND reservation_sequence >= 0
        AND version >= 0
        AND updated_at >= created_at
    )
);

-- Monthly intake COUNT support (claim-style authoritative count over crewscope.task):
-- the intake check counts the org's tasks created inside the reporting-month window, so
-- the (organization_id, created_at) prefix is the whole access path. The ledger's PK
-- already serves org+month lookups; no extra ledger index.
CREATE INDEX ix_task_organization_created
    ON crewscope.task (organization_id, created_at);
