package io.crewscope.domain.agent;

import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.Set;

/** Current authorization, responsibility, budget and quota facts checked at execution start. */
public record AgentExecutionAuthorizationFacts(
        PrincipalId requestingPrincipalId,
        boolean principalActive,
        boolean teamParticipationActive,
        boolean responsibilityAuthorized,
        boolean budgetAvailable,
        boolean quotaAvailable,
        Set<ModelConnectionId> usableConnectionIds) {

    public AgentExecutionAuthorizationFacts {
        requestingPrincipalId = Objects.requireNonNull(
                requestingPrincipalId, "requestingPrincipalId");
        usableConnectionIds = Set.copyOf(
                Objects.requireNonNull(usableConnectionIds, "usableConnectionIds"));
    }
}
