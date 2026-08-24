package io.crewscope.infrastructure.persistence.team;

import io.crewscope.application.agent.AgentInstance;
import io.crewscope.application.agent.AgentInstanceRepository;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.workspace.AgentProfileStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter that keeps one Agent Principal and AgentProfile lifecycle atomically aligned. */
@Repository
public class JpaAgentInstanceRepositoryAdapter implements AgentInstanceRepository {

    private final TeamPersistenceMapper mapper;

    @PersistenceContext private EntityManager entityManager;

    public JpaAgentInstanceRepositoryAdapter(TeamPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentInstance create(AgentInstance instance) {
        AgentInstance value = requireSynchronized(instance);
        if (value.principal().version() != 0
                || value.profile().version() != 0
                || value.profile().defaultProfile()) {
            throw new DomainValidationException(
                    "agentInstance", "create requires a new non-default Principal/Profile pair");
        }
        entityManager.persist(mapper.toEntity(value.principal()));
        entityManager.persist(mapper.toEntity(value.profile()));
        entityManager.flush();
        return value;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentInstance updateLifecycle(AgentInstance instance) {
        AgentInstance value = requireSynchronized(instance);
        long expectedPrincipalVersion = previous(value.principal().version(), "principal.version");
        long expectedProfileVersion = previous(value.profile().version(), "agentProfile.version");
        int principalUpdated = entityManager
                .createQuery(
                        """
                        UPDATE PrincipalEntity value
                        SET value.status = :status, value.version = :version,
                            value.updatedAt = :updatedAt
                        WHERE value.organizationId = :organizationId AND value.id = :id
                          AND value.version = :expected
                        """)
                .setParameter("status", value.principal().status().name())
                .setParameter("version", value.principal().version())
                .setParameter("updatedAt", value.principal().lifecycle().updatedAt().value())
                .setParameter("organizationId", value.profile().scope().organizationId().value())
                .setParameter("id", value.principal().id().value())
                .setParameter("expected", expectedPrincipalVersion)
                .executeUpdate();
        if (principalUpdated != 1) {
            throw principalConflict(value, expectedPrincipalVersion);
        }
        int profileUpdated = entityManager
                .createQuery(
                        """
                        UPDATE AgentProfileEntity value
                        SET value.status = :status, value.version = :version,
                            value.updatedAt = :updatedAt, value.updatedBy = :updatedBy
                        WHERE value.organizationId = :organizationId AND value.id = :id
                          AND value.version = :expected
                        """)
                .setParameter("status", value.profile().status().name())
                .setParameter("version", value.profile().version())
                .setParameter("updatedAt", value.profile().audit().updatedAt().value())
                .setParameter("updatedBy", value.profile().audit().updatedBy().orElseThrow().value())
                .setParameter("organizationId", value.profile().scope().organizationId().value())
                .setParameter("id", value.profile().id().value())
                .setParameter("expected", expectedProfileVersion)
                .executeUpdate();
        if (profileUpdated != 1) {
            throw profileConflict(value, expectedProfileVersion);
        }
        entityManager.flush();
        entityManager.clear();
        return value;
    }

    private AgentInstance requireSynchronized(AgentInstance instance) {
        AgentInstance value = Objects.requireNonNull(instance, "instance");
        AgentProfileStatus profileStatus = value.profile().status();
        PrincipalStatus expected = switch (profileStatus) {
            case ACTIVE -> PrincipalStatus.ACTIVE;
            case DISABLED -> PrincipalStatus.DISABLED;
            case ARCHIVED -> PrincipalStatus.ARCHIVED;
        };
        if (value.principal().status() != expected) {
            throw new DomainValidationException(
                    "agentInstance.status", "Principal and AgentProfile lifecycle must be synchronized");
        }
        return value;
    }

    private RuntimeException principalConflict(AgentInstance value, long expected) {
        Long actual = entityManager
                .createQuery(
                        """
                        SELECT principal.version FROM PrincipalEntity principal
                        WHERE principal.organizationId = :organizationId AND principal.id = :id
                        """,
                        Long.class)
                .setParameter("organizationId", value.profile().scope().organizationId().value())
                .setParameter("id", value.principal().id().value())
                .getResultStream()
                .findFirst()
                .orElse(null);
        return actual == null
                ? new AggregateNotFoundException("Principal", value.principal().id())
                : new OptimisticLockConflictException(
                        "Principal", value.principal().id(), expected, actual);
    }

    private RuntimeException profileConflict(AgentInstance value, long expected) {
        Long actual = entityManager
                .createQuery(
                        """
                        SELECT profile.version FROM AgentProfileEntity profile
                        WHERE profile.organizationId = :organizationId AND profile.id = :id
                        """,
                        Long.class)
                .setParameter("organizationId", value.profile().scope().organizationId().value())
                .setParameter("id", value.profile().id().value())
                .getResultStream()
                .findFirst()
                .orElse(null);
        return actual == null
                ? new AggregateNotFoundException("AgentProfile", value.profile().id())
                : new OptimisticLockConflictException(
                        "AgentProfile", value.profile().id(), expected, actual);
    }

    private static long previous(long version, String field) {
        if (version < 1) {
            throw new DomainValidationException(field, "must advance for a lifecycle update");
        }
        return version - 1;
    }
}
