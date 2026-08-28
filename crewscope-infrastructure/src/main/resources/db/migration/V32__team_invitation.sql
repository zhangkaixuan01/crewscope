-- Team invitations are scoped collaboration facts. The plaintext bearer token never crosses this
-- persistence boundary; only its fixed, lookup-safe digest is retained.
CREATE TABLE crewscope.team_invitation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    invited_by_principal_id UUID NOT NULL,
    target_email_normalized VARCHAR(254),
    target_role VARCHAR(32) NOT NULL,
    token_digest VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    accepted_by_account_id UUID,
    accepted_member_id UUID,
    resolved_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_team_invitation_scope_id
        UNIQUE (organization_id, team_id, id),
    CONSTRAINT uk_team_invitation_token_digest UNIQUE (token_digest),
    CONSTRAINT fk_team_invitation_team
        FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_team_invitation_inviter
        FOREIGN KEY (organization_id, invited_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_team_invitation_accepted_account
        FOREIGN KEY (accepted_by_account_id)
        REFERENCES crewscope.user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_team_invitation_accepted_member
        FOREIGN KEY (organization_id, team_id, accepted_member_id)
        REFERENCES crewscope.team_member (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_team_invitation_target_email CHECK (
        target_email_normalized IS NULL
        OR (
            BTRIM(target_email_normalized) = target_email_normalized
            AND target_email_normalized <> ''
            AND target_email_normalized = LOWER(target_email_normalized)
            AND POSITION('@' IN target_email_normalized) > 1
        )
    ),
    CONSTRAINT ck_team_invitation_target_role CHECK (
        target_role IN ('TEAM_ADMIN', 'TEAM_LEAD', 'MEMBER', 'AUDITOR')
    ),
    CONSTRAINT ck_team_invitation_token_digest CHECK (
        CHAR_LENGTH(token_digest) = 64
        AND token_digest ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_team_invitation_status CHECK (
        status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')
    ),
    CONSTRAINT ck_team_invitation_resolution CHECK (
        (status = 'PENDING'
            AND accepted_by_account_id IS NULL
            AND accepted_member_id IS NULL
            AND resolved_at IS NULL
            AND version = 0
            AND updated_at = created_at)
        OR (status = 'ACCEPTED'
            AND accepted_by_account_id IS NOT NULL
            AND accepted_member_id IS NOT NULL
            AND resolved_at IS NOT NULL
            AND resolved_at = updated_at
            AND resolved_at < expires_at
            AND version = 1)
        OR (status = 'REVOKED'
            AND accepted_by_account_id IS NULL
            AND accepted_member_id IS NULL
            AND resolved_at IS NOT NULL
            AND resolved_at = updated_at
            AND resolved_at < expires_at
            AND version = 1)
        OR (status = 'EXPIRED'
            AND accepted_by_account_id IS NULL
            AND accepted_member_id IS NULL
            AND resolved_at IS NOT NULL
            AND resolved_at = updated_at
            AND resolved_at >= expires_at
            AND version = 1)
    ),
    CONSTRAINT ck_team_invitation_version CHECK (version >= 0),
    CONSTRAINT ck_team_invitation_timestamps CHECK (
        expires_at > created_at AND updated_at >= created_at
    )
);

-- Team management, public preview and expiry cleanup have separate index paths. The unique digest
-- key above is the lookup and concurrency verdict for token presentation.
CREATE INDEX ix_team_invitation_team_status_v32
    ON crewscope.team_invitation (
        organization_id, team_id, status, created_at DESC, id DESC
    );

CREATE INDEX ix_team_invitation_pending_expiry_v32
    ON crewscope.team_invitation (expires_at, id)
    WHERE status = 'PENDING';

CREATE INDEX ix_team_invitation_pending_target_v32
    ON crewscope.team_invitation (
        organization_id, target_email_normalized, expires_at, id
    )
    WHERE status = 'PENDING' AND target_email_normalized IS NOT NULL;

-- Generic list and audit readers use this projection. Only the invitation adapter may read the
-- digest-bearing table directly.
CREATE VIEW crewscope.team_invitation_metadata AS
SELECT
    id,
    organization_id,
    team_id,
    invited_by_principal_id,
    target_email_normalized,
    target_role,
    expires_at,
    status,
    accepted_by_account_id,
    accepted_member_id,
    resolved_at,
    version,
    created_at,
    updated_at
FROM crewscope.team_invitation;

REVOKE ALL ON TABLE crewscope.team_invitation FROM PUBLIC;
REVOKE ALL ON TABLE crewscope.team_invitation_metadata FROM PUBLIC;

COMMENT ON COLUMN crewscope.team_invitation.token_digest IS
    'Restricted 32-byte digest encoded as lowercase hexadecimal; plaintext tokens are never stored.';
COMMENT ON VIEW crewscope.team_invitation_metadata IS
    'Non-secret TeamInvitation projection for management, preview and audit readers.';

-- Issuance requires one current active Team and one active Organization-scope USER inviter. These
-- facts may later become inactive without damaging the historical invitation.
CREATE FUNCTION crewscope.require_team_invitation_issue_scope_v32()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RETURN NEW;
    END IF;

    PERFORM 1
    FROM crewscope.team
    WHERE organization_id = NEW.organization_id
      AND id = NEW.team_id
    FOR KEY SHARE;
    IF NOT FOUND THEN
        -- Preserve the composite foreign key as the verdict for missing or cross-Scope Teams.
        RETURN NEW;
    END IF;
    PERFORM 1
    FROM crewscope.team
    WHERE organization_id = NEW.organization_id
      AND id = NEW.team_id
      AND status = 'ACTIVE';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'TeamInvitation requires an active Team' USING ERRCODE = '23514';
    END IF;

    PERFORM 1
    FROM crewscope.principal
    WHERE organization_id = NEW.organization_id
      AND id = NEW.invited_by_principal_id
    FOR KEY SHARE;
    IF NOT FOUND THEN
        -- Preserve the composite foreign key as the verdict for a cross-Organization inviter.
        RETURN NEW;
    END IF;
    PERFORM 1
    FROM crewscope.principal
    WHERE organization_id = NEW.organization_id
      AND id = NEW.invited_by_principal_id
      AND principal_type = 'USER'
      AND team_id IS NULL
      AND status = 'ACTIVE';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'TeamInvitation requires an active Organization USER inviter'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_team_invitation_issue_scope_v32
BEFORE INSERT ON crewscope.team_invitation
FOR EACH ROW EXECUTE FUNCTION crewscope.require_team_invitation_issue_scope_v32();

-- An accepted invitation must resolve the Account through its active Organization Binding to the
-- exact active Membership recorded by the terminal fact.
CREATE FUNCTION crewscope.require_team_invitation_acceptance_v32()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status <> 'ACCEPTED' THEN
        RETURN NEW;
    END IF;

    PERFORM 1
    FROM crewscope.user_account account
    JOIN crewscope.account_organization_binding binding
      ON binding.account_id = account.id
    JOIN crewscope.principal principal
      ON principal.organization_id = binding.organization_id
     AND principal.id = binding.principal_id
    JOIN crewscope.team team
      ON team.organization_id = binding.organization_id
     AND team.id = NEW.team_id
    JOIN crewscope.team_member member
      ON member.organization_id = binding.organization_id
     AND member.user_principal_id = binding.principal_id
    WHERE binding.organization_id = NEW.organization_id
      AND account.id = NEW.accepted_by_account_id
      AND account.status = 'ACTIVE'
      AND (NEW.target_email_normalized IS NULL
        OR account.email_normalized = NEW.target_email_normalized)
      AND binding.status = 'ACTIVE'
      AND principal.principal_type = 'USER'
      AND principal.team_id IS NULL
      AND principal.status = 'ACTIVE'
      AND team.status = 'ACTIVE'
      AND member.team_id = NEW.team_id
      AND member.id = NEW.accepted_member_id
      AND member.status = 'ACTIVE'
    FOR NO KEY UPDATE OF account, binding, principal, team, member;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'TeamInvitation acceptance requires the active bound Team Membership'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_team_invitation_acceptance_v32
BEFORE INSERT OR UPDATE ON crewscope.team_invitation
FOR EACH ROW EXECUTE FUNCTION crewscope.require_team_invitation_acceptance_v32();

-- Coordinates and token digest are immutable. A new row is always pending, and exactly one
-- single-version transition may close it; every terminal row is permanent.
CREATE FUNCTION crewscope.guard_team_invitation_v32()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'TeamInvitation cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' OR NEW.version <> 0 THEN
            RAISE EXCEPTION 'TeamInvitation must be issued as PENDING at version zero'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(
            NEW.id,
            NEW.organization_id,
            NEW.team_id,
            NEW.invited_by_principal_id,
            NEW.target_email_normalized,
            NEW.target_role,
            NEW.token_digest,
            NEW.expires_at,
            NEW.created_at
        ) IS DISTINCT FROM ROW(
            OLD.id,
            OLD.organization_id,
            OLD.team_id,
            OLD.invited_by_principal_id,
            OLD.target_email_normalized,
            OLD.target_role,
            OLD.token_digest,
            OLD.expires_at,
            OLD.created_at
        )
        OR OLD.status <> 'PENDING'
        OR NEW.status NOT IN ('ACCEPTED', 'REVOKED', 'EXPIRED')
        OR NEW.version <> OLD.version + 1
        OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'invalid TeamInvitation mutation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_team_invitation_guard_v32
BEFORE INSERT OR UPDATE OR DELETE ON crewscope.team_invitation
FOR EACH ROW EXECUTE FUNCTION crewscope.guard_team_invitation_v32();
