package io.crewscope.domain.action;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Human authorization for exactly one Bundle digest and all ordered child action digests. */
public final class Confirmation {

    private final ConfirmationId id;
    private final WorkItemScope scope;
    private final ActionBundleId bundleId;
    private final ActionBundleDigest bundleDigest;
    private final List<ConfirmedActionReference> actions;
    private final PrincipalId confirmedByPrincipalId;
    private final UtcTimestamp confirmedAt;
    private final UtcTimestamp validUntil;
    private final ConfirmationStatus status;
    private final Optional<ActionCancellationReason> cancellationReason;
    private final long version;
    private final AuditMetadata audit;

    private Confirmation(
            ConfirmationId id,
            WorkItemScope scope,
            ActionBundleId bundleId,
            ActionBundleDigest bundleDigest,
            List<ConfirmedActionReference> actions,
            PrincipalId confirmedByPrincipalId,
            UtcTimestamp confirmedAt,
            UtcTimestamp validUntil,
            ConfirmationStatus status,
            Optional<ActionCancellationReason> cancellationReason,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
        this.bundleDigest = Objects.requireNonNull(bundleDigest, "bundleDigest");
        this.actions = requireActions(actions);
        this.confirmedByPrincipalId = Objects.requireNonNull(
                confirmedByPrincipalId, "confirmedByPrincipalId");
        this.confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        if (this.validUntil.compareTo(this.confirmedAt) <= 0) {
            throw new DomainValidationException(
                    "confirmation.validUntil", "must be after confirmation time");
        }
        this.status = Objects.requireNonNull(status, "status");
        this.cancellationReason = Objects.requireNonNull(cancellationReason, "cancellationReason");
        if ((this.status == ConfirmationStatus.CANCELLED) != this.cancellationReason.isPresent()) {
            throw new DomainValidationException(
                    "confirmation.cancellationReason", "must exist exactly for cancelled confirmation");
        }
        if (version < 0) {
            throw new DomainValidationException("confirmation.version", "must not be negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
        if (!this.audit.createdAt().equals(this.confirmedAt)) {
            throw new DomainValidationException(
                    "confirmation.audit.createdAt", "must equal confirmedAt");
        }
        if (this.audit.createdBy().filter(this.confirmedByPrincipalId::equals).isEmpty()) {
            throw new DomainValidationException(
                    "confirmation.audit.createdBy", "must identify the confirming OWNER");
        }
    }

    /** Confirms only a current Bundle and only by its current accountable human OWNER. */
    public static Confirmation confirm(
            ConfirmationId id,
            ActionBundle bundle,
            ActionAuthorityFacts currentFacts,
            Principal actor,
            UtcTimestamp confirmedAt) {
        ActionBundle requiredBundle = Objects.requireNonNull(bundle, "bundle");
        UtcTimestamp requiredTime = Objects.requireNonNull(confirmedAt, "confirmedAt");
        requiredBundle.requireCurrent(currentFacts, requiredTime);
        PrincipalId actorId = requireOwner(actor, requiredBundle.authority());
        return new Confirmation(
                id,
                requiredBundle.authority().scope(),
                requiredBundle.id(),
                requiredBundle.digest(),
                requiredBundle.actions().stream().map(ConfirmedActionReference::from).toList(),
                actorId,
                requiredTime,
                requiredBundle.validUntil(),
                ConfirmationStatus.ACTIVE,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, requiredTime));
    }

    public static Confirmation reconstitute(
            ConfirmationId id,
            WorkItemScope scope,
            ActionBundleId bundleId,
            ActionBundleDigest bundleDigest,
            List<ConfirmedActionReference> actions,
            PrincipalId confirmedByPrincipalId,
            UtcTimestamp confirmedAt,
            UtcTimestamp validUntil,
            ConfirmationStatus status,
            Optional<ActionCancellationReason> cancellationReason,
            long version,
            AuditMetadata audit) {
        return new Confirmation(
                id, scope, bundleId, bundleDigest, actions, confirmedByPrincipalId, confirmedAt,
                validUntil, status, cancellationReason, version, audit);
    }

    /** Revalidates time, digest and every current server-owned authority fact. */
    public void requireAuthorizes(
            ActionBundle bundle, ActionAuthorityFacts currentFacts, UtcTimestamp now) {
        ActionBundle requiredBundle = Objects.requireNonNull(bundle, "bundle");
        UtcTimestamp requiredNow = Objects.requireNonNull(now, "now");
        if (status != ConfirmationStatus.ACTIVE
                || requiredNow.compareTo(confirmedAt) < 0
                || requiredNow.compareTo(validUntil) >= 0
                || !scope.equals(requiredBundle.authority().scope())
                || !confirmedByPrincipalId.equals(
                        requiredBundle.authority().responsibility().actorPrincipalId())
                || !bundleId.equals(requiredBundle.id())
                || !bundleDigest.equals(requiredBundle.digest())
                || !actions.equals(requiredBundle.actions().stream()
                        .map(ConfirmedActionReference::from)
                        .toList())) {
            throw new DomainValidationException(
                    "confirmation", "does not authorize the exact current ActionBundle");
        }
        requiredBundle.requireCurrent(currentFacts, requiredNow);
    }

    /** Cancels unused authority; running Dispatches must independently enter UNKNOWN if uncertain. */
    public Confirmation cancel(
            long expectedVersion,
            ActionCancellationReason reason,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        if (status != ConfirmationStatus.ACTIVE) {
            throw new InvalidStateTransitionException(
                    "Confirmation", id, status, ConfirmationStatus.CANCELLED);
        }
        PrincipalId actorId = requireScopedActor(actor, scope, confirmedByPrincipalId);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        if (requiredTime.compareTo(confirmedAt) < 0) {
            throw new DomainValidationException(
                    "confirmation.cancelledAt", "must not precede confirmation");
        }
        return new Confirmation(
                id, scope, bundleId, bundleDigest, actions, confirmedByPrincipalId, confirmedAt,
                validUntil, ConfirmationStatus.CANCELLED,
                Optional.of(Objects.requireNonNull(reason, "reason")), version + 1,
                audit.modifiedBy(actorId, requiredTime));
    }

    private static List<ConfirmedActionReference> requireActions(
            List<ConfirmedActionReference> values) {
        List<ConfirmedActionReference> required = List.copyOf(
                Objects.requireNonNull(values, "actions"));
        if (required.isEmpty()) {
            throw new DomainValidationException("confirmation.actions", "must not be empty");
        }
        Set<PlannedActionId> ids = new HashSet<>();
        for (int index = 0; index < required.size(); index++) {
            ConfirmedActionReference action = Objects.requireNonNull(
                    required.get(index), "confirmedAction");
            if (action.sequence() != index + 1 || !ids.add(action.actionId())) {
                throw new DomainValidationException(
                        "confirmation.actions", "must preserve unique continuous Bundle order");
            }
        }
        return required;
    }

    private static PrincipalId requireOwner(Principal actor, ActionAuthoritySnapshot authority) {
        Principal required = Objects.requireNonNull(actor, "actor");
        PrincipalId expectedOwner = authority.responsibility().actorPrincipalId();
        if (required.type() != PrincipalType.USER || !required.id().equals(expectedOwner)) {
            throw new DomainValidationException(
                    "confirmation.confirmedByPrincipalId",
                    "must be the current human OWNER responsible for this WorkItem");
        }
        return requireScopedActor(required, authority.scope(), expectedOwner);
    }

    private static PrincipalId requireScopedActor(
            Principal actor, WorkItemScope scope, PrincipalId requiredActorId) {
        Principal required = Objects.requireNonNull(actor, "actor");
        boolean wrongTeam = required.scope().teamId().isPresent()
                && required.scope().teamId().filter(scope.teamId()::equals).isEmpty();
        if (!required.canAct()
                || !required.id().equals(requiredActorId)
                || !required.scope().organizationId().equals(scope.organizationId())
                || wrongTeam) {
            throw new DomainValidationException(
                    "confirmation.actor", "must be the active confirming OWNER in scope");
        }
        return required.id();
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new OptimisticLockConflictException(
                    "Confirmation", id, expectedVersion, version);
        }
    }

    public ConfirmationId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public ActionBundleId bundleId() { return bundleId; }
    public ActionBundleDigest bundleDigest() { return bundleDigest; }
    public List<ConfirmedActionReference> actions() { return actions; }
    public PrincipalId confirmedByPrincipalId() { return confirmedByPrincipalId; }
    public UtcTimestamp confirmedAt() { return confirmedAt; }
    public UtcTimestamp validUntil() { return validUntil; }
    public ConfirmationStatus status() { return status; }
    public Optional<ActionCancellationReason> cancellationReason() { return cancellationReason; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
