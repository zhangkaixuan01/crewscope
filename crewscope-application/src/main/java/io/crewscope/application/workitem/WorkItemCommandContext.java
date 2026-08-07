package io.crewscope.application.workitem;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-resolved identity and scope for a WorkItem command.
 *
 * <p>HTTP, AG-UI and Agent tool adapters build this context after authentication and authorization;
 * client request bodies never supply these trusted facts directly.
 */
public record WorkItemCommandContext(
        OrganizationId organizationId,
        TeamId teamId,
        WorkspaceId workspaceId,
        EventActorType actorType,
        PrincipalId actorId,
        UUID correlationId,
        Optional<UUID> causationId,
        Optional<String> idempotencyKey) {

    public WorkItemCommandContext {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        actorType = Objects.requireNonNull(actorType, "actorType");
        actorId = Objects.requireNonNull(actorId, "actorId");
        correlationId = requireUuid(correlationId, "correlationId");
        causationId = Objects.requireNonNull(causationId, "causationId")
                .map(value -> requireUuid(value, "causationId"));
        idempotencyKey = normalizeIdempotencyKey(idempotencyKey);
    }

    private static UUID requireUuid(UUID value, String name) {
        UUID required = Objects.requireNonNull(value, name);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(name + " must not use the nil UUID");
        }
        return required;
    }

    private static Optional<String> normalizeIdempotencyKey(Optional<String> value) {
        return Objects.requireNonNull(value, "idempotencyKey")
                .map(IdempotencyKey::from)
                .map(IdempotencyKey::value);
    }
}
