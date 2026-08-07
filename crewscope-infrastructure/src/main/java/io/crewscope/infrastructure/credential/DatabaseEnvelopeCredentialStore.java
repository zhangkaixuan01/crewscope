package io.crewscope.infrastructure.credential;

import io.crewscope.application.credential.CredentialAccessContext;
import io.crewscope.application.credential.CredentialCreateRequest;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialMutationContext;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialRevocationReason;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.credential.CredentialStoreError;
import io.crewscope.application.credential.CredentialStoreException;
import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.application.credential.CredentialSubjectType;
import io.crewscope.application.credential.ResolvedCredential;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL credential adapter using AES-256-GCM and deterministic AAD reconstruction. */
public class DatabaseEnvelopeCredentialStore implements CredentialStore {

    private static final String SELECT_COLUMNS = """
            id, organization_id, team_id, principal_id, subject_type, subject_id,
            credential_key, provider_key, connection_ref, credential_type,
            ciphertext, nonce, authentication_tag, key_id, algorithm, aad_version,
            metadata::TEXT AS metadata, status, expires_at, rotated_at, revoked_at,
            created_by_principal_id, updated_by_principal_id,
            created_at, updated_at, version
            """;

    private final JdbcTemplate jdbcTemplate;
    private final CredentialKeyRing keyRing;
    private final CredentialEnvelopeCrypto crypto;
    private final CredentialMetadataJsonCodec metadataCodec;
    private final Clock clock;

    public DatabaseEnvelopeCredentialStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CredentialEncryptionKey encryptionKey,
            SecureRandom secureRandom,
            Clock clock) {
        this(
                jdbcTemplate,
                objectMapper,
                CredentialKeyRing.single(encryptionKey),
                secureRandom,
                clock);
    }

    public DatabaseEnvelopeCredentialStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CredentialKeyRing keyRing,
            SecureRandom secureRandom,
            Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
        this.crypto = new CredentialEnvelopeCrypto(
                keyRing,
                Objects.requireNonNull(secureRandom, "secureRandom"),
                new CredentialAadCodec());
        this.metadataCodec = new CredentialMetadataJsonCodec(objectMapper);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CredentialDescriptor create(
            CredentialCreateRequest request, CredentialSecret secret) {
        CredentialCreateRequest create = Objects.requireNonNull(request, "request");
        CredentialSecret plaintext = Objects.requireNonNull(secret, "secret");
        UtcTimestamp now = now();
        create.expiresAt().ifPresent(expiresAt -> {
            if (expiresAt.compareTo(now) <= 0) {
                throw new IllegalArgumentException("expiresAt must be after the creation time");
            }
        });
        CredentialEnvelopeContext context = context(create);
        CredentialEnvelope envelope = crypto.encrypt(context, plaintext);
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.credential_secret (
                        id, organization_id, team_id, principal_id, subject_type, subject_id,
                        credential_key, provider_key, connection_ref, credential_type,
                        ciphertext, nonce, authentication_tag, key_id, algorithm, aad_version,
                        metadata, status, expires_at,
                        created_by_principal_id, updated_by_principal_id,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                              CAST(? AS JSONB), 'ACTIVE', ?, ?, ?, ?, ?, 0)
                    """,
                    create.credentialId().value(),
                    create.subject().organizationId().value(),
                    create.subject().teamId().map(TeamId::value).orElse(null),
                    create.subject().principalId().map(PrincipalId::value).orElse(null),
                    create.subject().type().name(),
                    create.subject().subjectId(),
                    create.credentialKey(),
                    create.providerKey(),
                    create.connectionRef().orElse(null),
                    create.credentialType(),
                    envelope.ciphertext(),
                    envelope.nonce(),
                    envelope.authenticationTag(),
                    context.keyId(),
                    context.algorithm(),
                    context.aadVersion(),
                    metadataCodec.encode(create.metadata()),
                    create.expiresAt().map(UtcTimestamp::toOffsetDateTime).orElse(null),
                    create.createdBy().value(),
                    create.createdBy().value(),
                    now.toOffsetDateTime(),
                    now.toOffsetDateTime());
            return descriptor(create, context, now);
        } catch (DuplicateKeyException exception) {
            throw conflict("Credential ID or organization credential key already exists", exception);
        } catch (DataAccessException exception) {
            throw storageFailure("Credential envelope could not be stored", exception);
        }
    }

    @Override
    public Optional<ResolvedCredential> resolve(
            CredentialReference reference, CredentialAccessContext accessContext) {
        CredentialReference credential = Objects.requireNonNull(reference, "reference");
        CredentialAccessContext access = Objects.requireNonNull(accessContext, "accessContext");
        if (!access.allows(credential)) {
            return Optional.empty();
        }
        Optional<CredentialDatabaseRow> result = find(credential);
        if (result.isEmpty() || !result.orElseThrow().descriptor().isUsableAt(now())) {
            return Optional.empty();
        }
        CredentialDatabaseRow row = result.orElseThrow();
        CredentialSecret plaintext = crypto.decrypt(row.context(), row.envelope());
        return Optional.of(new ResolvedCredential(row.descriptor(), plaintext));
    }

    @Override
    public CredentialDescriptor rotate(
            CredentialReference reference,
            long expectedVersion,
            CredentialMutationContext mutationContext,
            CredentialSecret newSecret) {
        CredentialReference credential = requireMutation(reference, mutationContext, expectedVersion);
        CredentialSecret plaintext = Objects.requireNonNull(newSecret, "newSecret");
        CredentialDatabaseRow existing = find(credential).orElseThrow(() -> notFound(credential));
        if (existing.descriptor().status() != CredentialStatus.ACTIVE) {
            throw conflict("Credential is not active");
        }
        // Authenticate the old envelope before accepting its database fields as the next AAD.
        try (CredentialSecret ignored = crypto.decrypt(existing.context(), existing.envelope())) {
            // The replacement is supplied separately; the old plaintext is immediately cleared.
        }
        CredentialEnvelopeContext nextContext = withCurrentKey(existing.context());
        CredentialEnvelope envelope = crypto.encrypt(nextContext, plaintext);
        UtcTimestamp rotatedAt = now();
        try {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE crewscope.credential_secret
                    SET ciphertext = ?, nonce = ?, authentication_tag = ?,
                        key_id = ?, algorithm = ?, aad_version = ?,
                        status = 'ACTIVE', rotated_at = ?,
                        updated_by_principal_id = ?, updated_at = ?, version = version + 1
                    WHERE organization_id = ? AND id = ?
                      AND status = 'ACTIVE' AND version = ?
                    """,
                    envelope.ciphertext(),
                    envelope.nonce(),
                    envelope.authenticationTag(),
                    nextContext.keyId(),
                    nextContext.algorithm(),
                    nextContext.aadVersion(),
                    rotatedAt.toOffsetDateTime(),
                    mutationContext.principalId().value(),
                    rotatedAt.toOffsetDateTime(),
                    credential.organizationId().value(),
                    credential.credentialId().value(),
                    expectedVersion);
            requireUpdated(updated, credential, expectedVersion);
            return find(credential).orElseThrow(() -> notFound(credential)).descriptor();
        } catch (CredentialStoreException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw storageFailure("Credential secret could not be rotated", exception);
        }
    }

    @Override
    public CredentialDescriptor revoke(
            CredentialReference reference,
            long expectedVersion,
            CredentialMutationContext mutationContext,
            CredentialRevocationReason reason) {
        CredentialReference credential = requireMutation(reference, mutationContext, expectedVersion);
        Objects.requireNonNull(reason, "reason");
        CredentialDatabaseRow existing = find(credential).orElseThrow(() -> notFound(credential));
        if (existing.descriptor().status() != CredentialStatus.ACTIVE) {
            throw conflict("Credential is not active");
        }
        CredentialEnvelope destroyed = crypto.destroy(existing.envelope().ciphertext().length);
        UtcTimestamp revokedAt = now();
        try {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE crewscope.credential_secret
                    SET ciphertext = ?, nonce = ?, authentication_tag = ?,
                        status = 'REVOKED', revoked_at = ?,
                        updated_by_principal_id = ?, updated_at = ?, version = version + 1
                    WHERE organization_id = ? AND id = ?
                      AND status = 'ACTIVE' AND version = ?
                    """,
                    destroyed.ciphertext(),
                    destroyed.nonce(),
                    destroyed.authenticationTag(),
                    revokedAt.toOffsetDateTime(),
                    mutationContext.principalId().value(),
                    revokedAt.toOffsetDateTime(),
                    credential.organizationId().value(),
                    credential.credentialId().value(),
                    expectedVersion);
            requireUpdated(updated, credential, expectedVersion);
            return find(credential).orElseThrow(() -> notFound(credential)).descriptor();
        } catch (CredentialStoreException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw storageFailure("Credential could not be revoked", exception);
        }
    }

    /** Re-encrypts a bounded set of active historical envelopes with the current key. */
    public CredentialRewrapResult rewrapBatch(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        List<CredentialDatabaseRow> candidates = findRewrapCandidates(batchSize);
        int rewrapped = 0;
        int conflicts = 0;
        for (CredentialDatabaseRow candidate : candidates) {
            if (rewrapOne(candidate)) {
                rewrapped++;
            } else {
                conflicts++;
            }
        }
        return new CredentialRewrapResult(
                candidates.size(), rewrapped, conflicts, remainingHistoricalEnvelopes());
    }

    private List<CredentialDatabaseRow> findRewrapCandidates(int batchSize) {
        try {
            return jdbcTemplate.query(
                    "SELECT " + SELECT_COLUMNS
                            + " FROM crewscope.credential_secret"
                            + " WHERE status = 'ACTIVE' AND key_id <> ?"
                            + " ORDER BY id LIMIT ?",
                    this::mapRow,
                    keyRing.currentKeyId(),
                    batchSize);
        } catch (CredentialStoreException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw storageFailure("Credential rewrap candidates could not be read", exception);
        } catch (RuntimeException exception) {
            throw integrityViolation("Credential rewrap candidate metadata is invalid", exception);
        }
    }

    private boolean rewrapOne(CredentialDatabaseRow existing) {
        CredentialEnvelopeContext nextContext = withCurrentKey(existing.context());
        CredentialEnvelope nextEnvelope;
        try (CredentialSecret plaintext =
                crypto.decrypt(existing.context(), existing.envelope())) {
            nextEnvelope = crypto.encrypt(nextContext, plaintext);
        }
        UtcTimestamp rewrappedAt = now();
        try {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE crewscope.credential_secret
                    SET ciphertext = ?, nonce = ?, authentication_tag = ?,
                        key_id = ?, algorithm = ?, aad_version = ?,
                        updated_at = ?, version = version + 1
                    WHERE organization_id = ? AND id = ?
                      AND status = 'ACTIVE' AND version = ? AND key_id = ?
                    """,
                    nextEnvelope.ciphertext(),
                    nextEnvelope.nonce(),
                    nextEnvelope.authenticationTag(),
                    nextContext.keyId(),
                    nextContext.algorithm(),
                    nextContext.aadVersion(),
                    rewrappedAt.toOffsetDateTime(),
                    existing.descriptor().subject().organizationId().value(),
                    existing.descriptor().credentialId().value(),
                    existing.descriptor().version(),
                    existing.context().keyId());
            return updated == 1;
        } catch (DataAccessException exception) {
            throw storageFailure("Credential envelope could not be rewrapped", exception);
        }
    }

    private long remainingHistoricalEnvelopes() {
        try {
            Long remaining = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM crewscope.credential_secret
                    WHERE status = 'ACTIVE' AND key_id <> ?
                    """,
                    Long.class,
                    keyRing.currentKeyId());
            return remaining == null ? 0 : remaining;
        } catch (DataAccessException exception) {
            throw storageFailure("Credential rewrap remainder could not be counted", exception);
        }
    }

    private Optional<CredentialDatabaseRow> find(CredentialReference reference) {
        try {
            List<CredentialDatabaseRow> rows = jdbcTemplate.query(
                    "SELECT " + SELECT_COLUMNS
                            + " FROM crewscope.credential_secret"
                            + " WHERE organization_id = ? AND id = ?",
                    this::mapRow,
                    reference.organizationId().value(),
                    reference.credentialId().value());
            return rows.stream().findFirst();
        } catch (CredentialStoreException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw storageFailure("Credential envelope could not be read", exception);
        } catch (RuntimeException exception) {
            throw integrityViolation("Credential envelope metadata is invalid", exception);
        }
    }

    private CredentialDatabaseRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        OrganizationId organizationId = new OrganizationId(
                resultSet.getObject("organization_id", UUID.class));
        CredentialSubjectType subjectType = CredentialSubjectType.valueOf(
                resultSet.getString("subject_type"));
        UUID subjectId = resultSet.getObject("subject_id", UUID.class);
        UUID teamId = resultSet.getObject("team_id", UUID.class);
        UUID principalId = resultSet.getObject("principal_id", UUID.class);
        CredentialSubject subject = new CredentialSubject(
                organizationId,
                subjectType,
                subjectId,
                Optional.ofNullable(teamId).map(TeamId::new),
                Optional.ofNullable(principalId).map(PrincipalId::new));
        Map<String, String> metadata = metadataCodec.decode(resultSet.getString("metadata"));
        CredentialDescriptor descriptor = new CredentialDescriptor(
                new CredentialId(resultSet.getObject("id", UUID.class)),
                subject,
                resultSet.getString("credential_key"),
                resultSet.getString("provider_key"),
                Optional.ofNullable(resultSet.getObject("connection_ref", UUID.class)),
                resultSet.getString("credential_type"),
                metadata,
                CredentialStatus.valueOf(resultSet.getString("status")),
                timestamp(resultSet, "expires_at"),
                timestamp(resultSet, "rotated_at"),
                timestamp(resultSet, "revoked_at"),
                resultSet.getString("key_id"),
                resultSet.getString("algorithm"),
                resultSet.getString("aad_version"),
                new PrincipalId(resultSet.getObject("created_by_principal_id", UUID.class)),
                new PrincipalId(resultSet.getObject("updated_by_principal_id", UUID.class)),
                UtcTimestamp.from(resultSet.getObject("created_at", OffsetDateTime.class)),
                UtcTimestamp.from(resultSet.getObject("updated_at", OffsetDateTime.class)),
                resultSet.getLong("version"));
        CredentialEnvelopeContext context = new CredentialEnvelopeContext(
                descriptor.credentialId(),
                descriptor.subject(),
                descriptor.credentialKey(),
                descriptor.providerKey(),
                descriptor.connectionRef(),
                descriptor.credentialType(),
                descriptor.metadata(),
                descriptor.expiresAt(),
                descriptor.keyId(),
                descriptor.algorithm(),
                descriptor.aadVersion());
        CredentialEnvelope envelope = new CredentialEnvelope(
                resultSet.getBytes("ciphertext"),
                resultSet.getBytes("nonce"),
                resultSet.getBytes("authentication_tag"));
        return new CredentialDatabaseRow(descriptor, context, envelope);
    }

    private CredentialEnvelopeContext context(CredentialCreateRequest request) {
        return new CredentialEnvelopeContext(
                request.credentialId(),
                request.subject(),
                request.credentialKey(),
                request.providerKey(),
                request.connectionRef(),
                request.credentialType(),
                request.metadata(),
                request.expiresAt(),
                keyRing.currentKeyId(),
                CredentialEnvelopeCrypto.ALGORITHM,
                CredentialEnvelopeCrypto.AAD_VERSION);
    }

    private CredentialEnvelopeContext withCurrentKey(CredentialEnvelopeContext context) {
        return new CredentialEnvelopeContext(
                context.credentialId(),
                context.subject(),
                context.credentialKey(),
                context.providerKey(),
                context.connectionRef(),
                context.credentialType(),
                context.metadata(),
                context.expiresAt(),
                keyRing.currentKeyId(),
                CredentialEnvelopeCrypto.ALGORITHM,
                CredentialEnvelopeCrypto.AAD_VERSION);
    }

    private CredentialDescriptor descriptor(
            CredentialCreateRequest request,
            CredentialEnvelopeContext context,
            UtcTimestamp createdAt) {
        return new CredentialDescriptor(
                request.credentialId(),
                request.subject(),
                request.credentialKey(),
                request.providerKey(),
                request.connectionRef(),
                request.credentialType(),
                request.metadata(),
                CredentialStatus.ACTIVE,
                request.expiresAt(),
                Optional.empty(),
                Optional.empty(),
                context.keyId(),
                context.algorithm(),
                context.aadVersion(),
                request.createdBy(),
                request.createdBy(),
                createdAt,
                createdAt,
                0);
    }

    private CredentialReference requireMutation(
            CredentialReference reference,
            CredentialMutationContext context,
            long expectedVersion) {
        CredentialReference credential = Objects.requireNonNull(reference, "reference");
        CredentialMutationContext mutation = Objects.requireNonNull(context, "mutationContext");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (!credential.organizationId().equals(mutation.organizationId())) {
            throw new CredentialStoreException(
                    CredentialStoreError.ACCESS_DENIED,
                    "Credential mutation is outside the authorized organization");
        }
        return credential;
    }

    private void requireUpdated(
            int updated, CredentialReference reference, long expectedVersion) {
        if (updated == 1) {
            return;
        }
        Optional<CredentialDatabaseRow> actual = find(reference);
        if (actual.isEmpty()) {
            throw notFound(reference);
        }
        throw new CredentialStoreException(
                CredentialStoreError.CONFLICT,
                "Credential lifecycle version or status changed; expected version "
                        + expectedVersion);
    }

    private UtcTimestamp now() {
        return UtcTimestamp.from(clock.instant());
    }

    private static Optional<UtcTimestamp> timestamp(ResultSet resultSet, String column)
            throws SQLException {
        return Optional.ofNullable(resultSet.getObject(column, OffsetDateTime.class))
                .map(UtcTimestamp::from);
    }

    private static CredentialStoreException notFound(CredentialReference reference) {
        Objects.requireNonNull(reference, "reference");
        return new CredentialStoreException(
                CredentialStoreError.NOT_FOUND, "Credential was not found");
    }

    private static CredentialStoreException conflict(String message) {
        return new CredentialStoreException(CredentialStoreError.CONFLICT, message);
    }

    private static CredentialStoreException conflict(String message, Throwable cause) {
        return new CredentialStoreException(CredentialStoreError.CONFLICT, message, cause);
    }

    private static CredentialStoreException integrityViolation(String message, Throwable cause) {
        return new CredentialStoreException(
                CredentialStoreError.INTEGRITY_VIOLATION, message, cause);
    }

    private static CredentialStoreException storageFailure(String message, Throwable cause) {
        return new CredentialStoreException(CredentialStoreError.STORAGE_FAILURE, message, cause);
    }

    private record CredentialDatabaseRow(
            CredentialDescriptor descriptor,
            CredentialEnvelopeContext context,
            CredentialEnvelope envelope) {}
}
