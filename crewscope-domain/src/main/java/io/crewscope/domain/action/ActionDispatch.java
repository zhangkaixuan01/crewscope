package io.crewscope.domain.action;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Durable, fenced scheduler state for one exact confirmed PlannedAction. */
public final class ActionDispatch {

    private final ActionDispatchId id;
    private final WorkItemScope scope;
    private final ActionBundleId bundleId;
    private final ActionBundleDigest bundleDigest;
    private final ConfirmationId confirmationId;
    private final PlannedActionId actionId;
    private final ActionDigest actionDigest;
    private final int sequence;
    private final List<ActionDependency> dependencies;
    private final ActionIdempotencyKey idempotencyKey;
    private final UtcTimestamp validUntil;
    private final ActionDispatchStatus status;
    private final Optional<ActionClaim> claim;
    private final long lastFencingToken;
    private final int claimAttempts;
    private final int reconciliationAttempts;
    private final UtcTimestamp notBefore;
    private final Optional<ActionReceiptReference> receipt;
    private final Optional<ActionCancellationReason> cancellationReason;
    private final CompensationDisposition compensationDisposition;
    private final long version;
    private final AuditMetadata audit;

    private ActionDispatch(
            ActionDispatchId id,
            WorkItemScope scope,
            ActionBundleId bundleId,
            ActionBundleDigest bundleDigest,
            ConfirmationId confirmationId,
            PlannedActionId actionId,
            ActionDigest actionDigest,
            int sequence,
            List<ActionDependency> dependencies,
            ActionIdempotencyKey idempotencyKey,
            UtcTimestamp validUntil,
            ActionDispatchStatus status,
            Optional<ActionClaim> claim,
            long lastFencingToken,
            int claimAttempts,
            int reconciliationAttempts,
            UtcTimestamp notBefore,
            Optional<ActionReceiptReference> receipt,
            Optional<ActionCancellationReason> cancellationReason,
            CompensationDisposition compensationDisposition,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
        this.bundleDigest = Objects.requireNonNull(bundleDigest, "bundleDigest");
        this.confirmationId = Objects.requireNonNull(confirmationId, "confirmationId");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.actionDigest = Objects.requireNonNull(actionDigest, "actionDigest");
        if (sequence < 1) {
            throw new DomainValidationException("actionDispatch.sequence", "must be positive");
        }
        this.sequence = sequence;
        this.dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        ActionIdempotencyKey expectedIdempotencyKey = ActionIdempotencyKey.derive(
                this.scope.organizationId(), this.bundleId, this.actionId, this.actionDigest);
        if (!this.idempotencyKey.equals(expectedIdempotencyKey)) {
            throw new DomainValidationException(
                    "actionDispatch.idempotencyKey",
                    "must be derived from the exact organization, Bundle and Action digest");
        }
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        this.status = Objects.requireNonNull(status, "status");
        this.claim = Objects.requireNonNull(claim, "claim");
        if (lastFencingToken < 0 || claimAttempts < 0 || reconciliationAttempts < 0) {
            throw new DomainValidationException(
                    "actionDispatch.counters", "must not be negative");
        }
        this.lastFencingToken = lastFencingToken;
        this.claimAttempts = claimAttempts;
        this.reconciliationAttempts = reconciliationAttempts;
        this.notBefore = Objects.requireNonNull(notBefore, "notBefore");
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        this.cancellationReason = Objects.requireNonNull(
                cancellationReason, "cancellationReason");
        this.compensationDisposition = Objects.requireNonNull(
                compensationDisposition, "compensationDisposition");
        if (version < 0) {
            throw new DomainValidationException("actionDispatch.version", "must not be negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
        validateShape();
    }

    /** Schedules one row in the same transaction as Bundle, Confirmation, event and Outbox. */
    public static ActionDispatch schedule(
            ActionDispatchId id,
            ActionBundle bundle,
            PlannedAction action,
            Confirmation confirmation,
            Principal actor,
            UtcTimestamp createdAt) {
        ActionBundle requiredBundle = Objects.requireNonNull(bundle, "bundle");
        PlannedAction requiredAction = Objects.requireNonNull(action, "action");
        Confirmation requiredConfirmation = Objects.requireNonNull(confirmation, "confirmation");
        requireBundleAction(requiredBundle, requiredAction);
        requireConfirmation(requiredConfirmation, requiredBundle);
        PrincipalId actorId = requireScopedActor(actor, requiredBundle.authority().scope());
        UtcTimestamp requiredTime = Objects.requireNonNull(createdAt, "createdAt");
        if (requiredTime.compareTo(requiredConfirmation.confirmedAt()) < 0
                || requiredTime.compareTo(requiredConfirmation.validUntil()) >= 0) {
            throw new DomainValidationException(
                    "actionDispatch.createdAt", "must be inside the Confirmation validity window");
        }
        return new ActionDispatch(
                id,
                requiredBundle.authority().scope(),
                requiredBundle.id(),
                requiredBundle.digest(),
                requiredConfirmation.id(),
                requiredAction.id(),
                requiredAction.digest(),
                requiredAction.sequence(),
                requiredAction.dependencies(),
                ActionIdempotencyKey.derive(
                        requiredBundle.authority().scope().organizationId(),
                        requiredBundle.id(),
                        requiredAction.id(),
                        requiredAction.digest()),
                requiredConfirmation.validUntil(),
                ActionDispatchStatus.READY,
                Optional.empty(),
                0,
                0,
                0,
                requiredTime,
                Optional.empty(),
                Optional.empty(),
                CompensationDisposition.NOT_REQUIRED,
                0,
                AuditMetadata.createdBy(actorId, requiredTime));
    }

    public static ActionDispatch reconstitute(
            ActionDispatchId id,
            WorkItemScope scope,
            ActionBundleId bundleId,
            ActionBundleDigest bundleDigest,
            ConfirmationId confirmationId,
            PlannedActionId actionId,
            ActionDigest actionDigest,
            int sequence,
            List<ActionDependency> dependencies,
            ActionIdempotencyKey idempotencyKey,
            UtcTimestamp validUntil,
            ActionDispatchStatus status,
            Optional<ActionClaim> claim,
            long lastFencingToken,
            int claimAttempts,
            int reconciliationAttempts,
            UtcTimestamp notBefore,
            Optional<ActionReceiptReference> receipt,
            Optional<ActionCancellationReason> cancellationReason,
            CompensationDisposition compensationDisposition,
            long version,
            AuditMetadata audit) {
        return new ActionDispatch(
                id, scope, bundleId, bundleDigest, confirmationId, actionId, actionDigest, sequence,
                dependencies, idempotencyKey, validUntil, status, claim, lastFencingToken,
                claimAttempts, reconciliationAttempts, notBefore, receipt, cancellationReason,
                compensationDisposition, version, audit);
    }

    /** Claims READY for execution; expired RUNNING and UNKNOWN are fenced into reconciliation. */
    public ActionDispatch claim(
            long expectedVersion,
            ActionBundle bundle,
            ActionAuthorityFacts currentFacts,
            Confirmation confirmation,
            List<ActionReceipt> dependencyReceipts,
            ActionWorkerId workerId,
            UtcTimestamp now,
            UtcTimestamp leaseUntil) {
        requireVersion(expectedVersion);
        UtcTimestamp requiredNow = Objects.requireNonNull(now, "now");
        if (requiredNow.compareTo(notBefore) < 0) {
            throw new DomainValidationException(
                    "actionDispatch.notBefore", "is not claimable at the supplied time");
        }
        ActionClaimMode mode = claimMode(requiredNow);
        if (mode == ActionClaimMode.EXECUTE) {
            if (requiredNow.compareTo(validUntil) >= 0) {
                throw new DomainValidationException(
                        "actionDispatch.validUntil", "has expired for external execution");
            }
            requireCurrentAuthorization(bundle, currentFacts, confirmation, requiredNow);
        } else {
            requireReconciliationCoordinates(bundle, confirmation);
        }
        requireSuccessfulDependencies(dependencyReceipts);
        long nextTokenValue = lastFencingToken + 1;
        if (nextTokenValue < 1) {
            throw new DomainValidationException(
                    "actionDispatch.fencingToken", "has exhausted the supported range");
        }
        ActionClaim nextClaim = new ActionClaim(
                id, actionId, Objects.requireNonNull(workerId, "workerId"),
                new ActionFencingToken(nextTokenValue), mode, requiredNow, requiredNow, leaseUntil);
        return copy(
                mode == ActionClaimMode.EXECUTE
                        ? ActionDispatchStatus.RUNNING
                        : ActionDispatchStatus.RECONCILING,
                Optional.of(nextClaim),
                nextTokenValue,
                claimAttempts + 1,
                reconciliationAttempts,
                notBefore,
                receipt,
                cancellationReason,
                compensationDisposition,
                version + 1,
                audit.modifiedBy(audit.updatedBy().orElseThrow(), requiredNow));
    }

    /** Claims UNKNOWN or an expired Worker lease without requiring mutable execution authority. */
    public ActionDispatch claimForReconciliation(
            long expectedVersion,
            ActionBundle bundle,
            Confirmation confirmation,
            List<ActionReceipt> dependencyReceipts,
            ActionWorkerId workerId,
            UtcTimestamp now,
            UtcTimestamp leaseUntil) {
        if (status == ActionDispatchStatus.READY) {
            throw new InvalidStateTransitionException(
                    "ActionDispatch", id, status, ActionDispatchStatus.RECONCILING);
        }
        return claim(
                expectedVersion,
                bundle,
                null,
                confirmation,
                dependencyReceipts,
                workerId,
                now,
                leaseUntil);
    }

    public ActionDispatch heartbeat(
            long expectedVersion,
            ActionClaim ownership,
            UtcTimestamp now,
            UtcTimestamp replacementLeaseUntil) {
        requireVersion(expectedVersion);
        ActionClaim current = requireCurrentClaim(ownership, now);
        ActionClaim renewed = current.heartbeat(now, replacementLeaseUntil);
        return copy(
                status, Optional.of(renewed), lastFencingToken, claimAttempts,
                reconciliationAttempts, notBefore, receipt, cancellationReason,
                compensationDisposition, version + 1,
                audit.modifiedBy(audit.updatedBy().orElseThrow(), now));
    }

    /** Records an uncertain write outcome. It is never converted into an ordinary retry. */
    public ActionDispatch markUnknown(
            long expectedVersion, ActionClaim ownership, UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        requireState(ActionDispatchStatus.RUNNING, ActionDispatchStatus.UNKNOWN);
        requireCurrentClaim(ownership, occurredAt);
        return copy(
                ActionDispatchStatus.UNKNOWN, Optional.empty(), lastFencingToken, claimAttempts,
                reconciliationAttempts, occurredAt, receipt, cancellationReason,
                compensationDisposition, version + 1,
                audit.modifiedBy(audit.updatedBy().orElseThrow(), occurredAt));
    }

    /** Retries only when durable evidence proves that no external side effect occurred. */
    public ActionDispatch scheduleRetry(
            long expectedVersion,
            ActionClaim ownership,
            ActionRetryDirective directive,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        requireState(ActionDispatchStatus.RUNNING, ActionDispatchStatus.READY);
        requireCurrentClaim(ownership, occurredAt);
        ActionRetryDirective required = Objects.requireNonNull(directive, "directive");
        if (required.notBefore().compareTo(occurredAt) < 0
                || required.notBefore().compareTo(validUntil) >= 0) {
            throw new DomainValidationException(
                    "actionRetry.notBefore", "must be within the remaining Confirmation window");
        }
        return copy(
                ActionDispatchStatus.READY, Optional.empty(), lastFencingToken, claimAttempts,
                reconciliationAttempts, required.notBefore(), receipt, cancellationReason,
                compensationDisposition, version + 1,
                audit.modifiedBy(audit.updatedBy().orElseThrow(), occurredAt));
    }

    /** Returns to UNKNOWN or escalates to MANUAL_REVIEW after the configured bounded attempts. */
    public ActionDispatch recordInconclusiveReconciliation(
            long expectedVersion,
            ActionClaim ownership,
            int maximumAttempts,
            UtcTimestamp nextAttemptAt,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        requireState(ActionDispatchStatus.RECONCILING, ActionDispatchStatus.UNKNOWN);
        ActionClaim current = requireCurrentClaim(ownership, occurredAt);
        if (current.mode() != ActionClaimMode.RECONCILE || maximumAttempts < 1) {
            throw new DomainValidationException(
                    "actionDispatch.reconciliation", "requires a reconciliation claim and positive limit");
        }
        int nextAttempts = reconciliationAttempts + 1;
        ActionDispatchStatus target = nextAttempts >= maximumAttempts
                ? ActionDispatchStatus.MANUAL_REVIEW
                : ActionDispatchStatus.UNKNOWN;
        UtcTimestamp requiredNext = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (target == ActionDispatchStatus.UNKNOWN
                && requiredNext.compareTo(occurredAt) < 0) {
            throw new DomainValidationException(
                    "actionDispatch.nextAttemptAt", "must not precede the current attempt");
        }
        return copy(
                target, Optional.empty(), lastFencingToken, claimAttempts, nextAttempts,
                requiredNext, receipt, cancellationReason, compensationDisposition, version + 1,
                audit.modifiedBy(audit.updatedBy().orElseThrow(), occurredAt));
    }

    public ActionDispatch completeClaimed(
            long expectedVersion,
            ActionClaim ownership,
            ActionReceipt result,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        requireCurrentClaim(ownership, occurredAt);
        ActionReceipt required = requireReceipt(result);
        if (required.claim().filter(ownership::equals).isEmpty()) {
            throw new DomainValidationException(
                    "actionReceipt.claim", "must bind the current fenced claim");
        }
        if (status != ActionDispatchStatus.RUNNING
                && status != ActionDispatchStatus.RECONCILING) {
            throw new InvalidStateTransitionException(
                    "ActionDispatch", id, status, targetStatus(required.result()));
        }
        return complete(required, occurredAt);
    }

    /** Accepts a trusted Webhook/query conclusion while no execution claim owns the action. */
    public ActionDispatch completeFromObservation(
            long expectedVersion, ActionReceipt result, UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        ActionReceipt required = requireReceipt(result);
        if (required.claim().isPresent()
                || (required.source() != ActionResultSource.WEBHOOK
                        && required.source() != ActionResultSource.ACTIVE_QUERY)
                || (status != ActionDispatchStatus.UNKNOWN
                        && status != ActionDispatchStatus.RECONCILING)) {
            throw new DomainValidationException(
                    "actionReceipt.source", "must be a trusted observation for an uncertain action");
        }
        return complete(required, occurredAt);
    }

    public ActionDispatch resolveManually(
            long expectedVersion, ActionReceipt result, UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        ActionReceipt required = requireReceipt(result);
        if (status != ActionDispatchStatus.MANUAL_REVIEW
                || !required.result().isManual()
                || required.source() != ActionResultSource.MANUAL) {
            throw new DomainValidationException(
                    "actionReceipt", "manual resolution requires MANUAL_REVIEW and a manual Receipt");
        }
        return complete(required, occurredAt);
    }

    /** Cancels only an unclaimed READY action and writes its sole cancellation Receipt. */
    public ActionDispatch cancel(
            long expectedVersion,
            ActionReceipt cancellationReceipt,
            ActionCancellationReason reason,
            List<ActionReceipt> dependencyReceipts,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        requireState(ActionDispatchStatus.READY, ActionDispatchStatus.CANCELLED);
        ActionReceipt required = requireReceipt(cancellationReceipt);
        if (required.result() != ActionReceiptResult.CANCELLED
                || required.source() != ActionResultSource.CONTROL
                || required.claim().isPresent()) {
            throw new DomainValidationException(
                    "actionReceipt", "cancellation requires an unclaimed CONTROL Receipt");
        }
        if (!required.receivedAt().equals(Objects.requireNonNull(occurredAt, "occurredAt"))) {
            throw new DomainValidationException(
                    "actionReceipt.receivedAt", "must equal the authoritative cancellation time");
        }
        boolean dependencySucceeded = hasSuccessfulDependency(dependencyReceipts);
        return copy(
                ActionDispatchStatus.CANCELLED, Optional.empty(), lastFencingToken, claimAttempts,
                reconciliationAttempts, notBefore,
                Optional.of(required.reference()),
                Optional.of(Objects.requireNonNull(reason, "reason")),
                dependencySucceeded
                        ? CompensationDisposition.MANUAL_REVIEW_REQUIRED
                        : CompensationDisposition.NOT_REQUIRED,
                version + 1,
                audit.modifiedBy(audit.updatedBy().orElseThrow(), occurredAt));
    }

    private ActionDispatch complete(ActionReceipt result, UtcTimestamp occurredAt) {
        if (receipt.isPresent() || status.isTerminal()) {
            throw new DomainValidationException(
                    "actionReceipt", "cannot replace the sole logical terminal Receipt");
        }
        if (!result.receivedAt().equals(Objects.requireNonNull(occurredAt, "occurredAt"))) {
            throw new DomainValidationException(
                    "actionReceipt.receivedAt", "must equal the authoritative transition time");
        }
        return copy(
                targetStatus(result.result()), Optional.empty(), lastFencingToken, claimAttempts,
                reconciliationAttempts, notBefore, Optional.of(result.reference()),
                cancellationReason, compensationDisposition, version + 1,
                audit.modifiedBy(audit.updatedBy().orElseThrow(), occurredAt));
    }

    private ActionReceipt requireReceipt(ActionReceipt value) {
        ActionReceipt required = Objects.requireNonNull(value, "receipt");
        if (!scope.equals(required.scope())
                || !bundleId.equals(required.bundleId())
                || !bundleDigest.equals(required.bundleDigest())
                || !actionId.equals(required.actionId())
                || !actionDigest.equals(required.actionDigest())
                || !idempotencyKey.equals(required.idempotencyKey())) {
            throw new DomainValidationException(
                    "actionReceipt", "must bind this exact tenant, Bundle, Action and digest");
        }
        return required;
    }

    private ActionClaimMode claimMode(UtcTimestamp now) {
        return switch (status) {
            case READY -> ActionClaimMode.EXECUTE;
            case UNKNOWN -> ActionClaimMode.RECONCILE;
            case RUNNING, RECONCILING -> {
                ActionClaim current = claim.orElseThrow(() -> new DomainValidationException(
                        "actionDispatch.claim", "running state must retain its claim"));
                if (current.isActiveAt(now)) {
                    throw new DomainValidationException(
                            "actionDispatch.claim", "is still owned by an active Worker lease");
                }
                yield ActionClaimMode.RECONCILE;
            }
            default -> throw new InvalidStateTransitionException(
                    "ActionDispatch", id, status, ActionDispatchStatus.RUNNING);
        };
    }

    private void requireCurrentAuthorization(
            ActionBundle bundle,
            ActionAuthorityFacts facts,
            Confirmation confirmation,
            UtcTimestamp now) {
        ActionBundle requiredBundle = Objects.requireNonNull(bundle, "bundle");
        Confirmation requiredConfirmation = Objects.requireNonNull(confirmation, "confirmation");
        requireConfirmation(requiredConfirmation, requiredBundle);
        requireBundleAction(requiredBundle, requiredBundle.actions().stream()
                .filter(action -> action.id().equals(actionId))
                .findFirst()
                .orElseThrow(() -> new DomainValidationException(
                        "actionDispatch.actionId", "is absent from the current Bundle")));
        requiredConfirmation.requireAuthorizes(requiredBundle, facts, now);
    }

    private void requireReconciliationCoordinates(
            ActionBundle bundle, Confirmation confirmation) {
        ActionBundle requiredBundle = Objects.requireNonNull(bundle, "bundle");
        Confirmation requiredConfirmation = Objects.requireNonNull(confirmation, "confirmation");
        List<ConfirmedActionReference> confirmedActions = requiredBundle.actions().stream()
                .map(ConfirmedActionReference::from)
                .toList();
        boolean actionMatches = requiredBundle.actions().stream()
                .anyMatch(action -> action.id().equals(actionId)
                        && action.digest().equals(actionDigest));
        if (!scope.equals(requiredBundle.authority().scope())
                || !bundleId.equals(requiredBundle.id())
                || !bundleDigest.equals(requiredBundle.digest())
                || !confirmationId.equals(requiredConfirmation.id())
                || !scope.equals(requiredConfirmation.scope())
                || !bundleId.equals(requiredConfirmation.bundleId())
                || !bundleDigest.equals(requiredConfirmation.bundleDigest())
                || !confirmedActions.equals(requiredConfirmation.actions())
                || !actionMatches) {
            throw new DomainValidationException(
                    "actionDispatch.reconciliation",
                    "must retain the exact originally confirmed Bundle coordinates");
        }
    }

    private void requireSuccessfulDependencies(List<ActionReceipt> values) {
        Map<PlannedActionId, ActionReceipt> byAction = receiptsByAction(values);
        for (ActionDependency dependency : dependencies) {
            ActionReceipt predecessor = byAction.get(dependency.predecessorActionId());
            if (predecessor == null
                    || !predecessor.result().isSuccessful()
                    || !bundleId.equals(predecessor.bundleId())
                    || !bundleDigest.equals(predecessor.bundleDigest())) {
                throw new DomainValidationException(
                        "actionDispatch.dependencies",
                        "must each have one successful Receipt in the same Bundle");
            }
        }
    }

    private boolean hasSuccessfulDependency(List<ActionReceipt> values) {
        Map<PlannedActionId, ActionReceipt> byAction = receiptsByAction(values);
        return dependencies.stream().map(ActionDependency::predecessorActionId)
                .map(byAction::get)
                .filter(Objects::nonNull)
                .anyMatch(receiptValue -> receiptValue.result().isSuccessful()
                        && bundleId.equals(receiptValue.bundleId())
                        && bundleDigest.equals(receiptValue.bundleDigest()));
    }

    private static Map<PlannedActionId, ActionReceipt> receiptsByAction(
            List<ActionReceipt> values) {
        Map<PlannedActionId, ActionReceipt> result = new HashMap<>();
        for (ActionReceipt receiptValue : List.copyOf(Objects.requireNonNull(values, "receipts"))) {
            ActionReceipt previous = result.put(
                    Objects.requireNonNull(receiptValue, "receipt").actionId(), receiptValue);
            if (previous != null) {
                throw new DomainValidationException(
                        "actionDispatch.dependencies", "must not contain duplicate Receipts");
            }
        }
        return result;
    }

    private ActionClaim requireCurrentClaim(ActionClaim ownership, UtcTimestamp now) {
        ActionClaim required = Objects.requireNonNull(ownership, "ownership");
        ActionClaim current = claim.orElseThrow(() -> new DomainValidationException(
                "actionDispatch.claim", "has no current Worker claim"));
        if (!current.equals(required)
                || current.fencingToken().value() != lastFencingToken
                || !current.isActiveAt(now)) {
            throw new DomainValidationException(
                    "actionDispatch.claim", "must match the current active fenced ownership");
        }
        return current;
    }

    private void requireState(ActionDispatchStatus expected, ActionDispatchStatus target) {
        if (status != expected) {
            throw new InvalidStateTransitionException("ActionDispatch", id, status, target);
        }
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new OptimisticLockConflictException(
                    "ActionDispatch", id, expectedVersion, version);
        }
    }

    private void validateShape() {
        if ((claimAttempts == 0 && lastFencingToken != 0)
                || (claimAttempts > 0 && lastFencingToken < 1)
                || reconciliationAttempts > claimAttempts) {
            throw new DomainValidationException(
                    "actionDispatch.counters",
                    "must retain monotonic claims, fencing tokens and reconciliation attempts");
        }
        if (validUntil.compareTo(audit.createdAt()) <= 0) {
            throw new DomainValidationException(
                    "actionDispatch.validUntil", "must be after audit.createdAt");
        }
        if (notBefore.compareTo(audit.createdAt()) < 0) {
            throw new DomainValidationException(
                    "actionDispatch.notBefore", "must not precede audit.createdAt");
        }
        boolean running = status == ActionDispatchStatus.RUNNING
                || status == ActionDispatchStatus.RECONCILING;
        if (running != claim.isPresent()) {
            throw new DomainValidationException(
                    "actionDispatch.claim", "must exist exactly while RUNNING or RECONCILING");
        }
        claim.ifPresent(value -> {
            boolean wrongMode = (status == ActionDispatchStatus.RUNNING
                            && value.mode() != ActionClaimMode.EXECUTE)
                    || (status == ActionDispatchStatus.RECONCILING
                            && value.mode() != ActionClaimMode.RECONCILE);
            if (!value.dispatchId().equals(id)
                    || !value.actionId().equals(actionId)
                    || value.fencingToken().value() != lastFencingToken
                    || wrongMode) {
                throw new DomainValidationException(
                        "actionDispatch.claim",
                        "must retain the exact Dispatch, Action, fencing token and state mode");
            }
        });
        boolean receiptRequired = status.isTerminal();
        if (receiptRequired != receipt.isPresent()) {
            throw new DomainValidationException(
                    "actionDispatch.receipt", "must exist exactly for terminal Dispatches");
        }
        if ((status == ActionDispatchStatus.CANCELLED) != cancellationReason.isPresent()) {
            throw new DomainValidationException(
                    "actionDispatch.cancellationReason", "must exist exactly when cancelled");
        }
        if (compensationDisposition == CompensationDisposition.MANUAL_REVIEW_REQUIRED
                && status != ActionDispatchStatus.CANCELLED) {
            throw new DomainValidationException(
                    "actionDispatch.compensationDisposition",
                    "manual compensation review is reserved for cancelled dependent actions");
        }
        receipt.ifPresent(value -> {
            if (!value.actionId().equals(actionId) || !value.actionDigest().equals(actionDigest)) {
                throw new DomainValidationException(
                        "actionDispatch.receipt", "must bind the exact PlannedAction");
            }
            if (targetStatus(value.result()) != status) {
                throw new DomainValidationException(
                        "actionDispatch.receipt", "result must equal the terminal Dispatch status");
            }
        });
    }

    private static void requireBundleAction(ActionBundle bundle, PlannedAction action) {
        boolean exact = bundle.actions().stream().anyMatch(candidate -> candidate.id().equals(action.id())
                && candidate.sequence() == action.sequence()
                && candidate.digest().equals(action.digest())
                && candidate.dependencies().equals(action.dependencies()));
        if (!exact || !bundle.authority().equals(action.authority())) {
            throw new DomainValidationException(
                    "actionDispatch.actionId", "must be an exact child of the supplied Bundle");
        }
    }

    private static void requireConfirmation(Confirmation confirmation, ActionBundle bundle) {
        if (confirmation.status() != ConfirmationStatus.ACTIVE
                || !confirmation.scope().equals(bundle.authority().scope())
                || !confirmation.bundleId().equals(bundle.id())
                || !confirmation.bundleDigest().equals(bundle.digest())
                || !confirmation.actions().equals(bundle.actions().stream()
                        .map(ConfirmedActionReference::from)
                        .toList())) {
            throw new DomainValidationException(
                    "actionDispatch.confirmationId", "must authorize the exact Bundle graph");
        }
    }

    private static PrincipalId requireScopedActor(Principal actor, WorkItemScope scope) {
        Principal required = Objects.requireNonNull(actor, "actor");
        boolean wrongTeam = required.scope().teamId().isPresent()
                && required.scope().teamId().filter(scope.teamId()::equals).isEmpty();
        if (!required.canAct()
                || !required.scope().organizationId().equals(scope.organizationId())
                || wrongTeam) {
            throw new DomainValidationException(
                    "actionDispatch.createdByPrincipalId", "must be an active Principal in scope");
        }
        return required.id();
    }

    private static ActionDispatchStatus targetStatus(ActionReceiptResult result) {
        return switch (Objects.requireNonNull(result, "result")) {
            case SUCCEEDED -> ActionDispatchStatus.SUCCEEDED;
            case FAILED -> ActionDispatchStatus.FAILED;
            case MANUALLY_SUCCEEDED -> ActionDispatchStatus.MANUALLY_SUCCEEDED;
            case MANUALLY_FAILED -> ActionDispatchStatus.MANUALLY_FAILED;
            case CANCELLED -> ActionDispatchStatus.CANCELLED;
        };
    }

    private ActionDispatch copy(
            ActionDispatchStatus targetStatus,
            Optional<ActionClaim> targetClaim,
            long targetFencingToken,
            int targetClaimAttempts,
            int targetReconciliationAttempts,
            UtcTimestamp targetNotBefore,
            Optional<ActionReceiptReference> targetReceipt,
            Optional<ActionCancellationReason> targetCancellationReason,
            CompensationDisposition targetCompensationDisposition,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new ActionDispatch(
                id, scope, bundleId, bundleDigest, confirmationId, actionId, actionDigest, sequence,
                dependencies, idempotencyKey, validUntil, targetStatus, targetClaim,
                targetFencingToken, targetClaimAttempts, targetReconciliationAttempts,
                targetNotBefore, targetReceipt, targetCancellationReason,
                targetCompensationDisposition, targetVersion, targetAudit);
    }

    public ActionDispatchId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public ActionBundleId bundleId() { return bundleId; }
    public ActionBundleDigest bundleDigest() { return bundleDigest; }
    public ConfirmationId confirmationId() { return confirmationId; }
    public PlannedActionId actionId() { return actionId; }
    public ActionDigest actionDigest() { return actionDigest; }
    public int sequence() { return sequence; }
    public List<ActionDependency> dependencies() { return dependencies; }
    public ActionIdempotencyKey idempotencyKey() { return idempotencyKey; }
    public UtcTimestamp validUntil() { return validUntil; }
    public ActionDispatchStatus status() { return status; }
    public Optional<ActionClaim> claim() { return claim; }
    public long lastFencingToken() { return lastFencingToken; }
    public int claimAttempts() { return claimAttempts; }
    public int reconciliationAttempts() { return reconciliationAttempts; }
    public UtcTimestamp notBefore() { return notBefore; }
    public Optional<ActionReceiptReference> receipt() { return receipt; }
    public Optional<ActionCancellationReason> cancellationReason() { return cancellationReason; }
    public CompensationDisposition compensationDisposition() { return compensationDisposition; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
