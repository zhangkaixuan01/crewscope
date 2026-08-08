package io.crewscope.infrastructure.persistence.team;

import static io.crewscope.infrastructure.persistence.team.JpaTeamRepositoryAdapter.previousVersion;

import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.TeamMemberId;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JPA adapter for historical and active member role grants. */
@Repository
public class JpaMemberRoleRepositoryAdapter implements MemberRoleRepository {
    private final TeamPersistenceMapper mapper;
    @PersistenceContext private EntityManager entityManager;

    public JpaMemberRoleRepositoryAdapter(TeamPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public MemberRole create(MemberRole value) {
        MemberRole required = Objects.requireNonNull(value, "memberRole");
        if (required.version() != 0) {
            throw new DomainValidationException("memberRole.version", "must be zero when created");
        }
        entityManager.persist(mapper.toEntity(required));
        entityManager.flush();
        return required;
    }

    @Override
    @Transactional
    public MemberRole update(MemberRole value) {
        MemberRole required = Objects.requireNonNull(value, "memberRole");
        long expected = previousVersion(required.version(), "memberRole.version");
        int affected =
                entityManager
                        .createQuery(
                                """
                                UPDATE MemberRoleEntity value SET value.expiresAt = :expiresAt,
                                    value.revokedAt = :revokedAt, value.status = :status,
                                    value.updatedAt = :updatedAt, value.version = :version
                                WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                                  AND value.id = :id AND value.version = :expected
                                """)
                        .setParameter(
                                "expiresAt", required.expiresAt().map(t -> t.value()).orElse(null))
                        .setParameter(
                                "revokedAt", required.revokedAt().map(t -> t.value()).orElse(null))
                        .setParameter("status", required.status().name())
                        .setParameter("updatedAt", required.lifecycle().updatedAt().value())
                        .setParameter("version", required.version())
                        .setParameter(
                                "organizationId", required.teamScope().organizationId().value())
                        .setParameter("teamId", required.teamScope().teamId().value())
                        .setParameter("id", required.id().value())
                        .setParameter("expected", expected)
                        .executeUpdate();
        entityManager.clear();
        verify(affected, required, expected);
        return findById(required.teamScope().organizationId(), required.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberRole> findById(OrganizationId organizationId, MemberRoleId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM MemberRoleEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        MemberRoleEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberRole> findByMember(OrganizationId organizationId, TeamMemberId memberId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM MemberRoleEntity value
                        WHERE value.organizationId = :organizationId AND value.teamMemberId = :memberId
                        ORDER BY value.grantedAt, value.id
                        """,
                        MemberRoleEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("memberId", Objects.requireNonNull(memberId).value())
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    private void verify(int affected, MemberRole value, long expected) {
        if (affected != 0) {
            return;
        }
        Optional<Long> actual =
                entityManager
                        .createQuery(
                                """
                                SELECT value.version FROM MemberRoleEntity value
                                WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                                  AND value.id = :id
                                """,
                                Long.class)
                        .setParameter("organizationId", value.teamScope().organizationId().value())
                        .setParameter("teamId", value.teamScope().teamId().value())
                        .setParameter("id", value.id().value())
                        .getResultStream()
                        .findFirst();
        if (actual.isEmpty()) {
            throw new AggregateNotFoundException("MemberRole", value.id());
        }
        throw new OptimisticLockConflictException(
                "MemberRole", value.id(), expected, actual.orElseThrow());
    }
}
