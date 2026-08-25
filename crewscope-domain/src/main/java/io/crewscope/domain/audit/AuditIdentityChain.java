package io.crewscope.domain.audit;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.Optional;

/** Distinguishes the initiating human, effective actor and executing Agent. */
public record AuditIdentityChain(
        Optional<PrincipalId> initiatorId,
        EventActor actor,
        Optional<PrincipalId> agentPrincipalId) {

    public AuditIdentityChain {
        initiatorId = Objects.requireNonNull(initiatorId, "initiatorId");
        actor = Objects.requireNonNull(actor, "actor");
        agentPrincipalId = Objects.requireNonNull(agentPrincipalId, "agentPrincipalId");
        boolean agentActor = isAgent(actor.type());
        if (agentActor != agentPrincipalId.isPresent()
                || agentActor
                        && !agentPrincipalId.orElseThrow().equals(actor.id().orElseThrow())) {
            throw new DomainValidationException(
                    "auditIdentity.agentPrincipalId",
                    "must identify exactly the effective Agent actor");
        }
        if (actor.type() == EventActorType.USER
                && initiatorId.filter(actor.id().orElseThrow()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "auditIdentity.initiatorId",
                    "must equal the effective USER actor");
        }
    }

    public static AuditIdentityChain from(
            Optional<PrincipalId> initiatorId, EventActor actor) {
        EventActor requiredActor = Objects.requireNonNull(actor, "actor");
        return new AuditIdentityChain(
                initiatorId,
                requiredActor,
                isAgent(requiredActor.type()) ? requiredActor.id() : Optional.empty());
    }

    private static boolean isAgent(EventActorType type) {
        return type == EventActorType.PERSONAL_AGENT
                || type == EventActorType.TEAM_AGENT
                || type == EventActorType.SPECIALIST_AGENT;
    }
}
