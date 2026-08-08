package io.crewscope.infrastructure.persistence.team;

import static io.crewscope.infrastructure.persistence.team.JpaTeamRepositoryAdapter.previousVersion;

import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JPA adapter for role definitions and their JSON permission set. */
@Repository
public class JpaTeamRoleRepositoryAdapter implements TeamRoleRepository {
    private final TeamPersistenceMapper mapper;
    @PersistenceContext private EntityManager entityManager;

    public JpaTeamRoleRepositoryAdapter(TeamPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public List<TeamRole> createAll(List<TeamRole> roles) {
        List<TeamRole> required = List.copyOf(Objects.requireNonNull(roles, "roles"));
        required.forEach(
                role -> {
                    if (role.version() != 0) {
                        throw new DomainValidationException(
                                "teamRole.version", "must be zero when created");
                    }
                    entityManager.persist(mapper.toEntity(role));
                });
        entityManager.flush();
        return required;
    }

    @Override
    @Transactional
    public TeamRole update(TeamRole role) {
        TeamRole required = Objects.requireNonNull(role, "role");
        long expected = previousVersion(required.version(), "teamRole.version");
        int affected =
                entityManager
                        .createQuery(
                                """
                                UPDATE TeamRoleEntity value SET value.name = :name,
                                    value.description = :description, value.permissions = :permissions,
                                    value.status = :status, value.updatedAt = :updatedAt, value.version = :version
                                WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                                  AND value.id = :id AND value.version = :expected
                                """)
                        .setParameter("name", required.name())
                        .setParameter("description", required.description().orElse(null))
                        .setParameter(
                                "permissions",
                                required.permissions().stream().map(Enum::name).sorted().toList())
                        .setParameter("status", required.status().name())
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
    public Optional<TeamRole> findById(OrganizationId organizationId, TeamRoleId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM TeamRoleEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        TeamRoleEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TeamRole> findByTeam(OrganizationId organizationId, TeamId teamId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM TeamRoleEntity value
                        WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                        ORDER BY value.builtIn DESC, value.roleKey, value.id
                        """,
                        TeamRoleEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("teamId", Objects.requireNonNull(teamId).value())
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    private void verify(int affected, TeamRole value, long expected) {
        if (affected != 0) {
            return;
        }
        Optional<Long> actual =
                entityManager
                        .createQuery(
                                """
                                SELECT value.version FROM TeamRoleEntity value
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
            throw new AggregateNotFoundException("TeamRole", value.id());
        }
        throw new OptimisticLockConflictException(
                "TeamRole", value.id(), expected, actual.orElseThrow());
    }
}
