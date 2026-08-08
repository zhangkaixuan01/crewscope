package io.crewscope.domain.team;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Migrated Team fact that still needs an accountable owner and default Workspace. */
public record UninitializedTeam(
    TeamId id,
    OrganizationId organizationId,
    String name,
    TeamStatus status,
    long version,
    AuditMetadata audit) {

  public UninitializedTeam {
    id = Objects.requireNonNull(id, "id");
    organizationId = Objects.requireNonNull(organizationId, "organizationId");
    if (name == null || name.isBlank() || name.strip().length() > Team.MAX_NAME_LENGTH) {
      throw new DomainValidationException("uninitializedTeam.name", "must be a valid Team name");
    }
    name = name.strip();
    status = Objects.requireNonNull(status, "status");
    if (status != TeamStatus.ACTIVE) {
      throw new DomainValidationException(
          "uninitializedTeam.status", "must be ACTIVE before initialization");
    }
    if (version < 0) {
      throw new DomainValidationException("uninitializedTeam.version", "must not be negative");
    }
    audit = Objects.requireNonNull(audit, "audit");
  }
}
