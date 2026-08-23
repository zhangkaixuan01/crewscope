package io.crewscope.domain.action.event;

import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.shared.DomainEvent;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Safe event announcing a review-approved immutable action graph without PR content or credentials. */
public record ActionBundlePlanned(
        UUID actionBundleId,
        UUID taskId,
        UUID taskExecutionId,
        UUID reviewDecisionId,
        String bundleDigest,
        List<String> actionKinds,
        List<String> actionDigests,
        String validUntil) implements DomainEvent {

    public ActionBundlePlanned {
        actionBundleId = Objects.requireNonNull(actionBundleId, "actionBundleId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        reviewDecisionId = Objects.requireNonNull(reviewDecisionId, "reviewDecisionId");
        bundleDigest = Objects.requireNonNull(bundleDigest, "bundleDigest");
        actionKinds = List.copyOf(Objects.requireNonNull(actionKinds, "actionKinds"));
        actionDigests = List.copyOf(Objects.requireNonNull(actionDigests, "actionDigests"));
        validUntil = Objects.requireNonNull(validUntil, "validUntil");
    }

    public static ActionBundlePlanned from(ActionBundle bundle) {
        ActionBundle value = Objects.requireNonNull(bundle, "bundle");
        return new ActionBundlePlanned(
                value.id().value(),
                value.authority().taskId().value(),
                value.authority().taskExecutionId().value(),
                value.authority().reviewDecision().id().value(),
                value.digest().toString(),
                value.actions().stream().map(action -> action.kind().name()).toList(),
                value.actions().stream().map(action -> action.digest().toString()).toList(),
                value.validUntil().toString());
    }
}
