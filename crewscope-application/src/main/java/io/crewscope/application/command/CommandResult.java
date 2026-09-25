package io.crewscope.application.command;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable coordinates only: never stores a command body, title, credential or HTTP response. */
public record CommandResult(
    OrganizationId organizationId,
    IdempotencyKey idempotencyKey,
    PrincipalId actorId,
    String commandType,
    TeamId teamId,
    Optional<WorkProjectId> projectId,
    ResourceType resourceType,
    UUID resourceId,
    long resourceVersion,
    CommandReceipt receipt,
    UtcTimestamp createdAt) {

  public enum ResourceType { WORK_PROJECT, WORK_ITEM, CONVERSATION, TEAM_MEMBER }

  public CommandResult {
    Objects.requireNonNull(organizationId, "organizationId");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(teamId, "teamId");
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(receipt, "receipt");
    Objects.requireNonNull(createdAt, "createdAt");
    if (commandType == null || !commandType.matches("[A-Z][A-Z0-9_]{0,99}")) {
      throw new IllegalArgumentException("Invalid command type");
    }
    if (resourceId.equals(new UUID(0, 0)) || resourceVersion < 0) {
      throw new IllegalArgumentException("Invalid command result coordinate");
    }
    boolean projectScoped =
        resourceType == ResourceType.WORK_PROJECT || resourceType == ResourceType.WORK_ITEM;
    if (projectScoped ? projectId.isEmpty() : projectId.isPresent()) {
      throw new IllegalArgumentException("Result project coordinate does not match its type");
    }
    if (resourceType == ResourceType.WORK_PROJECT
        && !projectId.orElseThrow().value().equals(resourceId)) {
      throw new IllegalArgumentException("Project result must locate its own project");
    }
  }
}
