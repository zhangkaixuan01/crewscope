package io.crewscope.application.agent;

import io.crewscope.domain.agent.AgentModelBindingKind;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.Optional;

/** Explicit client draft for a direct model pair or TEAM-default inheritance. */
public record AgentModelBindingDraft(
        AgentModelBindingKind kind,
        Optional<AgentModelSelectionDraft> primary,
        Optional<AgentModelSelectionDraft> fallback) {

    public AgentModelBindingDraft {
        kind = Objects.requireNonNull(kind, "kind");
        primary = Objects.requireNonNull(primary, "primary");
        fallback = Objects.requireNonNull(fallback, "fallback");
        boolean valid = switch (kind) {
            case DIRECT -> primary.isPresent();
            case INHERIT_TEAM_DEFAULT -> primary.isEmpty() && fallback.isEmpty();
            case ORCHESTRATION_ONLY -> false;
        };
        if (!valid || (fallback.isPresent() && fallback.equals(primary))) {
            throw new DomainValidationException(
                    "agentConfigurationDraft.modelBinding",
                    "must be a distinct direct pair or TEAM-default inheritance");
        }
    }

    public static AgentModelBindingDraft direct(
            AgentModelSelectionDraft primary,
            Optional<AgentModelSelectionDraft> fallback) {
        return new AgentModelBindingDraft(
                AgentModelBindingKind.DIRECT,
                Optional.of(Objects.requireNonNull(primary, "primary")),
                fallback);
    }

    public static AgentModelBindingDraft inheritTeamDefault() {
        return new AgentModelBindingDraft(
                AgentModelBindingKind.INHERIT_TEAM_DEFAULT,
                Optional.empty(),
                Optional.empty());
    }
}
