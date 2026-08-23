-- Separate the credential plaintext revision from the envelope lifecycle version. KMS rewrap and
-- revocation advance the optimistic envelope version without pretending that a new secret exists.
ALTER TABLE crewscope.credential_secret
    ADD COLUMN secret_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE crewscope.credential_secret
    ADD CONSTRAINT ck_credential_secret_secret_version CHECK (secret_version >= 0);

-- Preserve already-committed model bindings. Before V23 credential_version referenced the
-- envelope version directly, so the bound value is the only authoritative business revision to
-- carry forward. A credential cannot legitimately back two different current revisions.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM crewscope.model_connection
        GROUP BY organization_id, credential_id
        HAVING COUNT(DISTINCT credential_version) > 1
    ) THEN
        RAISE EXCEPTION
            'V23 cannot infer one business version for a credential with conflicting bindings';
    END IF;
END;
$$;

UPDATE crewscope.credential_secret credential
SET secret_version = binding.credential_version
FROM (
    SELECT organization_id, credential_id, MAX(credential_version) AS credential_version
    FROM crewscope.model_connection
    GROUP BY organization_id, credential_id
) binding
WHERE credential.organization_id = binding.organization_id
  AND credential.id = binding.credential_id;

ALTER TABLE crewscope.model_connection
    DROP CONSTRAINT fk_model_connection_credential;

ALTER TABLE crewscope.credential_secret
    DROP CONSTRAINT uk_credential_secret_model_binding;

ALTER TABLE crewscope.credential_secret
    ADD CONSTRAINT uk_credential_secret_model_binding
        UNIQUE (organization_id, id, subject_type, subject_id, secret_version);

ALTER TABLE crewscope.model_connection
    ADD CONSTRAINT fk_model_connection_credential
        FOREIGN KEY (
            organization_id, credential_id, credential_subject_type,
            credential_subject_id, credential_version
        ) REFERENCES crewscope.credential_secret (
            organization_id, id, subject_type, subject_id, secret_version
        ) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
