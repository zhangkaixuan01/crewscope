package io.crewscope.infrastructure.persistence.identity;

import io.crewscope.application.identity.LocalCredentialAuthenticationMaterial;
import io.crewscope.application.identity.LocalCredentialStore;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Trusted JDBC adapter that is the only identity persistence path allowed to read a Hash. */
@Repository
public class JdbcLocalCredentialStoreAdapter implements LocalCredentialStore {

    private static final String SELECT = "SELECT * FROM crewscope.local_credential";

    private final NamedParameterJdbcTemplate jdbc;
    private final IdentityPersistenceMapper mapper;

    public JdbcLocalCredentialStoreAdapter(
            NamedParameterJdbcTemplate jdbc, IdentityPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public LocalCredentialAuthenticationMaterial create(
            LocalCredentialMetadata metadata, LocalPasswordHash passwordHash) {
        LocalCredentialMetadata requiredMetadata = Objects.requireNonNull(metadata, "metadata");
        LocalPasswordHash requiredHash = Objects.requireNonNull(passwordHash, "passwordHash");
        IdentityPersistenceSupport.requireNewVersion(
                requiredMetadata.version(), "localCredential.metadataVersion");
        LocalCredentialAuthenticationMaterial.verified(requiredMetadata, requiredHash);
        if (!requiredHash.algorithm().isCurrentWriteAlgorithm()
                || requiredMetadata.credentialVersion().value() != 1) {
            throw new DomainValidationException(
                    "localCredential", "must use the initial current password encoding");
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.local_credential (
                        id, account_id, password_hash, algorithm, credential_version,
                        password_changed_at, version, created_at, updated_at
                    ) VALUES (
                        :id, :accountId, :passwordHash, :algorithm, :credentialVersion,
                        :passwordChangedAt, :version, :createdAt, :updatedAt
                    )
                    """,
                    parameters(requiredMetadata, requiredHash));
            return findByAccountIdForAuthentication(requiredMetadata.accountId())
                    .orElseThrow(JdbcLocalCredentialStoreAdapter::safePersistenceFailure);
        } catch (DataAccessException failure) {
            throw safePersistenceFailure();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalCredentialAuthenticationMaterial> findByAccountIdForAuthentication(
            UserAccountId accountId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource(
                "accountId", Objects.requireNonNull(accountId, "accountId").value());
        try {
            return jdbc.query(
                            SELECT + " WHERE account_id = :accountId",
                            parameters,
                            (row, ignored) -> {
                                LocalCredentialMetadata metadata;
                                try {
                                    metadata = mapper.credentialMetadata(row);
                                } catch (DomainValidationException invalidMetadata) {
                                    return LocalCredentialAuthenticationMaterial.corrupted();
                                }
                                try {
                                    LocalPasswordHash hash =
                                            new LocalPasswordHash(row.getString("password_hash"));
                                    return LocalCredentialAuthenticationMaterial.verified(
                                            metadata, hash);
                                } catch (DomainValidationException | IllegalArgumentException invalid) {
                                    return LocalCredentialAuthenticationMaterial.corrupted(metadata);
                                }
                            })
                    .stream()
                    .findFirst();
        } catch (DataAccessException failure) {
            throw safePersistenceFailure();
        }
    }

    @Override
    @Transactional
    public boolean rotateIfUnchanged(
            LocalCredentialMetadata replacement,
            LocalPasswordHash replacementHash,
            long expectedMetadataVersion) {
        LocalCredentialMetadata requiredMetadata = Objects.requireNonNull(replacement, "replacement");
        LocalPasswordHash requiredHash = Objects.requireNonNull(replacementHash, "replacementHash");
        IdentityPersistenceSupport.requireNextVersion(
                requiredMetadata.version(),
                expectedMetadataVersion,
                "localCredential.metadataVersion");
        LocalCredentialAuthenticationMaterial.verified(requiredMetadata, requiredHash);
        if (!requiredHash.algorithm().isCurrentWriteAlgorithm()
                || requiredMetadata.credentialVersion().value() <= 1) {
            throw new DomainValidationException(
                    "localCredential", "replacement must advance to the current encoding");
        }
        try {
            int affected = jdbc.update(
                    """
                    UPDATE crewscope.local_credential
                       SET password_hash = :passwordHash,
                           algorithm = :algorithm,
                           credential_version = :credentialVersion,
                           password_changed_at = :passwordChangedAt,
                           version = :version,
                           updated_at = :updatedAt
                     WHERE id = :id
                       AND account_id = :accountId
                       AND version = :expectedVersion
                       AND credential_version = :expectedCredentialVersion
                    """,
                    parameters(requiredMetadata, requiredHash)
                            .addValue("expectedVersion", expectedMetadataVersion)
                            .addValue(
                                    "expectedCredentialVersion",
                                    requiredMetadata.credentialVersion().value() - 1));
            return affected == 1;
        } catch (DataAccessException failure) {
            // JDBC exceptions can contain statement context, so the secret-bearing cause is removed.
            throw safePersistenceFailure();
        }
    }

    private static MapSqlParameterSource parameters(
            LocalCredentialMetadata metadata, LocalPasswordHash passwordHash) {
        return new MapSqlParameterSource()
                .addValue("id", metadata.id().value())
                .addValue("accountId", metadata.accountId().value())
                .addValue("passwordHash", passwordHash.encodedValue())
                .addValue("algorithm", passwordHash.algorithm().encodingId())
                .addValue("credentialVersion", metadata.credentialVersion().value())
                .addValue(
                        "passwordChangedAt",
                        IdentityPersistenceSupport.timestamp(metadata.passwordChangedAt()))
                .addValue("version", metadata.version())
                .addValue(
                        "createdAt",
                        IdentityPersistenceSupport.timestamp(metadata.lifecycle().createdAt()))
                .addValue(
                        "updatedAt",
                        IdentityPersistenceSupport.timestamp(metadata.lifecycle().updatedAt()));
    }

    private static IllegalStateException safePersistenceFailure() {
        return new IllegalStateException("Local credential persistence operation failed");
    }
}
