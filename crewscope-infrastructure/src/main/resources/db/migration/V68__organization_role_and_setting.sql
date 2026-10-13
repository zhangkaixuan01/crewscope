-- M12-A01: Organization-level roles and settings (ADR-035, S01 freeze sections 3.1/3.6).
-- The organization table itself stays untouched: name/status remain written only by the bootstrap
-- seeder (D01 read-only port contract), so organization-level configuration lives in dedicated
-- typed rows instead of a generic key-value table (notification_preference / project_execution_
-- defaults precedent).
--
-- organization_role_grant introduces the ORG_* vocabulary as a third, orthogonal role layer next
-- to PlatformRole and TeamRoleKey. ORG roles are a fixed three-word vocabulary, so the role is a
-- CHECKed column rather than a role-catalog FK (team_role exists because team roles are definable;
-- organization roles are not). Revoked grants stay in the table as the append-only audit face of
-- who held what; at most one ACTIVE grant per (organization, principal, role) is enforced by the
-- partial unique index below, mirroring ux_team_member_role_active_scope.
-- The last-owner protection is an application-transaction check (M9b last-owner precedent), not a
-- DB constraint: it must count ACTIVE owners at the moment of the revoke, and a declarative
-- constraint cannot express "keep at least one" for a partial state.
--
-- The backfill grants ORG_OWNER to the principal of the earliest-created ACTIVE binding of each
-- organization that has no ACTIVE ORG_OWNER yet. It covers existing databases only: on a fresh
-- install the migration runs before the seeder creates any organization, so the initial owner
-- arrives through the application-side ensureInitialOwner hook on the account-creation chain
-- (bootstrap operator provisioning / local account registration). Both tracks are idempotent.
CREATE TABLE crewscope.organization_setting (
    organization_id UUID NOT NULL,
    audit_event_retention_days INT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    PRIMARY KEY (organization_id),
    CONSTRAINT fk_organization_setting_organization FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_setting_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_setting_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_organization_setting_retention CHECK (
        audit_event_retention_days IS NULL OR audit_event_retention_days >= 30
    ),
    CONSTRAINT ck_organization_setting_version CHECK (version >= 0),
    CONSTRAINT ck_organization_setting_timestamps CHECK (updated_at >= created_at)
);

-- No seed row: the default state is "no row" (retention = forever), materialized by the
-- application on first write (notification_preference first-write precedent).

CREATE TABLE crewscope.organization_role_grant (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    principal_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    granted_by_principal_id UUID NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    revoked_by_principal_id UUID,
    reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT fk_organization_role_grant_organization FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_role_grant_principal
        FOREIGN KEY (organization_id, principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_role_grant_grantor
        FOREIGN KEY (organization_id, granted_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_role_grant_revoker
        FOREIGN KEY (organization_id, revoked_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_organization_role_grant_role CHECK (
        role IN ('ORG_OWNER', 'ORG_ADMIN', 'ORG_AUDITOR')
    ),
    CONSTRAINT ck_organization_role_grant_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_organization_role_grant_revocation CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_by_principal_id IS NOT NULL)
        OR (status <> 'REVOKED' AND revoked_at IS NULL AND revoked_by_principal_id IS NULL)
    ),
    CONSTRAINT ck_organization_role_grant_version CHECK (version >= 0),
    CONSTRAINT ck_organization_role_grant_timestamps CHECK (updated_at >= created_at)
);

-- At most one ACTIVE grant per (organization, principal, role); revoked history coexists.
CREATE UNIQUE INDEX ux_organization_role_grant_active
    ON crewscope.organization_role_grant (organization_id, principal_id, role)
    WHERE status = 'ACTIVE';

-- Capability checks re-query active grants per request; the last-owner count reads the same index.
CREATE INDEX ix_organization_role_grant_organization_role
    ON crewscope.organization_role_grant (organization_id, role, status);

-- Backfill (existing databases only; see header): earliest ACTIVE binding per organization
-- becomes its ORG_OWNER, attributed to itself as the migration fact.
INSERT INTO crewscope.organization_role_grant (
    id, organization_id, principal_id, role, status,
    granted_by_principal_id, granted_at, reason, version,
    created_at, created_by_principal_id, updated_at, updated_by_principal_id)
SELECT gen_random_uuid(), earliest.organization_id, earliest.principal_id,
       'ORG_OWNER', 'ACTIVE',
       earliest.principal_id, earliest.created_at,
       'V68 initial owner backfill (earliest ACTIVE account organization binding)',
       0,
       earliest.created_at, earliest.principal_id, earliest.created_at, earliest.principal_id
FROM (
    SELECT DISTINCT ON (b.organization_id)
           b.organization_id, b.principal_id, b.created_at
    FROM crewscope.account_organization_binding b
    WHERE b.status = 'ACTIVE'
    ORDER BY b.organization_id, b.created_at, b.principal_id
) AS earliest
WHERE NOT EXISTS (
    SELECT 1 FROM crewscope.organization_role_grant g
    WHERE g.organization_id = earliest.organization_id
      AND g.role = 'ORG_OWNER'
      AND g.status = 'ACTIVE'
);
