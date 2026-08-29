package io.crewscope.infrastructure.persistence.team;

import io.crewscope.application.identity.PrincipalProvisioningResult;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JPA identity lookup adapter with explicit Organization and external-subject boundaries. */
@Repository
public class JpaPrincipalRepositoryAdapter implements PrincipalRepository {

  private final TeamPersistenceMapper mapper;

  @PersistenceContext private EntityManager entityManager;

  public JpaPrincipalRepositoryAdapter(TeamPersistenceMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Principal> findById(OrganizationId organizationId, PrincipalId principalId) {
    return entityManager
        .createQuery(
            """
            SELECT value FROM PrincipalEntity value
            WHERE value.organizationId = :organizationId AND value.id = :id
            """,
            PrincipalEntity.class)
        .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
        .setParameter("id", Objects.requireNonNull(principalId).value())
        .getResultStream()
        .findFirst()
        .map(mapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Principal> findByExternalIdentity(
      OrganizationId organizationId, String provider, String subject) {
    return entityManager
        .createQuery(
            """
            SELECT value FROM PrincipalEntity value
            WHERE value.organizationId = :organizationId
              AND value.identityProvider = :provider
              AND value.externalSubject = :subject
            """,
            PrincipalEntity.class)
        .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
        .setParameter("provider", requireText(provider, "provider"))
        .setParameter("subject", requireText(subject, "subject"))
        .getResultStream()
        .findFirst()
        .map(mapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean organizationExists(OrganizationId organizationId) {
    return !entityManager
        .createNativeQuery(
            "SELECT 1 FROM crewscope.organization WHERE id = :organizationId", Integer.class)
        .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Principal createLocalUser(Principal candidate) {
    Principal value = requireLocalUserCandidate(candidate);
    entityManager.persist(mapper.toEntity(value));
    entityManager.flush();
    return findById(value.scope().organizationId(), value.id())
        .orElseThrow(
            () -> new IllegalStateException("Local USER Principal was not visible after creation"));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public PrincipalProvisioningResult provisionUser(Principal candidate) {
    Principal value = requireProvisioningCandidate(candidate);
    var external = value.externalIdentity().orElseThrow();
    int inserted =
        entityManager
            .createNativeQuery(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type, owner_principal_id,
                    display_name, identity_provider, external_subject, visibility, status,
                    version, created_at, updated_at
                ) VALUES (
                    :id, :organizationId, NULL, 'USER', NULL,
                    :displayName, :provider, :subject, :visibility, :status,
                    :version, :createdAt, :updatedAt
                )
                ON CONFLICT (organization_id, identity_provider, external_subject)
                    WHERE external_subject IS NOT NULL
                DO NOTHING
                """)
            .setParameter("id", value.id().value())
            .setParameter("organizationId", value.scope().organizationId().value())
            .setParameter("displayName", value.displayName())
            .setParameter("provider", external.provider())
            .setParameter("subject", external.subject())
            .setParameter("visibility", value.visibility().name())
            .setParameter("status", value.status().name())
            .setParameter("version", value.version())
            .setParameter("createdAt", value.lifecycle().createdAt().toOffsetDateTime())
            .setParameter("updatedAt", value.lifecycle().updatedAt().toOffsetDateTime())
            .executeUpdate();
    Principal resolved =
        findByExternalIdentity(
                value.scope().organizationId(), external.provider(), external.subject())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Atomic Principal provisioning did not produce a visible mapping"));
    return new PrincipalProvisioningResult(resolved, inserted == 1);
  }

  private static Principal requireProvisioningCandidate(Principal value) {
    Principal candidate = Objects.requireNonNull(value, "candidate");
    if (candidate.type() != PrincipalType.USER
        || candidate.scope().teamId().isPresent()
        || candidate.ownerPrincipalId().isPresent()
        || candidate.externalIdentity().isEmpty()
        || candidate.visibility() != PrincipalVisibility.ORGANIZATION
        || candidate.status() != PrincipalStatus.ACTIVE
        || candidate.version() != 0) {
      throw new IllegalArgumentException(
          "Provisioning candidate must be a new active organization-scoped USER with an external"
              + " identity");
    }
    return candidate;
  }

  private static Principal requireLocalUserCandidate(Principal value) {
    Principal candidate = Objects.requireNonNull(value, "candidate");
    if (candidate.type() != PrincipalType.USER
        || candidate.scope().teamId().isPresent()
        || candidate.ownerPrincipalId().isPresent()
        || candidate.externalIdentity().isPresent()
        || candidate.visibility() != PrincipalVisibility.ORGANIZATION
        || candidate.status() != PrincipalStatus.ACTIVE
        || candidate.version() != 0) {
      throw new IllegalArgumentException(
          "Local USER candidate must be new, active, organization-scoped and identity-free");
    }
    return candidate;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.strip();
  }
}
