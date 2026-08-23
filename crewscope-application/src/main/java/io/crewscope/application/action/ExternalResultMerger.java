package io.crewscope.application.action;

import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionReceiptReference;
import io.crewscope.domain.action.ExternalMergeOutcome;
import io.crewscope.domain.action.ExternalMergeResult;
import io.crewscope.domain.action.ExternalObservation;
import io.crewscope.domain.action.ExternalResult;
import io.crewscope.domain.action.ExternalResultId;
import io.crewscope.domain.action.PlannedAction;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Single transactional merge path shared by active queries and committed Webhook observations. */
public final class ExternalResultMerger {

    private final ExternalObservationRepository observations;
    private final ExternalResultRepository results;
    private final ActionReceiptRepository receipts;
    private final ActionWorkerEventPublisher events;

    public ExternalResultMerger(
            ExternalObservationRepository observations,
            ExternalResultRepository results,
            ActionReceiptRepository receipts,
            ActionWorkerEventPublisher events) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.results = Objects.requireNonNull(results, "results");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Appends the immutable observation and applies the one version-first merge function. */
    public ExternalMergeResult merge(
            ActionDispatch dispatch,
            ActionBundle bundle,
            PlannedAction action,
            ExternalObservation observation) {
        ActionDispatch requiredDispatch = Objects.requireNonNull(dispatch, "dispatch");
        ActionBundle requiredBundle = Objects.requireNonNull(bundle, "bundle");
        PlannedAction requiredAction = Objects.requireNonNull(action, "action");
        ExternalObservation requiredObservation = Objects.requireNonNull(
                observation, "observation");
        OrganizationId organizationId = requiredDispatch.scope().organizationId();
        observations.appendIfAbsent(organizationId, requiredObservation);
        Optional<ActionReceiptReference> terminalReceipt = receipts.findReceiptByAction(
                        organizationId, requiredAction.id())
                .map(value -> value.reference());
        PrincipalId actorId = requiredDispatch.audit().updatedBy().orElseThrow();

        ExternalResult current = results.findByAction(organizationId, requiredAction.id())
                .orElse(null);
        ExternalMergeResult merged;
        boolean needsUpdate;
        if (current == null) {
            ExternalResult candidate = ExternalResult.observeFirstFromTrustedSource(
                    ExternalResultId.generate(),
                    requiredDispatch,
                    requiredAction,
                    requiredObservation,
                    actorId);
            ExternalResult committed = results.insert(candidate);
            if (committed.lastObservationKey().equals(requiredObservation.observationKey())) {
                merged = new ExternalMergeResult(
                        committed,
                        committed.id().equals(candidate.id())
                                ? ExternalMergeOutcome.APPLIED
                                : ExternalMergeOutcome.DUPLICATE);
                needsUpdate = false;
            } else {
                merged = committed.mergeFromTrustedSource(
                        committed.version(), requiredObservation, terminalReceipt, actorId);
                needsUpdate = merged.changed();
            }
        } else {
            merged = current.mergeFromTrustedSource(
                    current.version(), requiredObservation, terminalReceipt, actorId);
            needsUpdate = merged.changed();
        }
        ExternalResult committed = needsUpdate
                ? results.update(merged.result())
                : merged.result();
        ExternalMergeResult result = new ExternalMergeResult(committed, merged.outcome());
        if (merged.changed()) {
            events.externalResultMerged(
                    committed,
                    merged.outcome(),
                    requiredBundle,
                    correlation(requiredAction));
        }
        return result;
    }

    private static UUID correlation(PlannedAction action) {
        return action.id().value();
    }
}
