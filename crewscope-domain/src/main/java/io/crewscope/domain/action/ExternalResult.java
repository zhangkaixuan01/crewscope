package io.crewscope.domain.action;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Current normalized external object state merged from Webhook, query and write response facts. */
public final class ExternalResult {

    private final ExternalResultId id;
    private final WorkItemScope scope;
    private final ActionBundleId bundleId;
    private final ActionBundleDigest bundleDigest;
    private final PlannedActionId actionId;
    private final ActionDigest actionDigest;
    private final ExternalResultIdentity identity;
    private final ExternalObjectStatus status;
    private final Optional<Long> providerVersion;
    private final Optional<UtcTimestamp> providerUpdatedAt;
    private final ExternalResultSource lastSource;
    private final ExternalObservationKey lastObservationKey;
    private final ActionEvidenceReference lastEvidence;
    private final UtcTimestamp observedAt;
    private final long version;
    private final AuditMetadata audit;

    private ExternalResult(
            ExternalResultId id,
            WorkItemScope scope,
            ActionBundleId bundleId,
            ActionBundleDigest bundleDigest,
            PlannedActionId actionId,
            ActionDigest actionDigest,
            ExternalResultIdentity identity,
            ExternalObjectStatus status,
            Optional<Long> providerVersion,
            Optional<UtcTimestamp> providerUpdatedAt,
            ExternalResultSource lastSource,
            ExternalObservationKey lastObservationKey,
            ActionEvidenceReference lastEvidence,
            UtcTimestamp observedAt,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
        this.bundleDigest = Objects.requireNonNull(bundleDigest, "bundleDigest");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.actionDigest = Objects.requireNonNull(actionDigest, "actionDigest");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.status = Objects.requireNonNull(status, "status");
        if (!this.status.supports(this.identity.objectType())) {
            throw new DomainValidationException(
                    "externalResult.status", "is incompatible with the external object type");
        }
        this.providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        this.providerVersion.ifPresent(value -> {
            if (value < 1) {
                throw new DomainValidationException(
                        "externalResult.providerVersion", "must be positive");
            }
        });
        this.providerUpdatedAt = Objects.requireNonNull(providerUpdatedAt, "providerUpdatedAt");
        if (this.providerVersion.isEmpty() && this.providerUpdatedAt.isEmpty()) {
            throw new DomainValidationException(
                    "externalResult.providerUpdatedAt",
                    "is required when the Provider has no monotonic version");
        }
        this.lastSource = Objects.requireNonNull(lastSource, "lastSource");
        this.lastObservationKey = Objects.requireNonNull(
                lastObservationKey, "lastObservationKey");
        this.lastEvidence = Objects.requireNonNull(lastEvidence, "lastEvidence");
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
        if (this.providerUpdatedAt.isPresent()
                && this.providerUpdatedAt.orElseThrow().compareTo(this.observedAt) > 0) {
            throw new DomainValidationException(
                    "externalResult.providerUpdatedAt", "must not be after observedAt");
        }
        if (version < 0) {
            throw new DomainValidationException("externalResult.version", "must not be negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public static ExternalResult observeFirst(
            ExternalResultId id,
            ActionDispatch dispatch,
            PlannedAction action,
            ExternalObservation observation,
            Principal actor) {
        ActionDispatch requiredDispatch = Objects.requireNonNull(dispatch, "dispatch");
        return observeFirstFromTrustedSource(
                id,
                requiredDispatch,
                action,
                observation,
                requireScopedActor(actor, requiredDispatch.scope()));
    }

    /** Creates a Provider fact attributed by a trusted service to an existing scoped Principal. */
    public static ExternalResult observeFirstFromTrustedSource(
            ExternalResultId id,
            ActionDispatch dispatch,
            PlannedAction action,
            ExternalObservation observation,
            PrincipalId actorId) {
        ActionDispatch requiredDispatch = Objects.requireNonNull(dispatch, "dispatch");
        PlannedAction requiredAction = Objects.requireNonNull(action, "action");
        ActionReceipt.requireAction(requiredDispatch, requiredAction);
        ExternalObservation requiredObservation = requireObservation(
                requiredDispatch, observation);
        ActionReceipt.requireExternalShape(
                requiredAction, Optional.of(requiredObservation.identity()));
        PrincipalId requiredActorId = Objects.requireNonNull(actorId, "actorId");
        return new ExternalResult(
                id,
                requiredDispatch.scope(),
                requiredDispatch.bundleId(),
                requiredDispatch.bundleDigest(),
                requiredDispatch.actionId(),
                requiredDispatch.actionDigest(),
                requiredObservation.identity(),
                requiredObservation.status(),
                requiredObservation.providerVersion(),
                requiredObservation.providerUpdatedAt(),
                requiredObservation.source(),
                requiredObservation.observationKey(),
                requiredObservation.evidence(),
                requiredObservation.observedAt(),
                0,
                AuditMetadata.createdBy(requiredActorId, requiredObservation.observedAt()));
    }

    public static ExternalResult reconstitute(
            ExternalResultId id,
            WorkItemScope scope,
            ActionBundleId bundleId,
            ActionBundleDigest bundleDigest,
            PlannedActionId actionId,
            ActionDigest actionDigest,
            ExternalResultIdentity identity,
            ExternalObjectStatus status,
            Optional<Long> providerVersion,
            Optional<UtcTimestamp> providerUpdatedAt,
            ExternalResultSource lastSource,
            ExternalObservationKey lastObservationKey,
            ActionEvidenceReference lastEvidence,
            UtcTimestamp observedAt,
            long version,
            AuditMetadata audit) {
        return new ExternalResult(
                id, scope, bundleId, bundleDigest, actionId, actionDigest, identity, status,
                providerVersion, providerUpdatedAt, lastSource, lastObservationKey, lastEvidence,
                observedAt, version, audit);
    }

    /** Applies one version-first monotonic merge and never lets late facts rewrite manual finality. */
    public ExternalMergeResult merge(
            long expectedVersion,
            ExternalObservation observation,
            Optional<ActionReceiptReference> terminalReceipt,
            Principal actor) {
        return mergeFromTrustedSource(
                expectedVersion,
                observation,
                terminalReceipt,
                requireScopedActor(actor, scope));
    }

    /** Merges a trusted Provider observation while retaining durable Principal provenance. */
    public ExternalMergeResult mergeFromTrustedSource(
            long expectedVersion,
            ExternalObservation observation,
            Optional<ActionReceiptReference> terminalReceipt,
            PrincipalId actorId) {
        requireVersion(expectedVersion);
        ExternalObservation candidate = requireObservation(this, observation);
        Optional<ActionReceiptReference> requiredReceipt = Objects.requireNonNull(
                terminalReceipt, "terminalReceipt");
        requiredReceipt.ifPresent(receipt -> {
            if (!receipt.actionId().equals(actionId) || !receipt.actionDigest().equals(actionDigest)) {
                throw new DomainValidationException(
                        "externalResult.receipt", "must belong to the exact action");
            }
        });
        if (requiredReceipt.filter(receipt -> receipt.result().isManual()).isPresent()) {
            return new ExternalMergeResult(this, ExternalMergeOutcome.MANUAL_TERMINAL_CONFLICT);
        }
        ExternalMergeOutcome decision = decide(candidate);
        if (decision != ExternalMergeOutcome.APPLIED) {
            return new ExternalMergeResult(this, decision);
        }
        PrincipalId requiredActorId = Objects.requireNonNull(actorId, "actorId");
        ExternalResult changed = new ExternalResult(
                id, scope, bundleId, bundleDigest, actionId, actionDigest, identity,
                candidate.status(), candidate.providerVersion(), candidate.providerUpdatedAt(),
                candidate.source(), candidate.observationKey(), candidate.evidence(),
                candidate.observedAt(), version + 1,
                audit.modifiedBy(requiredActorId, candidate.observedAt()));
        return new ExternalMergeResult(changed, ExternalMergeOutcome.APPLIED);
    }

    private ExternalMergeOutcome decide(ExternalObservation candidate) {
        if (lastObservationKey.equals(candidate.observationKey())) {
            return sameFact(candidate)
                    ? ExternalMergeOutcome.DUPLICATE
                    : ExternalMergeOutcome.CONFLICT;
        }
        if (providerVersion.isPresent()) {
            if (candidate.providerVersion().isEmpty()) {
                return ExternalMergeOutcome.STALE;
            }
            int comparison = Long.compare(
                    candidate.providerVersion().orElseThrow(), providerVersion.orElseThrow());
            if (comparison < 0) {
                return ExternalMergeOutcome.STALE;
            }
            if (comparison == 0) {
                return sameFact(candidate)
                        ? ExternalMergeOutcome.DUPLICATE
                        : ExternalMergeOutcome.CONFLICT;
            }
        } else if (candidate.providerVersion().isEmpty()) {
            int comparison = candidate.providerUpdatedAt().orElseThrow()
                    .compareTo(providerUpdatedAt.orElseThrow());
            if (comparison < 0) {
                return ExternalMergeOutcome.STALE;
            }
            if (comparison == 0) {
                return sameFact(candidate)
                        ? ExternalMergeOutcome.DUPLICATE
                        : ExternalMergeOutcome.CONFLICT;
            }
        }
        return status.canTransitionTo(candidate.status())
                ? ExternalMergeOutcome.APPLIED
                : ExternalMergeOutcome.CONFLICT;
    }

    private boolean sameFact(ExternalObservation candidate) {
        if (status != candidate.status()
                || !providerVersion.equals(candidate.providerVersion())) {
            return false;
        }
        return providerVersion.isPresent()
                || providerUpdatedAt.equals(candidate.providerUpdatedAt());
    }

    private static ExternalObservation requireObservation(
            ActionDispatch dispatch, ExternalObservation observation) {
        ExternalObservation required = Objects.requireNonNull(observation, "observation");
        if (!dispatch.actionId().equals(required.actionId())
                || !dispatch.actionDigest().equals(required.actionDigest())) {
            throw new DomainValidationException(
                    "externalObservation.actionId", "must bind the exact dispatched action");
        }
        return required;
    }

    private static ExternalObservation requireObservation(
            ExternalResult current, ExternalObservation observation) {
        ExternalObservation required = Objects.requireNonNull(observation, "observation");
        if (!current.actionId.equals(required.actionId())
                || !current.actionDigest.equals(required.actionDigest())
                || !current.identity.equals(required.identity())) {
            throw new DomainValidationException(
                    "externalObservation.identity", "must bind the exact existing external object");
        }
        return required;
    }

    private static PrincipalId requireScopedActor(Principal actor, WorkItemScope scope) {
        Principal required = Objects.requireNonNull(actor, "actor");
        boolean wrongTeam = required.scope().teamId().isPresent()
                && required.scope().teamId().filter(scope.teamId()::equals).isEmpty();
        if (!required.canAct()
                || !required.scope().organizationId().equals(scope.organizationId())
                || wrongTeam) {
            throw new DomainValidationException(
                    "externalResult.actor", "must be an active Principal in scope");
        }
        return required.id();
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new OptimisticLockConflictException(
                    "ExternalResult", id, expectedVersion, version);
        }
    }

    public ExternalResultId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public ActionBundleId bundleId() { return bundleId; }
    public ActionBundleDigest bundleDigest() { return bundleDigest; }
    public PlannedActionId actionId() { return actionId; }
    public ActionDigest actionDigest() { return actionDigest; }
    public ExternalResultIdentity identity() { return identity; }
    public ExternalObjectStatus status() { return status; }
    public Optional<Long> providerVersion() { return providerVersion; }
    public Optional<UtcTimestamp> providerUpdatedAt() { return providerUpdatedAt; }
    public ExternalResultSource lastSource() { return lastSource; }
    public ExternalObservationKey lastObservationKey() { return lastObservationKey; }
    public ActionEvidenceReference lastEvidence() { return lastEvidence; }
    public UtcTimestamp observedAt() { return observedAt; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
