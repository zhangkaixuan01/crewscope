package io.crewscope.infrastructure.persistence.team;

import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.Team;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** JPA Team adapter with explicit tenant and previous-version predicates. */
@Repository
public class JpaTeamRepositoryAdapter implements TeamRepository {
    private final TeamPersistenceMapper mapper;
    @PersistenceContext private EntityManager entityManager;

    public JpaTeamRepositoryAdapter(TeamPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public Team create(Team team) {
        Team required = Objects.requireNonNull(team, "team");
        if (required.version() != 0) {
            throw new DomainValidationException("team.version", "must be zero when created");
        }
        entityManager.persist(mapper.toEntity(required));
        entityManager.flush();
        return required;
    }

    @Override
    @Transactional
    public Team update(Team team) {
        Team required = Objects.requireNonNull(team, "team");
        long expected = previousVersion(required.version(), "team.version");
        int affected =
                entityManager
                        .createQuery(
                                """
                                UPDATE TeamEntity value
                                SET value.name = :name, value.ownerMemberId = :ownerMemberId,
                                    value.defaultWorkspaceId = :workspaceId, value.status = :status,
                                    value.updatedAt = :updatedAt, value.updatedBy = :updatedBy,
                                    value.version = :version
                                WHERE value.organizationId = :organizationId AND value.id = :id
                                  AND value.version = :expected
                                """)
                        .setParameter("name", required.name())
                        .setParameter("ownerMemberId", required.ownerMemberId().value())
                        .setParameter("workspaceId", required.defaultWorkspaceId().value())
                        .setParameter("status", required.status().name())
                        .setParameter("updatedAt", required.audit().updatedAt().value())
                        .setParameter(
                                "updatedBy", required.audit().updatedBy().orElseThrow().value())
                        .setParameter("version", required.version())
                        .setParameter("organizationId", required.organizationId().value())
                        .setParameter("id", required.id().value())
                        .setParameter("expected", expected)
                        .executeUpdate();
        entityManager.clear();
        verifyUpdate(affected, required.organizationId(), required.id(), expected);
        return findById(required.organizationId(), required.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Team> findById(OrganizationId organizationId, TeamId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM TeamEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        TeamEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    private void verifyUpdate(
            int affected, OrganizationId organizationId, TeamId id, long expected) {
        if (affected != 0) {
            return;
        }
        Optional<Long> actual =
                entityManager
                        .createQuery(
                                """
                                SELECT value.version FROM TeamEntity value
                                WHERE value.organizationId = :organizationId AND value.id = :id
                                """,
                                Long.class)
                        .setParameter("organizationId", organizationId.value())
                        .setParameter("id", id.value())
                        .getResultStream()
                        .findFirst();
        if (actual.isEmpty()) {
            throw new AggregateNotFoundException("Team", id);
        }
        throw new OptimisticLockConflictException("Team", id, expected, actual.orElseThrow());
    }

    static long previousVersion(long version, String field) {
        if (version <= 0) {
            throw new DomainValidationException(
                    field, "must contain one uncommitted domain mutation");
        }
        return version - 1;
    }
}
