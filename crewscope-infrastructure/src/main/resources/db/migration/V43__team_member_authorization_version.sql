-- M9b-A07: authorization dimension for TeamMember (ADR-038 §2).
--
-- authorization_version is the monotonic counter every revocation-relevant write advances: status
-- transitions and role grant/revoke bump it together with the optimistic version, while presence
-- (recordActivity) bumps only the optimistic version. Execution checkpoints compare the snapshot
-- they were issued under against this column, so a suspended or demoted member fails the next
-- side-effect boundary even mid-run. Existing rows start at 1, matching the value every new
-- membership is created with.
--
-- No additional index: the seven revocation checkpoints read either by member id (primary key) or
-- by (team_id, user_principal_id) (uk_team_member_team_user), and both paths already fetch the
-- narrow heap row; a covering index would only add write amplification.
ALTER TABLE crewscope.team_member
    ADD COLUMN authorization_version BIGINT NOT NULL DEFAULT 1;

ALTER TABLE crewscope.team_member
    ADD CONSTRAINT ck_team_member_authorization_version_positive
        CHECK (authorization_version >= 1);
