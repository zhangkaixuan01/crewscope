package io.crewscope.infrastructure.persistence.provider;

import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingQuery;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for Provider registry, authorization facts and BindingResolver candidates. */
@Repository
public class JpaProviderRepositoryAdapter
        implements ProviderDefinitionRepository,
                ProviderImplementationRepository,
                ConnectionRepository,
                ConnectionGrantRepository,
                ProviderBindingRepository {

    private final EntityManager entityManager;
    private final ProviderPersistenceMapper mapper;

    public JpaProviderRepositoryAdapter(
            EntityManager entityManager, ProviderPersistenceMapper mapper) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public ProviderDefinition create(ProviderDefinition definition) {
        ProviderDefinition required = Objects.requireNonNull(definition, "definition");
        requireNew("providerDefinition.version", required.version());
        ProviderDefinitionEntity row = mapper.toEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toDomain(row);
    }

    @Override
    @Transactional
    public ProviderDefinition update(ProviderDefinition definition) {
        ProviderDefinition required = Objects.requireNonNull(definition, "definition");
        ProviderDefinitionEntity row = mapper.toEntity(required);
        long expectedVersion = expectedVersion("providerDefinition.version", required.version());
        int affected = entityManager
                .createQuery(
                        """
                        UPDATE ProviderDefinitionEntity value
                        SET value.status = :status,
                            value.updatedAt = :updatedAt,
                            value.updatedByPrincipalId = :updatedBy,
                            value.version = :version
                        WHERE value.organizationId = :organizationId
                          AND value.id = :id
                          AND value.version = :expectedVersion
                        """)
                .setParameter("status", row.status)
                .setParameter("updatedAt", row.updatedAt)
                .setParameter("updatedBy", row.updatedByPrincipalId)
                .setParameter("version", row.version)
                .setParameter("organizationId", row.organizationId)
                .setParameter("id", row.id)
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        finishUpdate(
                "ProviderDefinition",
                required.id(),
                expectedVersion,
                affected,
                ProviderDefinitionEntity.class,
                row.organizationId);
        return findById(required.organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("ProviderDefinition", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderDefinition> findById(
            OrganizationId organizationId, ProviderDefinitionId id) {
        return entityManager
                .createQuery(
                        "SELECT value FROM ProviderDefinitionEntity value WHERE value.organizationId = :organizationId AND value.id = :id",
                        ProviderDefinitionEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderDefinition> findByKey(OrganizationId organizationId, String key) {
        String requiredKey = requireKey(key, "providerDefinition.key");
        return entityManager
                .createQuery(
                        "SELECT value FROM ProviderDefinitionEntity value WHERE value.organizationId = :organizationId AND value.providerKey = :key",
                        ProviderDefinitionEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("key", requiredKey)
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional
    public ProviderImplementation create(ProviderImplementation implementation) {
        ProviderImplementation required = Objects.requireNonNull(implementation, "implementation");
        requireNew("providerImplementation.version", required.version());
        ProviderImplementationEntity row = mapper.toEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toDomain(row);
    }

    @Override
    @Transactional
    public ProviderImplementation update(ProviderImplementation implementation) {
        ProviderImplementation required = Objects.requireNonNull(implementation, "implementation");
        ProviderImplementationEntity row = mapper.toEntity(required);
        long expectedVersion = expectedVersion("providerImplementation.version", required.version());
        int affected = entityManager
                .createQuery(
                        """
                        UPDATE ProviderImplementationEntity value
                        SET value.status = :status,
                            value.updatedAt = :updatedAt,
                            value.updatedByPrincipalId = :updatedBy,
                            value.version = :version
                        WHERE value.organizationId = :organizationId
                          AND value.id = :id
                          AND value.version = :expectedVersion
                        """)
                .setParameter("status", row.status)
                .setParameter("updatedAt", row.updatedAt)
                .setParameter("updatedBy", row.updatedByPrincipalId)
                .setParameter("version", row.version)
                .setParameter("organizationId", row.organizationId)
                .setParameter("id", row.id)
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        finishUpdate(
                "ProviderImplementation",
                required.id(),
                expectedVersion,
                affected,
                ProviderImplementationEntity.class,
                row.organizationId);
        return findById(required.organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("ProviderImplementation", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderImplementation> findById(
            OrganizationId organizationId, ProviderImplementationId id) {
        return entityManager
                .createQuery(
                        "SELECT value FROM ProviderImplementationEntity value WHERE value.organizationId = :organizationId AND value.id = :id",
                        ProviderImplementationEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderImplementation> findByDefinition(
            OrganizationId organizationId, ProviderDefinitionId definitionId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ProviderImplementationEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.providerDefinitionId = :definitionId
                        ORDER BY value.implementationKey, value.id
                        """,
                        ProviderImplementationEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("definitionId", Objects.requireNonNull(definitionId).value())
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public Connection create(Connection connection) {
        Connection required = Objects.requireNonNull(connection, "connection");
        requireNew("connection.version", required.version());
        ConnectionEntity row = mapper.toEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toDomain(row);
    }

    @Override
    @Transactional
    public Connection update(Connection connection) {
        Connection required = Objects.requireNonNull(connection, "connection");
        ConnectionEntity row = mapper.toEntity(required);
        long expectedVersion = expectedVersion("connection.version", required.version());
        int affected = entityManager
                .createQuery(
                        """
                        UPDATE ConnectionEntity value
                        SET value.status = :status,
                            value.terminalReason = :terminalReason,
                            value.updatedAt = :updatedAt,
                            value.updatedByPrincipalId = :updatedBy,
                            value.version = :version
                        WHERE value.organizationId = :organizationId
                          AND value.id = :id
                          AND value.version = :expectedVersion
                        """)
                .setParameter("status", row.status)
                .setParameter("terminalReason", row.terminalReason)
                .setParameter("updatedAt", row.updatedAt)
                .setParameter("updatedBy", row.updatedByPrincipalId)
                .setParameter("version", row.version)
                .setParameter("organizationId", row.organizationId)
                .setParameter("id", row.id)
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        finishUpdate(
                "Connection",
                required.id(),
                expectedVersion,
                affected,
                ConnectionEntity.class,
                row.organizationId);
        return findById(required.organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("Connection", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Connection> findById(OrganizationId organizationId, ConnectionId id) {
        return entityManager
                .createQuery(
                        "SELECT value FROM ConnectionEntity value WHERE value.organizationId = :organizationId AND value.id = :id",
                        ConnectionEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Connection> findByOwner(ProviderOwner owner) {
        ProviderOwner required = Objects.requireNonNull(owner, "owner");
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ConnectionEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.ownerType = :ownerType
                          AND value.ownerId = :ownerId
                        ORDER BY value.updatedAt DESC, value.id DESC
                        """,
                        ConnectionEntity.class)
                .setParameter("organizationId", required.organizationId().value())
                .setParameter("ownerType", required.type().name())
                .setParameter("ownerId", required.ownerId())
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public ConnectionGrant create(ConnectionGrant grant) {
        ConnectionGrant required = Objects.requireNonNull(grant, "grant");
        requireNew("connectionGrant.version", required.version());
        ConnectionGrantEntity row = mapper.toEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toDomain(row);
    }

    @Override
    @Transactional
    public ConnectionGrant update(ConnectionGrant grant) {
        ConnectionGrant required = Objects.requireNonNull(grant, "grant");
        ConnectionGrantEntity row = mapper.toEntity(required);
        long expectedVersion = expectedVersion("connectionGrant.version", required.version());
        int affected = entityManager
                .createQuery(
                        """
                        UPDATE ConnectionGrantEntity value
                        SET value.status = :status,
                            value.terminalReason = :terminalReason,
                            value.updatedAt = :updatedAt,
                            value.updatedByPrincipalId = :updatedBy,
                            value.version = :version
                        WHERE value.organizationId = :organizationId
                          AND value.id = :id
                          AND value.version = :expectedVersion
                        """)
                .setParameter("status", row.status)
                .setParameter("terminalReason", row.terminalReason)
                .setParameter("updatedAt", row.updatedAt)
                .setParameter("updatedBy", row.updatedByPrincipalId)
                .setParameter("version", row.version)
                .setParameter("organizationId", row.organizationId)
                .setParameter("id", row.id)
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        finishUpdate(
                "ConnectionGrant",
                required.id(),
                expectedVersion,
                affected,
                ConnectionGrantEntity.class,
                row.organizationId);
        return findById(required.organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("ConnectionGrant", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConnectionGrant> findById(
            OrganizationId organizationId, ConnectionGrantId id) {
        return entityManager
                .createQuery(
                        "SELECT value FROM ConnectionGrantEntity value WHERE value.organizationId = :organizationId AND value.id = :id",
                        ConnectionGrantEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConnectionGrant> findByConnectionAndGrantee(
            ConnectionId connectionId, ProviderOwner grantee) {
        ProviderOwner requiredGrantee = Objects.requireNonNull(grantee, "grantee");
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ConnectionGrantEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.connectionId = :connectionId
                          AND value.granteeType = :granteeType
                          AND value.granteeId = :granteeId
                        ORDER BY value.updatedAt DESC, value.id DESC
                        """,
                        ConnectionGrantEntity.class)
                .setParameter("organizationId", requiredGrantee.organizationId().value())
                .setParameter("connectionId", Objects.requireNonNull(connectionId).value())
                .setParameter("granteeType", requiredGrantee.type().name())
                .setParameter("granteeId", requiredGrantee.ownerId())
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public ProviderBinding create(ProviderBinding binding) {
        ProviderBinding required = Objects.requireNonNull(binding, "binding");
        requireNew("providerBinding.version", required.version());
        ProviderBindingEntity row = mapper.toEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toDomain(row);
    }

    @Override
    @Transactional
    public ProviderBinding update(ProviderBinding binding) {
        ProviderBinding required = Objects.requireNonNull(binding, "binding");
        ProviderBindingEntity row = mapper.toEntity(required);
        long expectedVersion = expectedVersion("providerBinding.version", required.version());
        int affected = entityManager
                .createQuery(
                        """
                        UPDATE ProviderBindingEntity value
                        SET value.status = :status,
                            value.updatedAt = :updatedAt,
                            value.updatedByPrincipalId = :updatedBy,
                            value.version = :version
                        WHERE value.organizationId = :organizationId
                          AND value.teamId = :teamId
                          AND value.workspaceId = :workspaceId
                          AND value.id = :id
                          AND value.version = :expectedVersion
                        """)
                .setParameter("status", row.status)
                .setParameter("updatedAt", row.updatedAt)
                .setParameter("updatedBy", row.updatedByPrincipalId)
                .setParameter("version", row.version)
                .setParameter("organizationId", row.organizationId)
                .setParameter("teamId", row.teamId)
                .setParameter("workspaceId", row.workspaceId)
                .setParameter("id", row.id)
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        finishBindingUpdate(
                "ProviderBinding",
                required.id(),
                expectedVersion,
                affected,
                row.organizationId,
                row.teamId,
                row.workspaceId);
        return findById(required.organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("ProviderBinding", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderBinding> findById(OrganizationId organizationId, ProviderBindingId id) {
        return entityManager
                .createQuery(
                        "SELECT value FROM ProviderBindingEntity value WHERE value.organizationId = :organizationId AND value.id = :id",
                        ProviderBindingEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderBinding> findCandidates(ProviderBindingQuery query) {
        ProviderBindingQuery required = Objects.requireNonNull(query, "query");
        StringBuilder jpql = new StringBuilder(
                """
                SELECT value FROM ProviderBindingEntity value
                WHERE value.organizationId = :organizationId
                  AND value.teamId = :teamId
                  AND value.workspaceId = :workspaceId
                  AND value.ownerType = :ownerType
                  AND value.ownerId = :ownerId
                  AND value.providerType = :providerType
                  AND value.status = 'ACTIVE'
                  AND (value.targetType = 'WORKSPACE'
                """);
        if (required.workProjectId().isPresent()) {
            jpql.append(" OR (value.targetType = 'WORK_PROJECT' AND value.workProjectId = :workProjectId)");
        }
        jpql.append(") ORDER BY value.targetType, value.defaultUsage DESC, value.id");
        var persistenceQuery = entityManager
                .createQuery(jpql.toString(), ProviderBindingEntity.class)
                .setParameter("organizationId", required.organizationId().value())
                .setParameter("teamId", required.teamId().value())
                .setParameter("workspaceId", required.workspaceId().value())
                .setParameter("ownerType", required.owner().type().name())
                .setParameter("ownerId", required.owner().ownerId())
                .setParameter("providerType", required.providerType().name());
        required.workProjectId().ifPresent(value -> persistenceQuery.setParameter("workProjectId", value.value()));
        return persistenceQuery.getResultList().stream().map(mapper::toDomain).toList();
    }

    private void finishUpdate(
            String aggregateType,
            AggregateId id,
            long expectedVersion,
            int affected,
            Class<?> entityType,
            UUID organizationId) {
        entityManager.clear();
        if (affected != 0) {
            return;
        }
        Optional<Long> actualVersion = entityManager
                .createQuery(
                        "SELECT value.version FROM "
                                + entityType.getSimpleName()
                                + " value WHERE value.organizationId = :organizationId AND value.id = :id",
                        Long.class)
                .setParameter("organizationId", organizationId)
                .setParameter("id", id.value())
                .getResultStream()
                .findFirst();
        if (actualVersion.isEmpty()) {
            throw new AggregateNotFoundException(aggregateType, id);
        }
        throw new OptimisticLockConflictException(
                aggregateType, id, expectedVersion, actualVersion.orElseThrow());
    }

    private void finishBindingUpdate(
            String aggregateType,
            AggregateId id,
            long expectedVersion,
            int affected,
            UUID organizationId,
            UUID teamId,
            UUID workspaceId) {
        entityManager.clear();
        if (affected != 0) {
            return;
        }
        // Use the same trusted Scope as the failed update so this branch cannot disclose a
        // Binding or its version from another Team or Workspace.
        Optional<Long> actualVersion = entityManager
                .createQuery(
                        """
                        SELECT value.version FROM ProviderBindingEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.teamId = :teamId
                          AND value.workspaceId = :workspaceId
                          AND value.id = :id
                        """,
                        Long.class)
                .setParameter("organizationId", organizationId)
                .setParameter("teamId", teamId)
                .setParameter("workspaceId", workspaceId)
                .setParameter("id", id.value())
                .getResultStream()
                .findFirst();
        if (actualVersion.isEmpty()) {
            throw new AggregateNotFoundException(aggregateType, id);
        }
        throw new OptimisticLockConflictException(
                aggregateType, id, expectedVersion, actualVersion.orElseThrow());
    }

    private static long expectedVersion(String field, long version) {
        long expected = version - 1;
        if (expected < 0) {
            throw new DomainValidationException(field, "must contain one uncommitted domain mutation");
        }
        return expected;
    }

    private static void requireNew(String field, long version) {
        if (version != 0) {
            throw new DomainValidationException(field, "must be zero when created");
        }
    }

    private static String requireKey(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field, "must not be blank");
        }
        return value.strip();
    }
}
