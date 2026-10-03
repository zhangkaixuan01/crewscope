package io.crewscope.application.embedding;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One Team-scoped embedding batch request (I01a): the caller supplies the stable command
 * identity used to derive deterministic usage-fact call ids, so retries of the same
 * command replay onto the same deduplication keys.
 */
public record TeamEmbeddingCommand(
        OrganizationId organizationId,
        TeamId teamId,
        PrincipalId actor,
        UUID commandId,
        UUID correlationId,
        List<String> inputs) {

    public TeamEmbeddingCommand {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        actor = Objects.requireNonNull(actor, "actor");
        commandId = Objects.requireNonNull(commandId, "commandId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must contain at least one item");
        }
    }
}
