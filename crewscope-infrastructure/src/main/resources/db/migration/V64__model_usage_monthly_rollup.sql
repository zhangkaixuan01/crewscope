-- M10-F03: monthly model-usage rollup and soft budget alerts (pure PostgreSQL, no vector).
--
-- The rollup is a deterministic rebuildable projection over MODEL_USAGE_FACT_RECORDED
-- domain events (S01 §3.9): one row per (organization, team?, usage month, role,
-- provider/model, price point, attempt) with atomically incremented token counters and
-- costs. Deliberately no foreign keys: the projection is deleted and recomputed by the
-- rebuild endpoint, so it must neither block catalog/price governance nor depend on the
-- team row (organization-scoped probe facts have no team at all).
--
-- Pricing semantics (contract §"rebuildability"): the projection resolves the price at
-- projection time from the event's occurred_at, never snapshotting event-time prices —
-- a published price revision therefore shows up after the next rebuild, and the price
-- triple is part of the grain so revisions split into separate rows naturally. Rows
-- with no resolvable price carry the ISO 4217 inactive-code sentinel 'XXX' plus NULL
-- costs and a NULL price triple: unpriced tokens are counted, never billed as zero.

CREATE TABLE crewscope.model_usage_monthly_rollup (
    organization_id UUID NOT NULL,
    team_id UUID,
    usage_month CHAR(7) NOT NULL,
    role VARCHAR(16) NOT NULL,
    provider_key VARCHAR(64) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    catalog_entry_id UUID,
    catalog_revision BIGINT,
    price_revision BIGINT,
    attempt INTEGER NOT NULL,
    input_tokens BIGINT NOT NULL,
    output_tokens BIGINT NOT NULL,
    cached_input_tokens BIGINT NOT NULL,
    input_cost NUMERIC(24, 12),
    output_cost NUMERIC(24, 12),
    cached_input_cost NUMERIC(24, 12),
    fact_count BIGINT NOT NULL,
    unreported_fact_count BIGINT NOT NULL,
    first_fact_at TIMESTAMPTZ NOT NULL,
    last_fact_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_rollup_usage_month CHECK (
        usage_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_rollup_role CHECK (
        role IN ('CHAT_PRIMARY', 'CHAT_FALLBACK', 'COMPACTION', 'EMBEDDING', 'DISTILLATION')),
    CONSTRAINT ck_rollup_attempt CHECK (attempt >= 1),
    CONSTRAINT ck_rollup_tokens CHECK (
        input_tokens >= 0 AND output_tokens >= 0 AND cached_input_tokens >= 0),
    CONSTRAINT ck_rollup_costs CHECK (
        (input_cost IS NULL OR input_cost >= 0)
        AND (output_cost IS NULL OR output_cost >= 0)
        AND (cached_input_cost IS NULL OR cached_input_cost >= 0)),
    -- 'XXX' (no resolvable price) and a priced row are mutually exclusive shapes:
    -- an unpriced row never carries costs or a price triple, a priced row always does.
    CONSTRAINT ck_rollup_unpriced_shape CHECK (
        (currency_code = 'XXX')
        = (input_cost IS NULL AND output_cost IS NULL AND cached_input_cost IS NULL)),
    CONSTRAINT ck_rollup_priced_shape CHECK (
        (currency_code <> 'XXX')
        = (catalog_entry_id IS NOT NULL
            AND catalog_revision IS NOT NULL
            AND price_revision IS NOT NULL)),
    CONSTRAINT ck_rollup_fact_counts CHECK (
        fact_count >= 0 AND unreported_fact_count >= 0
            AND unreported_fact_count <= fact_count),
    CONSTRAINT ck_rollup_timestamps CHECK (first_fact_at <= last_fact_at)
);

-- Grain uniqueness over nullable dimensions: COALESCE pins the NULL team (organization
-- scoped probe facts) and the NULL price triple (unpriced rows) to fixed sentinels. The
-- upsert's ON CONFLICT clause repeats these expressions verbatim for index inference.
CREATE UNIQUE INDEX ux_model_usage_rollup_grain
    ON crewscope.model_usage_monthly_rollup (
        organization_id,
        COALESCE(team_id, '00000000-0000-0000-0000-000000000000'::uuid),
        usage_month,
        role,
        provider_key,
        model_id,
        currency_code,
        COALESCE(catalog_entry_id, '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(catalog_revision, -1),
        COALESCE(price_revision, -1),
        attempt);

-- Team cost queries: newest month first inside one team.
CREATE INDEX ix_model_usage_rollup_team_month
    ON crewscope.model_usage_monthly_rollup (organization_id, team_id, usage_month DESC);

-- Budget scans sweep the current month across every team of an organization.
CREATE INDEX ix_model_usage_rollup_org_month
    ON crewscope.model_usage_monthly_rollup (organization_id, usage_month);

-- Soft budget alert ledger (contract: "a reminder, never a quota"). One row per
-- (team, month, kind, level) is inserted with ON CONFLICT DO NOTHING; only a successful
-- insert emits TEAM_BUDGET_ALERT_RECORDED, so repeated scans converge on one event.
-- Unlike the rollup this is authoritative reminder evidence and keeps its team FK.
CREATE TABLE crewscope.team_budget_alert (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    usage_month CHAR(7) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    level VARCHAR(16) NOT NULL,
    metric_value NUMERIC(24, 12) NOT NULL,
    threshold NUMERIC(24, 12) NOT NULL,
    currency_code CHAR(3),
    detected_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_team_budget_alert_dedup
        UNIQUE (organization_id, team_id, usage_month, kind, level),
    CONSTRAINT fk_team_budget_alert_team
        FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_team_budget_alert_month CHECK (
        usage_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_team_budget_alert_kind CHECK (kind IN ('TOKEN', 'AMOUNT')),
    CONSTRAINT ck_team_budget_alert_level CHECK (level IN ('WARNING', 'EXCEEDED')),
    CONSTRAINT ck_team_budget_alert_values CHECK (
        metric_value >= 0 AND threshold > 0),
    -- Only an amount alert is denominated; a token alert never carries a currency.
    CONSTRAINT ck_team_budget_alert_currency_shape CHECK (
        (kind = 'AMOUNT') = (currency_code IS NOT NULL))
);

CREATE INDEX ix_team_budget_alert_team_month
    ON crewscope.team_budget_alert (organization_id, team_id, usage_month DESC);

-- The rebuild scans the canonical log by (event_type, event_id) keyset in batches; the
-- existing domain_event indexes (event_id PK, subject keyset) cannot serve that shape,
-- and without this index every batch degrades to a full-table scan as the log grows.
CREATE INDEX ix_domain_event_type_event_id
    ON crewscope.domain_event (event_type, event_id);
