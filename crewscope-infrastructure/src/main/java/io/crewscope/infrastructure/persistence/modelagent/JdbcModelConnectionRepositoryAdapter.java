package io.crewscope.infrastructure.persistence.modelagent;

import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-qualified JDBC adapter for mutable model connection roots. */
@Repository
public class JdbcModelConnectionRepositoryAdapter implements ModelConnectionRepository {

    private static final String SELECT = "SELECT * FROM crewscope.model_connection";

    private final NamedParameterJdbcTemplate jdbc;
    private final ModelAgentPersistenceMapper mapper;

    public JdbcModelConnectionRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, ModelAgentPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public ModelConnection register(ModelConnection connection) {
        ModelConnection required = Objects.requireNonNull(connection, "connection");
        if (required.version() != 0) {
            throw new DomainValidationException("modelConnection.version", "must be zero when registered");
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.model_connection (
                        id, organization_id, provider_key, provider_definition_hash,
                        owner_type, owner_id, owner_team_id, owner_user_principal_id,
                        endpoint, region, credential_id, credential_subject_type,
                        credential_subject_id, credential_version, billing_subject_type,
                        billing_subject_id, billing_team_id, billing_principal_id,
                        status, health_status, health_credential_version, health_checked_at,
                        last_healthy_at, consecutive_failures, health_failure_code,
                        revocation_reason, version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (
                        :id, :organizationId, :providerKey, :providerDefinitionHash,
                        :ownerType, :ownerId, :ownerTeamId, :ownerUserPrincipalId,
                        :endpoint, :region, :credentialId, :credentialSubjectType,
                        :credentialSubjectId, :credentialVersion, :billingSubjectType,
                        :billingSubjectId, :billingTeamId, :billingPrincipalId,
                        :status, :healthStatus, :healthCredentialVersion, :healthCheckedAt,
                        :lastHealthyAt, :consecutiveFailures, :healthFailureCode,
                        :revocationReason, :version, :createdAt, :createdBy,
                        :updatedAt, :updatedBy
                    )
                    """,
                    parameters(required));
        } catch (DataIntegrityViolationException failure) {
            DomainValidationException conflict = new DomainValidationException(
                    "modelConnection", "conflicts with committed tenant or credential facts");
            conflict.addSuppressed(failure);
            throw conflict;
        }
        return findById(required.organizationId(), required.id()).orElseThrow();
    }

    @Override
    @Transactional
    public ModelConnection update(ModelConnection connection) {
        ModelConnection required = Objects.requireNonNull(connection, "connection");
        long expected = required.version() - 1;
        if (expected < 0) {
            throw new DomainValidationException(
                    "modelConnection.version", "must contain one uncommitted mutation");
        }
        int affected = jdbc.update(
                """
                UPDATE crewscope.model_connection
                   SET credential_id = :credentialId,
                       credential_subject_type = :credentialSubjectType,
                       credential_subject_id = :credentialSubjectId,
                       credential_version = :credentialVersion,
                       status = :status,
                       health_status = :healthStatus,
                       health_credential_version = :healthCredentialVersion,
                       health_checked_at = :healthCheckedAt,
                       last_healthy_at = :lastHealthyAt,
                       consecutive_failures = :consecutiveFailures,
                       health_failure_code = :healthFailureCode,
                       revocation_reason = :revocationReason,
                       version = :version,
                       updated_at = :updatedAt,
                       updated_by_principal_id = :updatedBy
                 WHERE organization_id = :organizationId AND id = :id
                   AND provider_key = :providerKey
                   AND provider_definition_hash = :providerDefinitionHash
                   AND version = :expectedVersion
                """,
                parameters(required).addValue("expectedVersion", expected));
        if (affected == 0) {
            throwVersionConflict(required, expected);
        }
        return findById(required.organizationId(), required.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModelConnection> findById(
            OrganizationId organizationId, ModelConnectionId connectionId) {
        OrganizationId requiredOrganization = Objects.requireNonNull(organizationId);
        ModelConnectionId requiredId = Objects.requireNonNull(connectionId);
        List<ModelProviderKey> providerKeys = jdbc.query(
                "SELECT provider_key FROM crewscope.model_connection WHERE organization_id = :organizationId AND id = :id",
                new MapSqlParameterSource()
                        .addValue("organizationId", requiredOrganization.value())
                        .addValue("id", requiredId.value()),
                (row, ignored) -> new ModelProviderKey(row.getString(1)));
        if (providerKeys.isEmpty()) {
            return Optional.empty();
        }
        ModelProviderDefinition provider = provider(providerKeys.get(0)).orElseThrow();
        return jdbc.query(
                        SELECT + " WHERE organization_id = :organizationId AND id = :id",
                        new MapSqlParameterSource()
                                .addValue("organizationId", requiredOrganization.value())
                                .addValue("id", requiredId.value()),
                        (row, ignored) -> mapper.connection(row, provider))
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelConnection> findByOwner(ModelConnectionOwner owner) {
        return findByOwner(owner, 0, 200);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelConnection> findByOwner(
            ModelConnectionOwner owner, int offset, int limit) {
        ModelConnectionOwner required = Objects.requireNonNull(owner, "owner");
        ModelAgentJdbcGuard.requirePage(offset, limit, "modelConnection.page");
        Map<ModelProviderKey, ModelProviderDefinition> providers = providersForOwner(required);
        return jdbc.query(
                SELECT + """
                 WHERE organization_id = :organizationId
                   AND owner_type = :ownerType AND owner_id = :ownerId
                 ORDER BY updated_at DESC, id DESC
                 OFFSET :offset LIMIT :limit
                """,
                ownerParameters(required).addValue("offset", offset).addValue("limit", limit),
                (row, ignored) -> {
                    ModelProviderKey key = new ModelProviderKey(row.getString("provider_key"));
                    ModelProviderDefinition provider = providers.get(key);
                    if (provider == null) {
                        throw new IllegalStateException("Missing provider for committed model connection");
                    }
                    return mapper.connection(row, provider);
                });
    }

    private Map<ModelProviderKey, ModelProviderDefinition> providersForOwner(
            ModelConnectionOwner owner) {
        Map<ModelProviderKey, ModelProviderDefinition> providers = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT DISTINCT provider.*
                  FROM crewscope.model_provider_definition provider
                  JOIN crewscope.model_connection connection
                    ON connection.provider_key = provider.provider_key
                   AND connection.provider_definition_hash = provider.content_hash
                 WHERE connection.organization_id = :organizationId
                   AND connection.owner_type = :ownerType
                   AND connection.owner_id = :ownerId
                """,
                ownerParameters(owner),
                row -> {
                    ModelProviderDefinition provider = mapper.provider(row);
                    providers.put(provider.providerKey(), provider);
                });
        return providers;
    }

    private Optional<ModelProviderDefinition> provider(ModelProviderKey key) {
        return jdbc.query(
                        "SELECT * FROM crewscope.model_provider_definition WHERE provider_key = :providerKey",
                        new MapSqlParameterSource("providerKey", key.value()),
                        (row, ignored) -> mapper.provider(row))
                .stream().findFirst();
    }

    private void throwVersionConflict(ModelConnection value, long expected) {
        List<Long> versions = jdbc.query(
                "SELECT version FROM crewscope.model_connection WHERE organization_id = :organizationId AND id = :id",
                new MapSqlParameterSource()
                        .addValue("organizationId", value.organizationId().value())
                        .addValue("id", value.id().value()),
                (row, ignored) -> row.getLong(1));
        if (versions.isEmpty()) {
            throw new AggregateNotFoundException("ModelConnection", value.id());
        }
        throw new OptimisticLockConflictException(
                "ModelConnection", value.id(), expected, versions.get(0));
    }

    private static MapSqlParameterSource ownerParameters(ModelConnectionOwner owner) {
        return new MapSqlParameterSource()
                .addValue("organizationId", owner.organizationId().value())
                .addValue("ownerType", owner.type().name())
                .addValue("ownerId", owner.ownerId());
    }

    private static MapSqlParameterSource parameters(ModelConnection value) {
        ModelConnectionOwner owner = value.owner();
        ModelCredentialSubject credential = value.credentialBinding().subject();
        ModelBillingSubject billing = value.billingSubject();
        PrincipalId createdBy = value.audit().createdBy().orElseThrow();
        PrincipalId updatedBy = value.audit().updatedBy().orElseThrow();
        return new MapSqlParameterSource()
                .addValue("id", value.id().value())
                .addValue("organizationId", value.organizationId().value())
                .addValue("providerKey", value.providerKey().value())
                .addValue("providerDefinitionHash", value.providerDefinitionHash().value())
                .addValue("ownerType", owner.type().name())
                .addValue("ownerId", owner.ownerId())
                .addValue("ownerTeamId", owner.teamId().map(id -> id.value()).orElse(null))
                .addValue("ownerUserPrincipalId", owner.userPrincipalId().map(id -> id.value()).orElse(null))
                .addValue("endpoint", value.endpoint().value())
                .addValue("region", value.region().value())
                .addValue("credentialId", value.credentialBinding().credentialId().value())
                .addValue("credentialSubjectType", credential.type().name())
                .addValue("credentialSubjectId", credential.subjectId())
                .addValue("credentialVersion", value.credentialBinding().credentialVersion().value())
                .addValue("billingSubjectType", billing.type().name())
                .addValue("billingSubjectId", billing.subjectId())
                .addValue("billingTeamId", billing.teamId().map(id -> id.value()).orElse(null))
                .addValue("billingPrincipalId", billing.principalId().map(id -> id.value()).orElse(null))
                .addValue("status", value.status().name())
                .addValue("healthStatus", value.health().status().name())
                .addValue("healthCredentialVersion", value.health().credentialVersion().value())
                .addValue("healthCheckedAt", value.health().checkedAt().map(JdbcModelConnectionRepositoryAdapter::timestamp).orElse(null))
                .addValue("lastHealthyAt", value.health().lastHealthyAt().map(JdbcModelConnectionRepositoryAdapter::timestamp).orElse(null))
                .addValue("consecutiveFailures", value.health().consecutiveFailures())
                .addValue("healthFailureCode", value.health().failureCode().map(Enum::name).orElse(null))
                .addValue("revocationReason", value.revocationReason().map(Enum::name).orElse(null))
                .addValue("version", value.version())
                .addValue("createdAt", timestamp(value.audit().createdAt()))
                .addValue("createdBy", createdBy.value())
                .addValue("updatedAt", timestamp(value.audit().updatedAt()))
                .addValue("updatedBy", updatedBy.value());
    }

    private static OffsetDateTime timestamp(UtcTimestamp value) {
        return OffsetDateTime.ofInstant(value.value(), ZoneOffset.UTC);
    }
}
