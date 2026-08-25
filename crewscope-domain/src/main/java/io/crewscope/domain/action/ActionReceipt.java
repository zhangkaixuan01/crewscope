package io.crewscope.domain.action;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Immutable sole logical conclusion for an external action side effect. */
public final class ActionReceipt {

    public static final int MAX_TARGET_VERSION_LENGTH = 500;

    private final ActionReceiptId id;
    private final WorkItemScope scope;
    private final ActionBundleId bundleId;
    private final ActionBundleDigest bundleDigest;
    private final PlannedActionId actionId;
    private final ActionDigest actionDigest;
    private final ActionIdempotencyKey idempotencyKey;
    private final ActionReceiptResult result;
    private final ActionResultSource source;
    private final Optional<ActionClaim> claim;
    private final Optional<ExternalResultIdentity> externalIdentity;
    private final Optional<String> targetVersion;
    private final ActionEvidenceReference evidence;
    private final Optional<PrincipalId> resolvedByPrincipalId;
    private final Optional<ManualResolutionReason> manualReason;
    private final UtcTimestamp receivedAt;

    private ActionReceipt(
            ActionReceiptId id,
            WorkItemScope scope,
            ActionBundleId bundleId,
            ActionBundleDigest bundleDigest,
            PlannedActionId actionId,
            ActionDigest actionDigest,
            ActionIdempotencyKey idempotencyKey,
            ActionReceiptResult result,
            ActionResultSource source,
            Optional<ActionClaim> claim,
            Optional<ExternalResultIdentity> externalIdentity,
            Optional<String> targetVersion,
            ActionEvidenceReference evidence,
            Optional<PrincipalId> resolvedByPrincipalId,
            Optional<ManualResolutionReason> manualReason,
            UtcTimestamp receivedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
        this.bundleDigest = Objects.requireNonNull(bundleDigest, "bundleDigest");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.actionDigest = Objects.requireNonNull(actionDigest, "actionDigest");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        ActionIdempotencyKey expectedIdempotencyKey = ActionIdempotencyKey.derive(
                this.scope.organizationId(), this.bundleId, this.actionId, this.actionDigest);
        if (!this.idempotencyKey.equals(expectedIdempotencyKey)) {
            throw new DomainValidationException(
                    "actionReceipt.idempotencyKey",
                    "must be derived from the exact organization, Bundle and Action digest");
        }
        this.result = Objects.requireNonNull(result, "result");
        this.source = Objects.requireNonNull(source, "source");
        this.claim = Objects.requireNonNull(claim, "claim");
        this.externalIdentity = Objects.requireNonNull(externalIdentity, "externalIdentity");
        this.targetVersion = Objects.requireNonNull(targetVersion, "targetVersion")
                .map(ActionReceipt::requireTargetVersion);
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.resolvedByPrincipalId = Objects.requireNonNull(
                resolvedByPrincipalId, "resolvedByPrincipalId");
        this.manualReason = Objects.requireNonNull(manualReason, "manualReason");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        validateShape();
    }

    /** Creates a result under the exact current fenced Worker claim. */
    public static ActionReceipt fromClaim(
            ActionReceiptId id,
            ActionDispatch dispatch,
            PlannedAction action,
            ActionClaim claim,
            ActionReceiptResult result,
            ActionResultSource source,
            Optional<ExternalResultIdentity> externalIdentity,
            Optional<String> targetVersion,
            ActionEvidenceReference evidence,
            UtcTimestamp receivedAt) {
        ActionDispatch requiredDispatch = Objects.requireNonNull(dispatch, "dispatch");
        ActionClaim requiredClaim = Objects.requireNonNull(claim, "claim");
        UtcTimestamp requiredReceivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        if (result.isManual() || result == ActionReceiptResult.CANCELLED
                || (source != ActionResultSource.WRITE_RESPONSE
                        && source != ActionResultSource.ACTIVE_QUERY)) {
            throw new DomainValidationException(
                    "actionReceipt.source", "claimed Receipt must be an automatic Worker result");
        }
        if (requiredDispatch.claim().filter(requiredClaim::equals).isEmpty()
                || !requiredClaim.isActiveAt(requiredReceivedAt)
                || (source == ActionResultSource.WRITE_RESPONSE
                        && requiredClaim.mode() != ActionClaimMode.EXECUTE)) {
            throw new DomainValidationException(
                    "actionReceipt.claim",
                    "must use the current active claim and a write response requires EXECUTE mode");
        }
        requireAction(requiredDispatch, action);
        requireExternalShape(action, externalIdentity);
        return create(
                id, requiredDispatch, result, source, Optional.of(requiredClaim),
                externalIdentity, targetVersion, evidence, Optional.empty(), Optional.empty(),
                requiredReceivedAt);
    }

    /** Creates a trusted Webhook or active-query result without borrowing a Worker claim. */
    public static ActionReceipt fromObservation(
            ActionReceiptId id,
            ActionDispatch dispatch,
            PlannedAction action,
            ActionReceiptResult result,
            ActionResultSource source,
            Optional<ExternalResultIdentity> externalIdentity,
            Optional<String> targetVersion,
            ActionEvidenceReference evidence,
            UtcTimestamp receivedAt) {
        if (result.isManual() || result == ActionReceiptResult.CANCELLED
                || (source != ActionResultSource.WEBHOOK
                        && source != ActionResultSource.ACTIVE_QUERY)) {
            throw new DomainValidationException(
                    "actionReceipt.source", "observation Receipt requires Webhook or active query");
        }
        requireAction(dispatch, action);
        requireExternalShape(action, externalIdentity);
        return create(
                id, dispatch, result, source, Optional.empty(), externalIdentity, targetVersion,
                evidence, Optional.empty(), Optional.empty(), receivedAt);
    }

    /** Creates an irreversible human conclusion with explicit evidence and stable reason. */
    public static ActionReceipt manual(
            ActionReceiptId id,
            ActionDispatch dispatch,
            PlannedAction action,
            ActionReceiptResult result,
            Optional<ExternalResultIdentity> externalIdentity,
            Optional<String> targetVersion,
            ActionEvidenceReference evidence,
            ManualResolutionReason reason,
            Principal actor,
            UtcTimestamp receivedAt) {
        if (!result.isManual()) {
            throw new DomainValidationException(
                    "actionReceipt.result", "must be a manual terminal result");
        }
        requireAction(dispatch, action);
        requireExternalShape(action, externalIdentity);
        Principal requiredActor = Objects.requireNonNull(actor, "actor");
        if (requiredActor.type() != PrincipalType.USER) {
            throw new DomainValidationException(
                    "actionReceipt.resolvedByPrincipalId",
                    "manual resolution requires a human USER Principal");
        }
        ManualResolutionReason requiredReason = Objects.requireNonNull(reason, "reason");
        ActionEvidenceReference requiredEvidence = Objects.requireNonNull(evidence, "evidence");
        if (!requiredEvidence.code().equals(requiredReason.name())) {
            throw new DomainValidationException(
                    "actionReceipt.evidence",
                    "manual evidence code must equal the stable manual resolution reason");
        }
        PrincipalId actorId = requireScopedActor(requiredActor, dispatch.scope());
        return create(
                id, dispatch, result, ActionResultSource.MANUAL, Optional.empty(),
                externalIdentity, targetVersion, requiredEvidence, Optional.of(actorId),
                Optional.of(requiredReason), receivedAt);
    }

    /** Proves a READY action was cancelled before any external write started. */
    public static ActionReceipt cancelled(
            ActionReceiptId id,
            ActionDispatch dispatch,
            PlannedAction action,
            ActionEvidenceReference noSideEffectEvidence,
            Principal actor,
            UtcTimestamp receivedAt) {
        requireAction(dispatch, action);
        if (!noSideEffectEvidence.code().startsWith("NO_SIDE_EFFECT_")) {
            throw new DomainValidationException(
                    "actionReceipt.evidence", "must prove cancellation occurred before a side effect");
        }
        PrincipalId actorId = requireScopedActor(actor, dispatch.scope());
        return create(
                id, dispatch, ActionReceiptResult.CANCELLED, ActionResultSource.CONTROL,
                Optional.empty(), Optional.empty(), Optional.empty(), noSideEffectEvidence,
                Optional.of(actorId), Optional.empty(), receivedAt);
    }

    public static ActionReceipt reconstitute(
            ActionReceiptId id,
            WorkItemScope scope,
            ActionBundleId bundleId,
            ActionBundleDigest bundleDigest,
            PlannedActionId actionId,
            ActionDigest actionDigest,
            ActionIdempotencyKey idempotencyKey,
            ActionReceiptResult result,
            ActionResultSource source,
            Optional<ActionClaim> claim,
            Optional<ExternalResultIdentity> externalIdentity,
            Optional<String> targetVersion,
            ActionEvidenceReference evidence,
            Optional<PrincipalId> resolvedByPrincipalId,
            Optional<ManualResolutionReason> manualReason,
            UtcTimestamp receivedAt) {
        return new ActionReceipt(
                id, scope, bundleId, bundleDigest, actionId, actionDigest, idempotencyKey, result,
                source, claim, externalIdentity, targetVersion, evidence, resolvedByPrincipalId,
                manualReason, receivedAt);
    }

    private static ActionReceipt create(
            ActionReceiptId id,
            ActionDispatch dispatch,
            ActionReceiptResult result,
            ActionResultSource source,
            Optional<ActionClaim> claim,
            Optional<ExternalResultIdentity> externalIdentity,
            Optional<String> targetVersion,
            ActionEvidenceReference evidence,
            Optional<PrincipalId> actorId,
            Optional<ManualResolutionReason> manualReason,
            UtcTimestamp receivedAt) {
        ActionDispatch required = Objects.requireNonNull(dispatch, "dispatch");
        return new ActionReceipt(
                id, required.scope(), required.bundleId(), required.bundleDigest(),
                required.actionId(), required.actionDigest(), required.idempotencyKey(), result,
                source, claim, externalIdentity, targetVersion, evidence, actorId, manualReason,
                receivedAt);
    }

    private void validateShape() {
        if (externalIdentity.isPresent() != targetVersion.isPresent()
                || (result.isSuccessful() && externalIdentity.isEmpty())) {
            throw new DomainValidationException(
                    "actionReceipt.externalIdentity",
                    "identity and target version must be paired and are required for success");
        }
        boolean validManualShape = source == ActionResultSource.MANUAL
                && resolvedByPrincipalId.isPresent()
                && manualReason.isPresent();
        if (result.isManual() != validManualShape
                || (!result.isManual()
                        && (source == ActionResultSource.MANUAL || manualReason.isPresent()))) {
            throw new DomainValidationException(
                    "actionReceipt.manualReason",
                    "must exist exactly for an irreversible manual result");
        }
        if (result == ActionReceiptResult.CANCELLED) {
            if (source != ActionResultSource.CONTROL
                    || claim.isPresent()
                    || externalIdentity.isPresent()
                    || targetVersion.isPresent()
                    || resolvedByPrincipalId.isEmpty()
                    || manualReason.isPresent()) {
                throw new DomainValidationException(
                        "actionReceipt", "has an invalid cancellation shape");
            }
        } else if (!result.isManual() && resolvedByPrincipalId.isPresent()) {
            throw new DomainValidationException(
                    "actionReceipt.resolvedByPrincipalId", "is reserved for human terminal results");
        }
        claim.ifPresent(value -> {
            if (!value.actionId().equals(actionId)
                    || receivedAt.compareTo(value.acquiredAt()) < 0) {
                throw new DomainValidationException(
                        "actionReceipt.claim",
                        "must belong to the exact action and precede receipt time");
            }
        });
    }

    static void requireAction(ActionDispatch dispatch, PlannedAction action) {
        ActionDispatch requiredDispatch = Objects.requireNonNull(dispatch, "dispatch");
        PlannedAction requiredAction = Objects.requireNonNull(action, "action");
        if (!requiredDispatch.actionId().equals(requiredAction.id())
                || !requiredDispatch.actionDigest().equals(requiredAction.digest())) {
            throw new DomainValidationException(
                    "actionReceipt.actionId", "must bind the exact dispatched action digest");
        }
    }

    static void requireExternalShape(
            PlannedAction action, Optional<ExternalResultIdentity> identity) {
        if (action.kind() == ActionKind.NOTIFY_COLLABORATION) {
            throw new DomainValidationException(
                    "actionReceipt.actionKind",
                    "NOTIFY_COLLABORATION is owned by the notification delivery contract");
        }
        Optional<ExternalResultIdentity> required = Objects.requireNonNull(identity, "externalIdentity");
        required.ifPresent(value -> {
            ExternalObjectType expectedType = switch (action.kind()) {
                case PUSH_BRANCH -> ExternalObjectType.BRANCH;
                case CREATE_DRAFT_PR -> ExternalObjectType.PULL_REQUEST;
                case NOTIFY_COLLABORATION -> throw new DomainValidationException(
                        "actionReceipt.actionKind",
                        "NOTIFY_COLLABORATION is owned by the notification delivery contract");
            };
            ConnectionId expectedConnection;
            if (action.parameters() instanceof PushBranchActionParameters push) {
                expectedConnection = push.connectionId();
            } else if (action.parameters()
                    instanceof CreateDraftPullRequestActionParameters pullRequest) {
                expectedConnection = pullRequest.connectionId();
            } else {
                throw new DomainValidationException(
                        "actionReceipt.actionKind", "is not supported by the M5 delivery contract");
            }
            if (value.objectType() != expectedType
                    || !value.connectionId().equals(expectedConnection)) {
                throw new DomainValidationException(
                        "actionReceipt.externalIdentity",
                        "must use the action kind and pinned Connection");
            }
        });
    }

    private static PrincipalId requireScopedActor(Principal actor, WorkItemScope scope) {
        Principal required = Objects.requireNonNull(actor, "actor");
        boolean wrongTeam = required.scope().teamId().isPresent()
                && required.scope().teamId().filter(scope.teamId()::equals).isEmpty();
        if (!required.canAct()
                || !required.scope().organizationId().equals(scope.organizationId())
                || wrongTeam) {
            throw new DomainValidationException(
                    "actionReceipt.resolvedByPrincipalId", "must be an active Principal in scope");
        }
        return required.id();
    }

    private static String requireTargetVersion(String value) {
        if (value == null || value.isBlank() || value.strip().length() > MAX_TARGET_VERSION_LENGTH) {
            throw new DomainValidationException(
                    "actionReceipt.targetVersion", "must be non-blank and within the size limit");
        }
        return value.strip();
    }

    public ActionReceiptReference reference() {
        return new ActionReceiptReference(id, actionId, actionDigest, result);
    }

    public ActionReceiptId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public ActionBundleId bundleId() { return bundleId; }
    public ActionBundleDigest bundleDigest() { return bundleDigest; }
    public PlannedActionId actionId() { return actionId; }
    public ActionDigest actionDigest() { return actionDigest; }
    public ActionIdempotencyKey idempotencyKey() { return idempotencyKey; }
    public ActionReceiptResult result() { return result; }
    public ActionResultSource source() { return source; }
    public Optional<ActionClaim> claim() { return claim; }
    public Optional<ExternalResultIdentity> externalIdentity() { return externalIdentity; }
    public Optional<String> targetVersion() { return targetVersion; }
    public ActionEvidenceReference evidence() { return evidence; }
    public Optional<PrincipalId> resolvedByPrincipalId() { return resolvedByPrincipalId; }
    public Optional<ManualResolutionReason> manualReason() { return manualReason; }
    public UtcTimestamp receivedAt() { return receivedAt; }
}
