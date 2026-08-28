-- Deployment-level accounts are deliberately independent from Organization authorization.
-- Mutable display values and canonical unique keys are stored together so PostgreSQL remains the
-- final arbiter for concurrent registrations.
CREATE TABLE crewscope.user_account (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    username_normalized VARCHAR(64) NOT NULL,
    email VARCHAR(254) NOT NULL,
    email_normalized VARCHAR(254) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    platform_role VARCHAR(32) NOT NULL DEFAULT 'USER',
    security_version BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_account_username_normalized UNIQUE (username_normalized),
    CONSTRAINT uk_user_account_email_normalized UNIQUE (email_normalized),
    CONSTRAINT ck_user_account_username CHECK (
        BTRIM(username) = username AND username <> ''
        AND BTRIM(username_normalized) = username_normalized
        AND username_normalized <> ''
        AND username_normalized = LOWER(username_normalized)
    ),
    CONSTRAINT ck_user_account_email CHECK (
        BTRIM(email) = email AND email <> ''
        AND BTRIM(email_normalized) = email_normalized
        AND email_normalized <> ''
        AND email_normalized = LOWER(email_normalized)
        AND POSITION('@' IN email_normalized) > 1
    ),
    CONSTRAINT ck_user_account_display_name CHECK (
        BTRIM(display_name) = display_name AND display_name <> ''
    ),
    CONSTRAINT ck_user_account_status CHECK (
        status IN ('ACTIVE', 'LOCKED', 'DISABLED', 'ARCHIVED')
    ),
    CONSTRAINT ck_user_account_platform_role CHECK (
        platform_role IN ('USER', 'OPERATOR')
    ),
    CONSTRAINT ck_user_account_versions CHECK (
        security_version >= 1 AND version >= 0
    ),
    CONSTRAINT ck_user_account_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_user_account_status_updated_v31
    ON crewscope.user_account (status, updated_at DESC, id DESC);

-- Provider and Subject are immutable identity coordinates. Local Subject is derived from the
-- Account UUID, while future OIDC providers retain their exact stable Subject value.
CREATE TABLE crewscope.login_identity (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    provider VARCHAR(100) NOT NULL,
    subject VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_authenticated_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_login_identity_provider_subject UNIQUE (provider, subject),
    CONSTRAINT uk_login_identity_account_provider UNIQUE (account_id, provider),
    CONSTRAINT fk_login_identity_account FOREIGN KEY (account_id)
        REFERENCES crewscope.user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_login_identity_provider CHECK (
        provider = LOWER(provider)
        AND provider ~ '^[a-z0-9]([a-z0-9._-]{0,62}[a-z0-9])?(/[a-z0-9]([a-z0-9._-]{0,62}[a-z0-9])?)*$'
    ),
    CONSTRAINT ck_login_identity_subject CHECK (
        BTRIM(subject) = subject AND subject <> ''
        AND CHAR_LENGTH(subject) <= 500 AND OCTET_LENGTH(subject) <= 1024
    ),
    CONSTRAINT ck_login_identity_local_subject CHECK (
        provider <> 'local' OR subject = account_id::TEXT
    ),
    CONSTRAINT ck_login_identity_status CHECK (
        status IN ('ACTIVE', 'DISABLED', 'REVOKED')
    ),
    CONSTRAINT ck_login_identity_version CHECK (version >= 0),
    CONSTRAINT ck_login_identity_timestamps CHECK (
        updated_at >= created_at
        AND (last_authenticated_at IS NULL
            OR (last_authenticated_at >= created_at
                AND last_authenticated_at <= updated_at))
    )
);

CREATE INDEX ix_login_identity_account_status_v31
    ON crewscope.login_identity (account_id, status, updated_at DESC, id DESC);

-- The encoded password Hash is kept in one restricted column. Generic account and audit readers
-- use local_credential_metadata, which intentionally cannot expose the Hash.
CREATE TABLE crewscope.local_credential (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    password_hash VARCHAR(2048) NOT NULL,
    algorithm VARCHAR(16) NOT NULL,
    credential_version BIGINT NOT NULL DEFAULT 1,
    password_changed_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_local_credential_account UNIQUE (account_id),
    CONSTRAINT fk_local_credential_account FOREIGN KEY (account_id)
        REFERENCES crewscope.user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_local_credential_algorithm CHECK (algorithm IN ('argon2id', 'bcrypt')),
    CONSTRAINT ck_local_credential_hash CHECK (
        password_hash ~ '^[!-~]+$'
        AND (
            (algorithm = 'argon2id'
                AND password_hash LIKE '{argon2id}%'
                AND CHAR_LENGTH(password_hash) > 26)
            OR (algorithm = 'bcrypt'
                AND password_hash LIKE '{bcrypt}%'
                AND CHAR_LENGTH(password_hash) > 24)
        )
    ),
    CONSTRAINT ck_local_credential_versions CHECK (
        credential_version >= 1 AND version >= 0
    ),
    CONSTRAINT ck_local_credential_timestamps CHECK (
        updated_at >= created_at
        AND password_changed_at >= created_at
        AND password_changed_at <= updated_at
    )
);

CREATE VIEW crewscope.local_credential_metadata AS
SELECT
    id,
    account_id,
    algorithm,
    credential_version,
    password_changed_at,
    version,
    created_at,
    updated_at
FROM crewscope.local_credential;

REVOKE ALL ON TABLE crewscope.local_credential FROM PUBLIC;
REVOKE ALL ON TABLE crewscope.local_credential_metadata FROM PUBLIC;

COMMENT ON COLUMN crewscope.local_credential.password_hash IS
    'Restricted encoded Hash; only the authentication adapter may map or read this column.';
COMMENT ON VIEW crewscope.local_credential_metadata IS
    'Non-secret LocalCredential projection for generic account and audit adapters.';

-- An Account may have one unambiguous USER Principal per Organization. The repeated Organization
-- coordinate makes a cross-tenant Principal reference impossible at the foreign-key edge.
CREATE TABLE crewscope.account_organization_binding (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    principal_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_account_organization_binding_account
        UNIQUE (account_id, organization_id),
    CONSTRAINT uk_account_organization_binding_principal
        UNIQUE (organization_id, principal_id),
    CONSTRAINT fk_account_organization_binding_account FOREIGN KEY (account_id)
        REFERENCES crewscope.user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_account_organization_binding_organization FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_account_organization_binding_principal
        FOREIGN KEY (organization_id, principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_account_organization_binding_status CHECK (
        status IN ('ACTIVE', 'DISABLED')
    ),
    CONSTRAINT ck_account_organization_binding_version CHECK (version >= 0),
    CONSTRAINT ck_account_organization_binding_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_account_organization_binding_account_status_v31
    ON crewscope.account_organization_binding (account_id, status, updated_at DESC, id DESC);

CREATE INDEX ix_account_organization_binding_organization_status_v31
    ON crewscope.account_organization_binding (
        organization_id, status, updated_at DESC, id DESC
    );

-- Guard aggregate versions, immutable identity coordinates and forward-only lifecycle changes.
CREATE FUNCTION crewscope.guard_user_account_v31()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'UserAccount cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.platform_role <> OLD.platform_role
        OR NEW.created_at <> OLD.created_at
        OR NEW.version <> OLD.version + 1
        OR NEW.security_version NOT IN (OLD.security_version, OLD.security_version + 1)
        OR (NEW.status <> OLD.status
            AND NEW.security_version <> OLD.security_version + 1)
        OR NEW.updated_at < OLD.updated_at
        OR OLD.status = 'ARCHIVED'
        OR NOT (
            NEW.status = OLD.status
            OR (OLD.status = 'ACTIVE' AND NEW.status IN ('LOCKED', 'DISABLED', 'ARCHIVED'))
            OR (OLD.status = 'LOCKED' AND NEW.status IN ('ACTIVE', 'DISABLED', 'ARCHIVED'))
            OR (OLD.status = 'DISABLED' AND NEW.status IN ('ACTIVE', 'ARCHIVED'))
        ) THEN
        RAISE EXCEPTION 'invalid UserAccount mutation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_user_account_guard_v31
BEFORE UPDATE OR DELETE ON crewscope.user_account
FOR EACH ROW EXECUTE FUNCTION crewscope.guard_user_account_v31();

CREATE FUNCTION crewscope.guard_login_identity_v31()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'LoginIdentity cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.id, NEW.account_id, NEW.provider, NEW.subject, NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id, OLD.account_id, OLD.provider, OLD.subject, OLD.created_at)
        OR NEW.version <> OLD.version + 1
        OR NEW.updated_at < OLD.updated_at
        OR (OLD.last_authenticated_at IS NOT NULL
            AND (NEW.last_authenticated_at IS NULL
                OR NEW.last_authenticated_at < OLD.last_authenticated_at))
        OR (NEW.status <> OLD.status
            AND NEW.last_authenticated_at IS DISTINCT FROM OLD.last_authenticated_at)
        OR (NEW.status = OLD.status
            AND NEW.last_authenticated_at IS NOT DISTINCT FROM OLD.last_authenticated_at)
        OR NOT (
            NEW.status = OLD.status
            OR (OLD.status = 'ACTIVE' AND NEW.status IN ('DISABLED', 'REVOKED'))
            OR (OLD.status = 'DISABLED' AND NEW.status IN ('ACTIVE', 'REVOKED'))
        ) THEN
        RAISE EXCEPTION 'invalid LoginIdentity mutation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_login_identity_guard_v31
BEFORE UPDATE OR DELETE ON crewscope.login_identity
FOR EACH ROW EXECUTE FUNCTION crewscope.guard_login_identity_v31();

CREATE FUNCTION crewscope.guard_local_credential_v31()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'LocalCredential cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.id, NEW.account_id, NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id, OLD.account_id, OLD.created_at)
        OR NEW.password_hash = OLD.password_hash
        OR NEW.credential_version <> OLD.credential_version + 1
        OR NEW.version <> OLD.version + 1
        OR NEW.password_changed_at <= OLD.password_changed_at
        OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'invalid LocalCredential mutation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_local_credential_guard_v31
BEFORE UPDATE OR DELETE ON crewscope.local_credential
FOR EACH ROW EXECUTE FUNCTION crewscope.guard_local_credential_v31();

CREATE FUNCTION crewscope.require_binding_principal_v31()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM 1
    FROM crewscope.principal
    WHERE organization_id = NEW.organization_id
      AND id = NEW.principal_id
    FOR KEY SHARE;
    IF NOT FOUND THEN
        -- Let the composite foreign key report missing or cross-Organization coordinates.
        RETURN NEW;
    END IF;
    PERFORM 1
    FROM crewscope.principal
    WHERE organization_id = NEW.organization_id
      AND id = NEW.principal_id
      AND principal_type = 'USER'
      AND team_id IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'AccountOrganizationBinding requires an Organization USER Principal'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_account_organization_binding_principal_v31
BEFORE INSERT OR UPDATE OF organization_id, principal_id
ON crewscope.account_organization_binding
FOR EACH ROW EXECUTE FUNCTION crewscope.require_binding_principal_v31();

CREATE FUNCTION crewscope.guard_account_organization_binding_v31()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'AccountOrganizationBinding cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.id, NEW.account_id, NEW.organization_id, NEW.principal_id, NEW.created_at)
            IS DISTINCT FROM
            ROW(OLD.id, OLD.account_id, OLD.organization_id, OLD.principal_id, OLD.created_at)
        OR NEW.version <> OLD.version + 1
        OR NEW.updated_at < OLD.updated_at
        OR NEW.status = OLD.status THEN
        RAISE EXCEPTION 'invalid AccountOrganizationBinding mutation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_account_organization_binding_guard_v31
BEFORE UPDATE OR DELETE ON crewscope.account_organization_binding
FOR EACH ROW EXECUTE FUNCTION crewscope.guard_account_organization_binding_v31();

-- Principal type and scope are immutable in the domain. This database guard preserves that rule
-- once an Account binding depends on the Principal shape.
CREATE FUNCTION crewscope.guard_bound_principal_shape_v31()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(NEW.organization_id, NEW.id, NEW.principal_type, NEW.team_id)
            IS DISTINCT FROM ROW(OLD.organization_id, OLD.id, OLD.principal_type, OLD.team_id)
        AND EXISTS (
            SELECT 1
            FROM crewscope.account_organization_binding binding
            WHERE binding.organization_id = OLD.organization_id
              AND binding.principal_id = OLD.id
        ) THEN
        RAISE EXCEPTION 'bound Principal identity shape cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_bound_principal_shape_guard_v31
BEFORE UPDATE OF organization_id, id, principal_type, team_id ON crewscope.principal
FOR EACH ROW EXECUTE FUNCTION crewscope.guard_bound_principal_shape_v31();
