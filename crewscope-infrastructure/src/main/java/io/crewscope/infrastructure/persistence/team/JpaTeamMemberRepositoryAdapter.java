package io.crewscope.infrastructure.persistence.team;

import static io.crewscope.infrastructure.persistence.team.JpaTeamRepositoryAdapter.previousVersion;

import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JPA membership adapter and tenant-scoped membership read model. */
@Repository
public class JpaTeamMemberRepositoryAdapter implements TeamMemberRepository, TeamMembershipQuery {
    private final TeamPersistenceMapper mapper;
    @PersistenceContext private EntityManager entityManager;

    public JpaTeamMemberRepositoryAdapter(TeamPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public TeamMember create(TeamMember value) {
        TeamMember required = Objects.requireNonNull(value, "member");
        if (required.version() != 0) {
            throw new DomainValidationException("teamMember.version", "must be zero when created");
        }
        entityManager.persist(mapper.toEntity(required));
        entityManager.flush();
        return required;
    }

    @Override
    @Transactional
    public TeamMember update(TeamMember value) {
        TeamMember required = Objects.requireNonNull(value, "member");
        long expected = previousVersion(required.version(), "teamMember.version");
        int affected =
                entityManager
                        .createQuery(
                                """
                                UPDATE TeamMemberEntity value
                                SET value.status = :status, value.joinMethod = :joinMethod,
                                    value.invitedBy = :invitedBy, value.joinedAt = :joinedAt,
                                    value.lastActiveAt = :lastActiveAt, value.updatedAt = :updatedAt,
                                    value.version = :version
                                WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                                  AND value.id = :id AND value.version = :expected
                                """)
                        .setParameter("status", required.status().name())
                        .setParameter("joinMethod", required.joinMethod().name())
                        .setParameter(
                                "invitedBy",
                                required.invitedByPrincipalId().map(id -> id.value()).orElse(null))
                        .setParameter(
                                "joinedAt", required.joinedAt().map(t -> t.value()).orElse(null))
                        .setParameter(
                                "lastActiveAt",
                                required.lastActiveAt().map(t -> t.value()).orElse(null))
                        .setParameter("updatedAt", required.lifecycle().updatedAt().value())
                        .setParameter("version", required.version())
                        .setParameter("organizationId", required.scope().organizationId().value())
                        .setParameter("teamId", required.scope().teamId().value())
                        .setParameter("id", required.id().value())
                        .setParameter("expected", expected)
                        .executeUpdate();
        entityManager.clear();
        verify(affected, required, expected);
        return findById(required.scope().organizationId(), required.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamMember> findById(OrganizationId organizationId, TeamMemberId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM TeamMemberEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        TeamMemberEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamMember> findByTeamAndUserPrincipalId(
            OrganizationId organizationId, TeamId teamId, PrincipalId userPrincipalId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM TeamMemberEntity value
                        WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                          AND value.userPrincipalId = :userPrincipalId
                        """,
                        TeamMemberEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("teamId", Objects.requireNonNull(teamId).value())
                .setParameter("userPrincipalId", Objects.requireNonNull(userPrincipalId).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TeamMember> findByTeam(OrganizationId organizationId, TeamId teamId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM TeamMemberEntity value
                        WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                        ORDER BY value.createdAt, value.id
                        """,
                        TeamMemberEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("teamId", Objects.requireNonNull(teamId).value())
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    private void verify(int affected, TeamMember value, long expected) {
        if (affected != 0) {
            return;
        }
        Optional<Long> actual =
                entityManager
                        .createQuery(
                                """
                                SELECT value.version FROM TeamMemberEntity value
                                WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                                  AND value.id = :id
                                """,
                                Long.class)
                        .setParameter("organizationId", value.scope().organizationId().value())
                        .setParameter("teamId", value.scope().teamId().value())
                        .setParameter("id", value.id().value())
                        .getResultStream()
                        .findFirst();
        if (actual.isEmpty()) {
            throw new AggregateNotFoundException("TeamMember", value.id());
        }
        throw new OptimisticLockConflictException(
                "TeamMember", value.id(), expected, actual.orElseThrow());
    }
}
