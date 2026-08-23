-- M5 model and Agent configuration schema. Immutable catalog/configuration revisions are stored
-- separately from mutable lifecycle roots, and every tenant relation carries its Organization
-- coordinate so PostgreSQL rejects cross-scope references before an Adapter can materialize them.

CREATE TABLE crewscope.model_provider_definition (
    provider_key VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    adapter_key VARCHAR(64) NOT NULL,
    default_endpoint VARCHAR(2048) NOT NULL,
    available_regions JSONB NOT NULL,
    retention_mode VARCHAR(32) NOT NULL,
    maximum_retention_seconds BIGINT,
    training_usage_policy VARCHAR(32) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lifecycle_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_model_provider_definition_hash UNIQUE (provider_key, content_hash),
    CONSTRAINT fk_model_provider_definition_created_by
        FOREIGN KEY (created_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT fk_model_provider_definition_updated_by
        FOREIGN KEY (updated_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT ck_model_provider_definition_keys CHECK (
        provider_key ~ '^[a-z][a-z0-9-]{0,63}$'
        AND adapter_key ~ '^[a-z][a-z0-9._-]{0,63}$'
        AND BTRIM(display_name) <> ''
    ),
    CONSTRAINT ck_model_provider_definition_endpoint CHECK (
        default_endpoint ~ '^https?://[^/?#@]+(?:/[^?#]*)?$'
    ),
    CONSTRAINT ck_model_provider_definition_regions CHECK (
        JSONB_TYPEOF(available_regions) = 'array'
        AND JSONB_ARRAY_LENGTH(available_regions) > 0
        AND JSONB_ARRAY_LENGTH(available_regions) <= 200
    ),
    CONSTRAINT ck_model_provider_definition_data_policy CHECK (
        (retention_mode = 'TIME_BOUND'
            AND maximum_retention_seconds BETWEEN 1 AND 315360000)
        OR (retention_mode IN ('NONE', 'PROVIDER_MANAGED')
            AND maximum_retention_seconds IS NULL)
    ),
    CONSTRAINT ck_model_provider_definition_training CHECK (
        training_usage_policy IN ('PROHIBITED', 'EXPLICIT_OPT_IN', 'PROVIDER_DEFAULT')
    ),
    CONSTRAINT ck_model_provider_definition_status CHECK (
        status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')
    ),
    CONSTRAINT ck_model_provider_definition_hash CHECK (
        content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_model_provider_definition_version CHECK (lifecycle_version >= 0),
    CONSTRAINT ck_model_provider_definition_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_model_provider_definition_status
    ON crewscope.model_provider_definition (status, display_name, provider_key);

CREATE TABLE crewscope.model_catalog_entry (
    id UUID PRIMARY KEY,
    provider_key VARCHAR(64) NOT NULL,
    provider_definition_hash CHAR(64) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    catalog_revision BIGINT NOT NULL,
    previous_catalog_revision BIGINT,
    model_revision VARCHAR(128) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    context_window_tokens BIGINT NOT NULL,
    maximum_output_tokens BIGINT NOT NULL,
    capabilities JSONB NOT NULL,
    available_regions JSONB NOT NULL,
    content_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lifecycle_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_model_catalog_entry_coordinate
        UNIQUE (provider_key, model_id, catalog_revision),
    CONSTRAINT uk_model_catalog_entry_id_coordinate
        UNIQUE (id, provider_key, model_id, catalog_revision),
    CONSTRAINT uk_model_catalog_entry_exact
        UNIQUE (
            id, provider_key, model_id, catalog_revision,
            provider_definition_hash, content_hash
        ),
    CONSTRAINT fk_model_catalog_entry_provider
        FOREIGN KEY (provider_key, provider_definition_hash)
        REFERENCES crewscope.model_provider_definition (provider_key, content_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_model_catalog_entry_created_by
        FOREIGN KEY (created_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT fk_model_catalog_entry_updated_by
        FOREIGN KEY (updated_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT ck_model_catalog_entry_key CHECK (
        model_id ~ '^[A-Za-z0-9][A-Za-z0-9._:/+\\-]{0,127}$'
        AND model_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:/+\\-]{0,127}$'
        AND BTRIM(display_name) <> ''
    ),
    CONSTRAINT ck_model_catalog_entry_revision CHECK (
        catalog_revision > 0
        AND ((catalog_revision = 1 AND previous_catalog_revision IS NULL)
            OR (catalog_revision > 1
                AND previous_catalog_revision = catalog_revision - 1))
    ),
    CONSTRAINT ck_model_catalog_entry_tokens CHECK (
        context_window_tokens > 0
        AND maximum_output_tokens > 0
        AND maximum_output_tokens <= context_window_tokens
    ),
    CONSTRAINT ck_model_catalog_entry_collections CHECK (
        JSONB_TYPEOF(capabilities) = 'array'
        AND JSONB_ARRAY_LENGTH(capabilities) <= 200
        AND JSONB_TYPEOF(available_regions) = 'array'
        AND JSONB_ARRAY_LENGTH(available_regions) > 0
        AND JSONB_ARRAY_LENGTH(available_regions) <= 200
    ),
    CONSTRAINT ck_model_catalog_entry_hashes CHECK (
        provider_definition_hash ~ '^[0-9a-f]{64}$'
        AND content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_model_catalog_entry_status CHECK (
        status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')
    ),
    CONSTRAINT ck_model_catalog_entry_lifecycle_version CHECK (lifecycle_version >= 0),
    CONSTRAINT ck_model_catalog_entry_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_model_catalog_entry_selectable
    ON crewscope.model_catalog_entry (
        provider_key, status, model_id, catalog_revision DESC, id
    );

CREATE INDEX ix_model_catalog_entry_capabilities
    ON crewscope.model_catalog_entry USING GIN (capabilities);

CREATE TABLE crewscope.model_price_revision (
    catalog_entry_id UUID NOT NULL,
    provider_key VARCHAR(64) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    catalog_revision BIGINT NOT NULL,
    price_revision BIGINT NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    input_per_million_tokens NUMERIC(24, 12) NOT NULL,
    output_per_million_tokens NUMERIC(24, 12) NOT NULL,
    cached_input_per_million_tokens NUMERIC(24, 12),
    currency_code CHAR(3) NOT NULL,
    price_source VARCHAR(500) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    PRIMARY KEY (catalog_entry_id, price_revision),
    CONSTRAINT uk_model_price_revision_effective
        UNIQUE (catalog_entry_id, effective_from),
    CONSTRAINT fk_model_price_revision_catalog
        FOREIGN KEY (catalog_entry_id, provider_key, model_id, catalog_revision)
        REFERENCES crewscope.model_catalog_entry (
            id, provider_key, model_id, catalog_revision
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_model_price_revision_created_by
        FOREIGN KEY (created_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT ck_model_price_revision_sequence CHECK (price_revision > 0),
    CONSTRAINT ck_model_price_revision_amounts CHECK (
        input_per_million_tokens >= 0
        AND output_per_million_tokens >= 0
        AND (cached_input_per_million_tokens IS NULL
            OR cached_input_per_million_tokens >= 0)
    ),
    CONSTRAINT ck_model_price_revision_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_model_price_revision_source CHECK (BTRIM(price_source) <> ''),
    CONSTRAINT ck_model_price_revision_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_model_price_revision_time CHECK (effective_from <= created_at)
);

CREATE INDEX ix_model_price_revision_effective_lookup
    ON crewscope.model_price_revision (
        catalog_entry_id, effective_from DESC, price_revision DESC
    );

-- The existing CredentialStore version is the exact envelope version used by a Connection.
ALTER TABLE crewscope.credential_secret
    ADD CONSTRAINT uk_credential_secret_model_binding
        UNIQUE (organization_id, id, subject_type, subject_id, version);

CREATE TABLE crewscope.model_connection (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    provider_key VARCHAR(64) NOT NULL,
    provider_definition_hash CHAR(64) NOT NULL,
    owner_type VARCHAR(32) NOT NULL,
    owner_id UUID NOT NULL,
    owner_team_id UUID,
    owner_user_principal_id UUID,
    endpoint VARCHAR(2048) NOT NULL,
    region VARCHAR(64) NOT NULL,
    credential_id UUID NOT NULL,
    credential_subject_type VARCHAR(32) NOT NULL,
    credential_subject_id UUID NOT NULL,
    credential_version BIGINT NOT NULL,
    billing_subject_type VARCHAR(32) NOT NULL,
    billing_subject_id UUID NOT NULL,
    billing_team_id UUID,
    billing_principal_id UUID,
    status VARCHAR(32) NOT NULL,
    health_status VARCHAR(32) NOT NULL,
    health_credential_version BIGINT NOT NULL,
    health_checked_at TIMESTAMPTZ,
    last_healthy_at TIMESTAMPTZ,
    consecutive_failures INTEGER NOT NULL,
    health_failure_code VARCHAR(64),
    revocation_reason VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_model_connection_scope_id UNIQUE (organization_id, id),
    CONSTRAINT uk_model_connection_exact
        UNIQUE (organization_id, id, provider_key, provider_definition_hash),
    CONSTRAINT fk_model_connection_organization
        FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_model_connection_provider
        FOREIGN KEY (provider_key, provider_definition_hash)
        REFERENCES crewscope.model_provider_definition (provider_key, content_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_model_connection_owner_team
        FOREIGN KEY (organization_id, owner_team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_model_connection_owner_user
        FOREIGN KEY (organization_id, owner_user_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_model_connection_credential
        FOREIGN KEY (
            organization_id, credential_id, credential_subject_type,
            credential_subject_id, credential_version
        ) REFERENCES crewscope.credential_secret (
            organization_id, id, subject_type, subject_id, version
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_model_connection_billing_team
        FOREIGN KEY (organization_id, billing_team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_model_connection_billing_principal
        FOREIGN KEY (organization_id, billing_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_model_connection_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_model_connection_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_model_connection_owner_shape CHECK (
        (owner_type = 'ORGANIZATION' AND owner_id = organization_id
            AND owner_team_id IS NULL AND owner_user_principal_id IS NULL)
        OR (owner_type = 'TEAM' AND owner_id = owner_team_id
            AND owner_team_id IS NOT NULL AND owner_user_principal_id IS NULL)
        OR (owner_type = 'USER' AND owner_id = owner_user_principal_id
            AND owner_team_id IS NULL AND owner_user_principal_id IS NOT NULL)
    ),
    CONSTRAINT ck_model_connection_credential_scope CHECK (
        (owner_type = 'USER'
            AND credential_subject_type = 'PRINCIPAL'
            AND credential_subject_id = owner_user_principal_id)
        OR (owner_type = 'TEAM'
            AND ((credential_subject_type = 'TEAM'
                    AND credential_subject_id = owner_team_id)
                OR (credential_subject_type = 'ORGANIZATION'
                    AND credential_subject_id = organization_id)))
        OR (owner_type = 'ORGANIZATION'
            AND credential_subject_type = 'ORGANIZATION'
            AND credential_subject_id = organization_id)
    ),
    CONSTRAINT ck_model_connection_billing_shape CHECK (
        (billing_subject_type = 'ORGANIZATION'
            AND billing_subject_id = organization_id
            AND billing_team_id IS NULL AND billing_principal_id IS NULL)
        OR (billing_subject_type = 'TEAM'
            AND billing_subject_id = billing_team_id
            AND billing_team_id IS NOT NULL AND billing_principal_id IS NULL)
        OR (billing_subject_type = 'PRINCIPAL'
            AND billing_subject_id = billing_principal_id
            AND billing_team_id IS NULL AND billing_principal_id IS NOT NULL)
    ),
    CONSTRAINT ck_model_connection_billing_access CHECK (
        (owner_type = 'USER'
            AND billing_subject_type = 'PRINCIPAL'
            AND billing_subject_id = owner_user_principal_id)
        OR (owner_type = 'TEAM'
            AND (billing_subject_type = 'ORGANIZATION'
                OR (billing_subject_type = 'TEAM'
                    AND billing_subject_id = owner_team_id)))
        OR (owner_type = 'ORGANIZATION'
            AND billing_subject_type = 'ORGANIZATION')
    ),
    CONSTRAINT ck_model_connection_endpoint CHECK (
        endpoint ~ '^https?://[^/?#@]+(?:/[^?#]*)?$'
    ),
    CONSTRAINT ck_model_connection_region CHECK (region ~ '^[a-z][a-z0-9-]{0,63}$'),
    CONSTRAINT ck_model_connection_status CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')
    ),
    CONSTRAINT ck_model_connection_health CHECK (
        health_credential_version = credential_version
        AND consecutive_failures >= 0
        AND ((health_status = 'UNKNOWN' AND health_checked_at IS NULL
                AND consecutive_failures = 0 AND health_failure_code IS NULL)
            OR (health_status = 'HEALTHY' AND health_checked_at IS NOT NULL
                AND last_healthy_at = health_checked_at
                AND consecutive_failures = 0 AND health_failure_code IS NULL)
            OR (health_status = 'UNHEALTHY' AND health_checked_at IS NOT NULL
                AND consecutive_failures > 0
                AND health_failure_code IN (
                    'AUTHENTICATION_FAILED', 'ENDPOINT_UNREACHABLE', 'TIMEOUT',
                    'RATE_LIMITED', 'PROVIDER_REJECTED', 'POLICY_REJECTED')))
    ),
    CONSTRAINT ck_model_connection_revocation CHECK (
        (status = 'REVOKED' AND revocation_reason IN (
            'OWNER_REQUESTED', 'CREDENTIAL_REVOKED', 'PROVIDER_DISABLED',
            'POLICY_REVOKED', 'SECURITY_INCIDENT'))
        OR (status <> 'REVOKED' AND revocation_reason IS NULL)
    ),
    CONSTRAINT ck_model_connection_version CHECK (version >= 0),
    CONSTRAINT ck_model_connection_timestamps CHECK (
        updated_at >= created_at
        AND (health_checked_at IS NULL OR health_checked_at >= created_at)
        AND (last_healthy_at IS NULL OR last_healthy_at >= created_at)
    )
);

CREATE INDEX ix_model_connection_owner_status
    ON crewscope.model_connection (
        organization_id, owner_type, owner_id, status, updated_at DESC, id
    );

CREATE INDEX ix_model_connection_provider_health
    ON crewscope.model_connection (
        organization_id, provider_key, status, health_status, updated_at DESC, id
    );

-- Template keys are resolved inside an explicit publisher scope. The exact content hash is carried
-- by configurations so a later Team override cannot silently replace a historical definition.
CREATE TABLE crewscope.agent_template_version (
    organization_id UUID NOT NULL,
    publisher_type VARCHAR(32) NOT NULL,
    publisher_id UUID NOT NULL,
    publisher_team_id UUID,
    template_key VARCHAR(64) NOT NULL,
    template_version BIGINT NOT NULL,
    previous_template_version BIGINT,
    runtime_role VARCHAR(32) NOT NULL,
    allowed_ownership_types JSONB NOT NULL,
    allowed_execution_scopes JSONB NOT NULL,
    declared_capabilities JSONB NOT NULL,
    required_model_capabilities JSONB NOT NULL,
    capability_hash CHAR(64) NOT NULL,
    system_prompt_baseline TEXT NOT NULL,
    allowed_tools JSONB NOT NULL,
    approved_skill_keys JSONB NOT NULL,
    structured_output_schema TEXT,
    structured_output_schema_hash CHAR(64),
    member_configurable_slots JSONB NOT NULL,
    administrator_configurable_slots JSONB NOT NULL,
    policy_hash CHAR(64) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lifecycle_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    PRIMARY KEY (
        organization_id, publisher_type, publisher_id,
        template_key, template_version
    ),
    CONSTRAINT uk_agent_template_version_exact
        UNIQUE (
            organization_id, template_key, template_version,
            content_hash
        ),
    CONSTRAINT fk_agent_template_version_organization
        FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_template_version_team
        FOREIGN KEY (organization_id, publisher_team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_template_version_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_template_version_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_template_version_publisher CHECK (
        (publisher_type = 'ORGANIZATION' AND publisher_id = organization_id
            AND publisher_team_id IS NULL)
        OR (publisher_type = 'TEAM' AND publisher_id = publisher_team_id
            AND publisher_team_id IS NOT NULL)
    ),
    CONSTRAINT ck_agent_template_version_key CHECK (
        template_key ~ '^[a-z][a-z0-9-]{0,63}$'
    ),
    CONSTRAINT ck_agent_template_version_sequence CHECK (
        template_version > 0
        AND ((template_version = 1 AND previous_template_version IS NULL)
            OR (template_version > 1
                AND previous_template_version = template_version - 1))
    ),
    CONSTRAINT ck_agent_template_version_role CHECK (
        runtime_role IN ('PERSONAL_ASSISTANT', 'TEAM_COORDINATOR', 'SPECIALIST')
    ),
    CONSTRAINT ck_agent_template_version_collections CHECK (
        JSONB_TYPEOF(allowed_ownership_types) = 'array'
        AND JSONB_ARRAY_LENGTH(allowed_ownership_types) > 0
        AND JSONB_TYPEOF(allowed_execution_scopes) = 'array'
        AND JSONB_ARRAY_LENGTH(allowed_execution_scopes) > 0
        AND JSONB_TYPEOF(declared_capabilities) = 'array'
        AND JSONB_ARRAY_LENGTH(declared_capabilities) > 0
        AND JSONB_TYPEOF(required_model_capabilities) = 'array'
        AND JSONB_TYPEOF(allowed_tools) = 'array'
        AND JSONB_TYPEOF(approved_skill_keys) = 'array'
        AND JSONB_TYPEOF(member_configurable_slots) = 'array'
        AND JSONB_TYPEOF(administrator_configurable_slots) = 'array'
    ),
    CONSTRAINT ck_agent_template_version_schema CHECK (
        (structured_output_schema IS NULL AND structured_output_schema_hash IS NULL)
        OR (structured_output_schema IS NOT NULL
            AND BTRIM(structured_output_schema) <> ''
            AND structured_output_schema_hash ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_agent_template_version_prompt CHECK (
        BTRIM(system_prompt_baseline) <> ''
        AND CHAR_LENGTH(system_prompt_baseline) <= 65536
        AND (structured_output_schema IS NULL
            OR CHAR_LENGTH(structured_output_schema) <= 131072)
    ),
    CONSTRAINT ck_agent_template_version_hashes CHECK (
        capability_hash ~ '^[0-9a-f]{64}$'
        AND policy_hash ~ '^[0-9a-f]{64}$'
        AND content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_agent_template_version_status CHECK (
        status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')
    ),
    CONSTRAINT ck_agent_template_version_lifecycle CHECK (
        lifecycle_version >= 0 AND updated_at >= created_at
    )
);

CREATE INDEX ix_agent_template_version_catalog
    ON crewscope.agent_template_version (
        organization_id, publisher_type, publisher_id,
        template_key, status, template_version DESC
    );

CREATE INDEX ix_agent_template_version_capabilities
    ON crewscope.agent_template_version USING GIN (declared_capabilities);

-- Existing M2-M4 profiles receive the deterministic projection frozen by M5-D01. The nullable
-- add/backfill/not-null sequence preserves every stable Profile ID, Principal, version and audit
-- timestamp while making all M5 ownership/runtime/template coordinates explicit.
ALTER TABLE crewscope.agent_profile
    ADD COLUMN ownership_type VARCHAR(32),
    ADD COLUMN ownership_team_id UUID,
    ADD COLUMN runtime_role VARCHAR(32),
    ADD COLUMN template_key VARCHAR(64),
    ADD COLUMN template_version BIGINT;

UPDATE crewscope.agent_profile
SET ownership_type = CASE
        WHEN profile_type = 'PERSONAL' THEN 'USER'
        WHEN profile_type = 'TEAM' THEN 'TEAM'
        WHEN owner_member_id IS NOT NULL THEN 'USER'
        ELSE 'TEAM'
    END,
    ownership_team_id = team_id,
    runtime_role = CASE profile_type
        WHEN 'PERSONAL' THEN 'PERSONAL_ASSISTANT'
        WHEN 'TEAM' THEN 'TEAM_COORDINATOR'
        ELSE 'SPECIALIST'
    END,
    template_key = CASE profile_type
        WHEN 'PERSONAL' THEN 'personal-assistant'
        WHEN 'TEAM' THEN 'team-coordinator'
        ELSE 'coding'
    END,
    template_version = 1;

ALTER TABLE crewscope.agent_profile
    ALTER COLUMN ownership_type SET NOT NULL,
    ALTER COLUMN runtime_role SET NOT NULL,
    ALTER COLUMN template_key SET NOT NULL,
    ALTER COLUMN template_version SET NOT NULL,
    ADD CONSTRAINT fk_agent_profile_ownership_team
        FOREIGN KEY (organization_id, ownership_team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_agent_profile_ownership_shape CHECK (
        (ownership_type = 'USER' AND ownership_team_id = team_id
            AND owner_member_id IS NOT NULL)
        OR (ownership_type = 'TEAM' AND ownership_team_id = team_id
            AND owner_member_id IS NULL)
        OR (ownership_type = 'ORGANIZATION' AND ownership_team_id IS NULL
            AND owner_member_id IS NULL)
    ),
    ADD CONSTRAINT ck_agent_profile_runtime_shape CHECK (
        (runtime_role = 'PERSONAL_ASSISTANT' AND profile_type = 'PERSONAL'
            AND ownership_type = 'USER' AND default_profile)
        OR (runtime_role = 'TEAM_COORDINATOR' AND profile_type = 'TEAM'
            AND NOT default_profile)
        OR (runtime_role = 'SPECIALIST' AND profile_type = 'SPECIALIST'
            AND NOT default_profile)
    ),
    ADD CONSTRAINT ck_agent_profile_template_coordinate CHECK (
        template_key ~ '^[a-z][a-z0-9-]{0,63}$' AND template_version > 0
    ),
    ADD CONSTRAINT uk_agent_profile_organization_id UNIQUE (organization_id, id);

CREATE INDEX ix_agent_profile_template_status
    ON crewscope.agent_profile (
        organization_id, template_key, template_version, status, updated_at DESC, id
    );

CREATE INDEX ix_agent_profile_ownership_status
    ON crewscope.agent_profile (
        organization_id, ownership_type, ownership_team_id,
        owner_member_id, status, updated_at DESC, id
    );

-- During a rolling deployment a V19 node can still insert the legacy column set. Project only a
-- completely omitted M5 shape; partial or explicit forged coordinates continue into the checks
-- above and fail closed. This function can remain after all nodes upgrade because current Adapters
-- always provide the five explicit fields and therefore bypass the compatibility branch.
CREATE FUNCTION crewscope.project_legacy_agent_profile_v20()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.ownership_type IS NULL
            AND NEW.runtime_role IS NULL
            AND NEW.template_key IS NULL
            AND NEW.template_version IS NULL THEN
        NEW.ownership_type := CASE
            WHEN NEW.profile_type = 'PERSONAL' THEN 'USER'
            WHEN NEW.profile_type = 'TEAM' THEN 'TEAM'
            WHEN NEW.owner_member_id IS NOT NULL THEN 'USER'
            ELSE 'TEAM'
        END;
        NEW.ownership_team_id := NEW.team_id;
        NEW.runtime_role := CASE NEW.profile_type
            WHEN 'PERSONAL' THEN 'PERSONAL_ASSISTANT'
            WHEN 'TEAM' THEN 'TEAM_COORDINATOR'
            ELSE 'SPECIALIST'
        END;
        NEW.template_key := CASE NEW.profile_type
            WHEN 'PERSONAL' THEN 'personal-assistant'
            WHEN 'TEAM' THEN 'team-coordinator'
            ELSE 'coding'
        END;
        NEW.template_version := 1;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER project_legacy_agent_profile_v20
    BEFORE INSERT ON crewscope.agent_profile
    FOR EACH ROW
    EXECUTE FUNCTION crewscope.project_legacy_agent_profile_v20();

CREATE TABLE crewscope.agent_configuration_version (
    organization_id UUID NOT NULL,
    agent_profile_id UUID NOT NULL,
    ownership_type VARCHAR(32) NOT NULL,
    ownership_team_id UUID,
    owner_member_id UUID,
    owner_user_principal_id UUID,
    template_key VARCHAR(64) NOT NULL,
    template_version BIGINT NOT NULL,
    template_content_hash CHAR(64) NOT NULL,
    configuration_revision BIGINT NOT NULL,
    previous_configuration_revision BIGINT,
    supplemental_instructions TEXT,
    enabled_tools JSONB NOT NULL,
    structured_output_schema_hash CHAR(64),
    approved_skill_keys JSONB NOT NULL,
    memory_policy_id UUID,
    memory_policy_version BIGINT,
    budget_policy_id UUID,
    budget_policy_version BIGINT,
    policy_pack_id UUID NOT NULL,
    policy_pack_version BIGINT NOT NULL,
    generate_temperature NUMERIC(5, 4),
    generate_top_p NUMERIC(5, 4),
    generate_maximum_output_tokens BIGINT,
    generate_reasoning_mode VARCHAR(32) NOT NULL,
    generate_cache_enabled BOOLEAN NOT NULL,
    generate_parallel_tool_calls BOOLEAN NOT NULL,
    generate_seed BIGINT,
    generate_maximum_attempts INTEGER NOT NULL,
    configuration_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    PRIMARY KEY (organization_id, agent_profile_id, configuration_revision),
    CONSTRAINT uk_agent_configuration_version_hash
        UNIQUE (organization_id, agent_profile_id, configuration_revision, configuration_hash),
    CONSTRAINT fk_agent_configuration_version_profile
        FOREIGN KEY (organization_id, agent_profile_id)
        REFERENCES crewscope.agent_profile (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_configuration_version_ownership_team
        FOREIGN KEY (organization_id, ownership_team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_configuration_version_owner_member
        FOREIGN KEY (organization_id, ownership_team_id, owner_member_id)
        REFERENCES crewscope.team_member (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_configuration_version_owner_user
        FOREIGN KEY (organization_id, owner_user_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_configuration_version_template
        FOREIGN KEY (
            organization_id, template_key, template_version, template_content_hash
        ) REFERENCES crewscope.agent_template_version (
            organization_id, template_key, template_version, content_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_configuration_version_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_configuration_version_sequence CHECK (
        configuration_revision > 0
        AND ((configuration_revision = 1
                AND previous_configuration_revision IS NULL)
            OR (configuration_revision > 1
                AND previous_configuration_revision = configuration_revision - 1))
    ),
    CONSTRAINT ck_agent_configuration_version_ownership CHECK (
        (ownership_type = 'USER' AND ownership_team_id IS NOT NULL
            AND owner_member_id IS NOT NULL AND owner_user_principal_id IS NOT NULL)
        OR (ownership_type = 'TEAM' AND ownership_team_id IS NOT NULL
            AND owner_member_id IS NULL AND owner_user_principal_id IS NULL)
        OR (ownership_type = 'ORGANIZATION' AND ownership_team_id IS NULL
            AND owner_member_id IS NULL AND owner_user_principal_id IS NULL)
    ),
    CONSTRAINT ck_agent_configuration_version_collections CHECK (
        JSONB_TYPEOF(enabled_tools) = 'array'
        AND JSONB_ARRAY_LENGTH(enabled_tools) <= 200
        AND JSONB_TYPEOF(approved_skill_keys) = 'array'
        AND JSONB_ARRAY_LENGTH(approved_skill_keys) <= 200
    ),
    CONSTRAINT ck_agent_configuration_version_optional_policies CHECK (
        (memory_policy_id IS NULL AND memory_policy_version IS NULL)
        OR (memory_policy_id IS NOT NULL AND memory_policy_version > 0)
    ),
    CONSTRAINT ck_agent_configuration_version_budget_policy CHECK (
        (budget_policy_id IS NULL AND budget_policy_version IS NULL)
        OR (budget_policy_id IS NOT NULL AND budget_policy_version > 0)
    ),
    CONSTRAINT ck_agent_configuration_version_generate_options CHECK (
        (generate_temperature IS NULL
            OR generate_temperature BETWEEN 0 AND 2)
        AND (generate_top_p IS NULL
            OR (generate_top_p > 0 AND generate_top_p <= 1))
        AND (generate_maximum_output_tokens IS NULL
            OR generate_maximum_output_tokens BETWEEN 1 AND 10000000)
        AND generate_reasoning_mode IN ('DEFAULT', 'ENABLED', 'DISABLED')
        AND generate_maximum_attempts BETWEEN 1 AND 10
    ),
    CONSTRAINT ck_agent_configuration_version_values CHECK (
        template_version > 0
        AND policy_pack_version >= 0
        AND (supplemental_instructions IS NULL
            OR (BTRIM(supplemental_instructions) <> ''
                AND CHAR_LENGTH(supplemental_instructions) <= 16384))
        AND (structured_output_schema_hash IS NULL
            OR structured_output_schema_hash ~ '^[0-9a-f]{64}$')
        AND template_content_hash ~ '^[0-9a-f]{64}$'
        AND configuration_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_agent_configuration_version_latest
    ON crewscope.agent_configuration_version (
        organization_id, agent_profile_id, configuration_revision DESC
    );

-- One row exists for each execution scope declared by a configuration revision. DIRECT rows carry
-- exact Primary and optional Fallback Connection/Catalog snapshots; inherited and orchestration
-- rows deliberately carry no model selection.
CREATE TABLE crewscope.agent_configuration_model_binding (
    organization_id UUID NOT NULL,
    agent_profile_id UUID NOT NULL,
    configuration_revision BIGINT NOT NULL,
    execution_scope VARCHAR(32) NOT NULL,
    binding_kind VARCHAR(32) NOT NULL,
    primary_connection_id UUID,
    primary_provider_key VARCHAR(64),
    primary_provider_definition_hash CHAR(64),
    primary_catalog_entry_id UUID,
    primary_model_id VARCHAR(128),
    primary_catalog_revision BIGINT,
    primary_catalog_content_hash CHAR(64),
    fallback_connection_id UUID,
    fallback_provider_key VARCHAR(64),
    fallback_provider_definition_hash CHAR(64),
    fallback_catalog_entry_id UUID,
    fallback_model_id VARCHAR(128),
    fallback_catalog_revision BIGINT,
    fallback_catalog_content_hash CHAR(64),
    PRIMARY KEY (
        organization_id, agent_profile_id,
        configuration_revision, execution_scope
    ),
    CONSTRAINT fk_agent_configuration_model_binding_configuration
        FOREIGN KEY (organization_id, agent_profile_id, configuration_revision)
        REFERENCES crewscope.agent_configuration_version (
            organization_id, agent_profile_id, configuration_revision
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_configuration_model_binding_primary_connection
        FOREIGN KEY (
            organization_id, primary_connection_id,
            primary_provider_key, primary_provider_definition_hash
        ) REFERENCES crewscope.model_connection (
            organization_id, id, provider_key, provider_definition_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_configuration_model_binding_primary_catalog
        FOREIGN KEY (
            primary_catalog_entry_id, primary_provider_key, primary_model_id,
            primary_catalog_revision, primary_provider_definition_hash,
            primary_catalog_content_hash
        ) REFERENCES crewscope.model_catalog_entry (
            id, provider_key, model_id, catalog_revision,
            provider_definition_hash, content_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_configuration_model_binding_fallback_connection
        FOREIGN KEY (
            organization_id, fallback_connection_id,
            fallback_provider_key, fallback_provider_definition_hash
        ) REFERENCES crewscope.model_connection (
            organization_id, id, provider_key, provider_definition_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_configuration_model_binding_fallback_catalog
        FOREIGN KEY (
            fallback_catalog_entry_id, fallback_provider_key, fallback_model_id,
            fallback_catalog_revision, fallback_provider_definition_hash,
            fallback_catalog_content_hash
        ) REFERENCES crewscope.model_catalog_entry (
            id, provider_key, model_id, catalog_revision,
            provider_definition_hash, content_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_configuration_model_binding_scope CHECK (
        execution_scope IN ('PERSONAL', 'TEAM')
    ),
    CONSTRAINT ck_agent_configuration_model_binding_shape CHECK (
        (binding_kind = 'DIRECT'
            AND primary_connection_id IS NOT NULL
            AND primary_provider_key IS NOT NULL
            AND primary_provider_definition_hash IS NOT NULL
            AND primary_catalog_entry_id IS NOT NULL
            AND primary_model_id IS NOT NULL
            AND primary_catalog_revision IS NOT NULL
            AND primary_catalog_content_hash IS NOT NULL)
        OR (binding_kind IN ('INHERIT_TEAM_DEFAULT', 'ORCHESTRATION_ONLY')
            AND execution_scope = 'TEAM'
            AND primary_connection_id IS NULL
            AND primary_provider_key IS NULL
            AND primary_provider_definition_hash IS NULL
            AND primary_catalog_entry_id IS NULL
            AND primary_model_id IS NULL
            AND primary_catalog_revision IS NULL
            AND primary_catalog_content_hash IS NULL
            AND fallback_connection_id IS NULL)
    ),
    CONSTRAINT ck_agent_configuration_model_binding_fallback CHECK (
        (fallback_connection_id IS NULL
            AND fallback_provider_key IS NULL
            AND fallback_provider_definition_hash IS NULL
            AND fallback_catalog_entry_id IS NULL
            AND fallback_model_id IS NULL
            AND fallback_catalog_revision IS NULL
            AND fallback_catalog_content_hash IS NULL)
        OR (binding_kind = 'DIRECT'
            AND fallback_connection_id IS NOT NULL
            AND fallback_provider_key IS NOT NULL
            AND fallback_provider_definition_hash IS NOT NULL
            AND fallback_catalog_entry_id IS NOT NULL
            AND fallback_model_id IS NOT NULL
            AND fallback_catalog_revision IS NOT NULL
            AND fallback_catalog_content_hash IS NOT NULL
            AND (fallback_connection_id, fallback_catalog_entry_id)
                IS DISTINCT FROM (primary_connection_id, primary_catalog_entry_id))
    )
);

CREATE INDEX ix_agent_configuration_model_binding_connection
    ON crewscope.agent_configuration_model_binding (
        organization_id, primary_connection_id, agent_profile_id,
        configuration_revision DESC
    ) WHERE primary_connection_id IS NOT NULL;

CREATE TABLE crewscope.agent_model_default (
    organization_id UUID NOT NULL,
    default_scope_type VARCHAR(32) NOT NULL,
    default_scope_id UUID NOT NULL,
    team_id UUID,
    template_key VARCHAR(64) NOT NULL,
    template_version BIGINT NOT NULL,
    template_content_hash CHAR(64) NOT NULL,
    execution_scope VARCHAR(32) NOT NULL,
    default_revision BIGINT NOT NULL,
    previous_default_revision BIGINT,
    primary_connection_id UUID NOT NULL,
    primary_provider_key VARCHAR(64) NOT NULL,
    primary_provider_definition_hash CHAR(64) NOT NULL,
    primary_catalog_entry_id UUID NOT NULL,
    primary_model_id VARCHAR(128) NOT NULL,
    primary_catalog_revision BIGINT NOT NULL,
    primary_catalog_content_hash CHAR(64) NOT NULL,
    fallback_connection_id UUID,
    fallback_provider_key VARCHAR(64),
    fallback_provider_definition_hash CHAR(64),
    fallback_catalog_entry_id UUID,
    fallback_model_id VARCHAR(128),
    fallback_catalog_revision BIGINT,
    fallback_catalog_content_hash CHAR(64),
    policy_pack_id UUID NOT NULL,
    policy_pack_version BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    PRIMARY KEY (
        organization_id, default_scope_type, default_scope_id,
        template_key, template_version, execution_scope, default_revision
    ),
    CONSTRAINT uk_agent_model_default_hash UNIQUE (
        organization_id, default_scope_type, default_scope_id,
        template_key, template_version, execution_scope,
        default_revision, content_hash
    ),
    CONSTRAINT fk_agent_model_default_organization
        FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_model_default_team
        FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_model_default_template
        FOREIGN KEY (
            organization_id, template_key, template_version, template_content_hash
        ) REFERENCES crewscope.agent_template_version (
            organization_id, template_key, template_version, content_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_model_default_primary_connection
        FOREIGN KEY (
            organization_id, primary_connection_id,
            primary_provider_key, primary_provider_definition_hash
        ) REFERENCES crewscope.model_connection (
            organization_id, id, provider_key, provider_definition_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_model_default_primary_catalog
        FOREIGN KEY (
            primary_catalog_entry_id, primary_provider_key, primary_model_id,
            primary_catalog_revision, primary_provider_definition_hash,
            primary_catalog_content_hash
        ) REFERENCES crewscope.model_catalog_entry (
            id, provider_key, model_id, catalog_revision,
            provider_definition_hash, content_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_model_default_fallback_connection
        FOREIGN KEY (
            organization_id, fallback_connection_id,
            fallback_provider_key, fallback_provider_definition_hash
        ) REFERENCES crewscope.model_connection (
            organization_id, id, provider_key, provider_definition_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_model_default_fallback_catalog
        FOREIGN KEY (
            fallback_catalog_entry_id, fallback_provider_key, fallback_model_id,
            fallback_catalog_revision, fallback_provider_definition_hash,
            fallback_catalog_content_hash
        ) REFERENCES crewscope.model_catalog_entry (
            id, provider_key, model_id, catalog_revision,
            provider_definition_hash, content_hash
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_model_default_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_model_default_scope CHECK (
        (default_scope_type = 'ORGANIZATION'
            AND default_scope_id = organization_id AND team_id IS NULL)
        OR (default_scope_type = 'TEAM'
            AND default_scope_id = team_id AND team_id IS NOT NULL)
    ),
    CONSTRAINT ck_agent_model_default_sequence CHECK (
        default_revision > 0
        AND ((default_revision = 1 AND previous_default_revision IS NULL)
            OR (default_revision > 1
                AND previous_default_revision = default_revision - 1))
    ),
    CONSTRAINT ck_agent_model_default_values CHECK (
        execution_scope IN ('PERSONAL', 'TEAM')
        AND template_version > 0
        AND policy_pack_version >= 0
        AND template_content_hash ~ '^[0-9a-f]{64}$'
        AND content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_agent_model_default_fallback CHECK (
        (fallback_connection_id IS NULL
            AND fallback_provider_key IS NULL
            AND fallback_provider_definition_hash IS NULL
            AND fallback_catalog_entry_id IS NULL
            AND fallback_model_id IS NULL
            AND fallback_catalog_revision IS NULL
            AND fallback_catalog_content_hash IS NULL)
        OR (fallback_connection_id IS NOT NULL
            AND fallback_provider_key IS NOT NULL
            AND fallback_provider_definition_hash IS NOT NULL
            AND fallback_catalog_entry_id IS NOT NULL
            AND fallback_model_id IS NOT NULL
            AND fallback_catalog_revision IS NOT NULL
            AND fallback_catalog_content_hash IS NOT NULL
            AND (fallback_connection_id, fallback_catalog_entry_id)
                IS DISTINCT FROM (primary_connection_id, primary_catalog_entry_id))
    )
);

CREATE INDEX ix_agent_model_default_latest
    ON crewscope.agent_model_default (
        organization_id, default_scope_type, default_scope_id,
        template_key, execution_scope, default_revision DESC
    );

-- V1 snapshots remain byte-for-byte valid. New M5 snapshots set schema_version=2 and persist the
-- exact resolved non-secret configuration object; its canonical hash remains snapshot_hash.
ALTER TABLE crewscope.policy_snapshot
    ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN agent_execution_configuration JSONB,
    ADD CONSTRAINT ck_policy_snapshot_schema_version CHECK (schema_version IN (1, 2)),
    ADD CONSTRAINT ck_policy_snapshot_configuration_shape CHECK (
        (schema_version = 1 AND agent_execution_configuration IS NULL)
        OR (schema_version = 2
            AND agent_execution_configuration IS NOT NULL
            AND JSONB_TYPEOF(agent_execution_configuration) = 'object')
    );

-- Session rows pin the explicit template projection. Configuration is nullable only for upgraded
-- M2-M4 sessions; all new M5 execution factories pin its revision and canonical hash together.
ALTER TABLE crewscope.agent_runtime_session
    ADD COLUMN agent_ownership_type VARCHAR(32),
    ADD COLUMN agent_runtime_role VARCHAR(32),
    ADD COLUMN agent_template_key VARCHAR(64),
    ADD COLUMN agent_template_version BIGINT,
    ADD COLUMN agent_configuration_revision BIGINT,
    ADD COLUMN agent_configuration_hash CHAR(64);

UPDATE crewscope.agent_runtime_session session
SET agent_ownership_type = profile.ownership_type,
    agent_runtime_role = profile.runtime_role,
    agent_template_key = profile.template_key,
    agent_template_version = profile.template_version
FROM crewscope.agent_profile profile
WHERE session.organization_id = profile.organization_id
  AND session.team_id = profile.team_id
  AND session.workspace_id = profile.workspace_id
  AND session.agent_profile_id = profile.id;

ALTER TABLE crewscope.agent_runtime_session
    ADD CONSTRAINT ck_agent_runtime_session_m5_profile CHECK (
        (agent_ownership_type IS NULL
            AND agent_runtime_role IS NULL
            AND agent_template_key IS NULL
            AND agent_template_version IS NULL
            AND agent_configuration_revision IS NULL
            AND agent_configuration_hash IS NULL)
        OR (agent_ownership_type IN ('USER', 'TEAM', 'ORGANIZATION')
            AND agent_runtime_role IN (
                'PERSONAL_ASSISTANT', 'TEAM_COORDINATOR', 'SPECIALIST')
            AND agent_template_key ~ '^[a-z][a-z0-9-]{0,63}$'
            AND agent_template_version > 0
            AND ((agent_configuration_revision IS NULL
                    AND agent_configuration_hash IS NULL)
                OR (agent_configuration_revision > 0
                    AND agent_configuration_hash ~ '^[0-9a-f]{64}$')))
    );

CREATE INDEX ix_agent_runtime_session_configuration
    ON crewscope.agent_runtime_session (
        organization_id, agent_profile_id, agent_configuration_revision,
        status, updated_at DESC, id
    ) WHERE agent_configuration_revision IS NOT NULL;
