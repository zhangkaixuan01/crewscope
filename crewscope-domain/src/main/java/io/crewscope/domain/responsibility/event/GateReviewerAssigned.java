package io.crewscope.domain.responsibility.event;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityDecision;
import io.crewscope.domain.responsibility.ReviewerEligibilityMode;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Version 1 Gate Reviewer fact carrying immutable policy decision evidence. */
public record GateReviewerAssigned(
    UUID workItemId,
    UUID actorPrincipalId,
    ReviewerEligibilityMode eligibilityMode,
    Set<ResponsibilityRole> conflictingRoles,
    Optional<UUID> policyPackId,
    Optional<Long> policyPackVersion,
    Optional<String> overrideReason)
    implements DomainEvent {

  public GateReviewerAssigned {
    workItemId = AggregateId.requireValue(workItemId, "GateReviewerAssigned.workItemId");
    actorPrincipalId =
        AggregateId.requireValue(actorPrincipalId, "GateReviewerAssigned.actorPrincipalId");
    eligibilityMode = Objects.requireNonNull(eligibilityMode, "eligibilityMode");
    conflictingRoles = Set.copyOf(Objects.requireNonNull(conflictingRoles, "conflictingRoles"));
    policyPackId = Objects.requireNonNull(policyPackId, "policyPackId");
    policyPackVersion = Objects.requireNonNull(policyPackVersion, "policyPackVersion");
    overrideReason = Objects.requireNonNull(overrideReason, "overrideReason");
  }

  public static GateReviewerAssigned from(
      ResponsibilityAssignment assignment, ReviewerEligibilityDecision eligibility) {
    ResponsibilityAssignment source = Objects.requireNonNull(assignment, "assignment");
    ReviewerEligibilityDecision decision = Objects.requireNonNull(eligibility, "eligibility");
    return new GateReviewerAssigned(
        source.workItemId().value(),
        source.actorPrincipalId().value(),
        decision.mode(),
        decision.conflictingRoles(),
        decision.policyPack().map(reference -> reference.id().value()),
        decision.policyPack().map(reference -> reference.version()),
        decision.overrideReason());
  }
}
