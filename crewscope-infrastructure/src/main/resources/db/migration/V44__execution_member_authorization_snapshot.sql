-- M9b-A07: pin the executing member's authorization dimension on Task Token grants (ADR-038 §2).
--
-- Task Credential Grant scopes issued since this migration record which TeamMember executed
-- (the USER executor, or the owning user of an AGENT executor) and the authorization_version the
-- issuance was authorized under. Every side-effect boundary compares the pinned dimension against
-- the current member fact, so a suspension or role change invalidates the grant immediately.
--
-- Both columns stay NULLable on purpose: grants issued before this migration carry no member
-- dimension, keep authenticating for reads, and are still re-checked against current membership
-- facts (canParticipate) at every boundary. The dimension is deliberately excluded from the
-- signed scope fingerprint, so already-issued tokens remain verifiable after the upgrade. Like
-- the other snapshot coordinates on this table, no foreign key is declared: the dimension is a
-- pinned historical fact, not a live relationship.
ALTER TABLE crewscope.task_credential_grant
    ADD COLUMN execution_member_id UUID;

ALTER TABLE crewscope.task_credential_grant
    ADD COLUMN execution_member_authorization_version BIGINT;

ALTER TABLE crewscope.task_credential_grant
    ADD CONSTRAINT ck_task_grant_execution_member_version_positive
        CHECK (execution_member_authorization_version IS NULL
            OR execution_member_authorization_version >= 1);
