package io.crewscope.domain.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;
import java.util.Optional;

/** Resolved current Principal and optional Team membership proposed for a responsibility. */
public record TaskIntentCandidate(Principal principal, Optional<TeamMember> member) {

    public TaskIntentCandidate {
        principal = Objects.requireNonNull(principal, "principal");
        member = Objects.requireNonNull(member, "member");
    }

    public static TaskIntentCandidate user(Principal principal, TeamMember member) {
        return new TaskIntentCandidate(
                Objects.requireNonNull(principal, "principal"),
                Optional.of(Objects.requireNonNull(member, "member")));
    }

    public static TaskIntentCandidate agent(Principal principal) {
        return new TaskIntentCandidate(
                Objects.requireNonNull(principal, "principal"), Optional.empty());
    }
}
