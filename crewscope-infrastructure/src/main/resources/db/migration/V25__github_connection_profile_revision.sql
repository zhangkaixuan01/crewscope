-- GitHub verification is version-bound authority. Keep one immutable profile per Connection
-- revision so credential rotation and Connection lifecycle changes invalidate old verification
-- without preventing the Connection aggregate from advancing.
ALTER TABLE crewscope.github_rate_limit_snapshot
    DROP CONSTRAINT fk_github_rate_limit_profile;

ALTER TABLE crewscope.external_observation
    DROP CONSTRAINT fk_external_observation_connection;

ALTER TABLE crewscope.github_repository_catalog_entry
    DROP CONSTRAINT fk_github_repository_profile;

ALTER TABLE crewscope.github_connection_profile
    DROP CONSTRAINT fk_github_connection_profile_connection;

ALTER TABLE crewscope.external_observation
    ADD COLUMN connection_version BIGINT;

ALTER TABLE crewscope.external_observation
    DISABLE TRIGGER trg_external_observation_append_only_v21;

UPDATE crewscope.external_observation observation
SET connection_version = profile.connection_version
FROM crewscope.github_connection_profile profile
WHERE profile.organization_id = observation.organization_id
  AND profile.connection_id = observation.connection_id;

ALTER TABLE crewscope.external_observation
    ENABLE TRIGGER trg_external_observation_append_only_v21;

ALTER TABLE crewscope.external_observation
    ALTER COLUMN connection_version SET NOT NULL;

ALTER TABLE crewscope.github_connection_profile
    DROP CONSTRAINT uk_github_connection_profile_connection;

ALTER TABLE crewscope.github_repository_catalog_entry
    ADD CONSTRAINT fk_github_repository_profile
        FOREIGN KEY (
            organization_id, connection_id, connection_version, external_identity
        ) REFERENCES crewscope.github_connection_profile (
            organization_id, connection_id, connection_version, external_identity
        ) ON DELETE RESTRICT;

ALTER TABLE crewscope.github_rate_limit_snapshot
    ADD CONSTRAINT fk_github_rate_limit_profile
        FOREIGN KEY (organization_id, connection_id, connection_version)
        REFERENCES crewscope.github_connection_profile (
            organization_id, connection_id, connection_version
        ) ON DELETE RESTRICT;

ALTER TABLE crewscope.external_observation
    ADD CONSTRAINT fk_external_observation_connection
        FOREIGN KEY (organization_id, connection_id, connection_version)
        REFERENCES crewscope.github_connection_profile (
            organization_id, connection_id, connection_version
        ) ON DELETE RESTRICT;

ALTER TABLE crewscope.external_observation
    ADD CONSTRAINT ck_external_observation_connection_version
        CHECK (connection_version >= 0);

CREATE INDEX ix_github_connection_profile_current
    ON crewscope.github_connection_profile (
        organization_id, connection_id, connection_version DESC, status, id
    );

CREATE INDEX ix_external_observation_connection_version
    ON crewscope.external_observation (
        organization_id, connection_id, connection_version,
        observed_at DESC, id DESC
    );
