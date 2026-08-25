package io.crewscope.domain.action;

import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable ordered graph reviewed and confirmed as one source-delivery unit. */
public final class ActionBundle {

    public static final Duration MAX_VALIDITY = Duration.ofMinutes(15);

    private final ActionBundleId id;
    private final ActionAuthoritySnapshot authority;
    private final List<PlannedAction> actions;
    private final UtcTimestamp validUntil;
    private final ActionBundleDigest digest;
    private final long version;
    private final AuditMetadata audit;

    private ActionBundle(
            ActionBundleId id,
            ActionAuthoritySnapshot authority,
            List<PlannedAction> actions,
            UtcTimestamp validUntil,
            Optional<ActionBundleDigest> expectedDigest,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.actions = requireGraph(actions, this.authority);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.validUntil = requireValidity(this.audit.createdAt(), validUntil);
        if (this.actions.stream().anyMatch(action -> !this.validUntil.equals(action.validUntil()))) {
            throw new DomainValidationException(
                    "actionBundle.validUntil", "must equal every child action validity boundary");
        }
        if (version < 0) {
            throw new DomainValidationException("actionBundle.version", "must not be negative");
        }
        this.version = version;
        this.digest = calculateDigest();
        Objects.requireNonNull(expectedDigest, "expectedDigest").ifPresent(expected -> {
            if (!expected.equals(this.digest)) {
                throw new DomainValidationException(
                        "actionBundle.digest", "must match the ordered action graph");
            }
        });
    }

    /** Plans the fixed M5 Push -> Draft PR delivery graph from current server authority facts. */
    public static ActionBundle planSourceCodeDelivery(
            ActionBundleId bundleId,
            PlannedActionId pushActionId,
            PlannedActionId pullRequestActionId,
            ActionAuthorityFacts facts,
            ExternalRepositoryId repositoryId,
            RepositoryBranchReference deliveryBranch,
            Optional<io.crewscope.domain.coding.RepositoryCommitId> expectedRemoteHead,
            String title,
            String body,
            UtcTimestamp validUntil,
            Principal actor,
            UtcTimestamp createdAt) {
        ExternalRepositoryId requiredRepositoryId = Objects.requireNonNull(
                repositoryId, "repositoryId");
        return planSourceCodeDelivery(
                bundleId,
                pushActionId,
                pullRequestActionId,
                facts,
                requiredRepositoryId,
                "repository:" + requiredRepositoryId.value(),
                deliveryBranch,
                expectedRemoteHead,
                title,
                body,
                validUntil,
                actor,
                createdAt);
    }

    /** Plans delivery after an application resolver binds the external ID to an exact Grant key. */
    public static ActionBundle planSourceCodeDelivery(
            ActionBundleId bundleId,
            PlannedActionId pushActionId,
            PlannedActionId pullRequestActionId,
            ActionAuthorityFacts facts,
            ExternalRepositoryId repositoryId,
            String authorizedResourceKey,
            RepositoryBranchReference deliveryBranch,
            Optional<io.crewscope.domain.coding.RepositoryCommitId> expectedRemoteHead,
            String title,
            String body,
            UtcTimestamp validUntil,
            Principal actor,
            UtcTimestamp createdAt) {
        UtcTimestamp requiredCreatedAt = Objects.requireNonNull(createdAt, "createdAt");
        UtcTimestamp requiredValidUntil = requireValidity(requiredCreatedAt, validUntil);
        ExternalRepositoryId requiredRepositoryId = Objects.requireNonNull(repositoryId, "repositoryId");
        requireSourceDeliveryAccess(facts, authorizedResourceKey);
        ActionAuthoritySnapshot authority = ActionAuthoritySnapshot.capture(facts, requiredCreatedAt);
        PrincipalId actorId = requireActor(actor, authority.scope());
        RepositoryBranchName head = Objects.requireNonNull(deliveryBranch, "deliveryBranch").shortName();
        PlannedAction push = PlannedAction.plan(
                pushActionId,
                1,
                new PushBranchActionParameters(
                        requiredRepositoryId,
                        deliveryBranch,
                        authority.targetPrecondition().deliveryCommit(),
                        expectedRemoteHead,
                        authority.providerAuthorization().connectionId()),
                List.of(),
                authority,
                ActionRiskLevel.HIGH_RISK_WRITE,
                requiredValidUntil);
        PlannedAction pullRequest = PlannedAction.plan(
                pullRequestActionId,
                2,
                new CreateDraftPullRequestActionParameters(
                        requiredRepositoryId,
                        head,
                        authority.targetPrecondition().defaultBranch(),
                        authority.targetPrecondition().deliveryCommit(),
                        title,
                        body,
                        true,
                        authority.providerAuthorization().connectionId()),
                List.of(new ActionDependency(push.id())),
                authority,
                ActionRiskLevel.LOW_RISK_WRITE,
                requiredValidUntil);
        return new ActionBundle(
                bundleId,
                authority,
                List.of(push, pullRequest),
                requiredValidUntil,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, requiredCreatedAt));
    }

    /** Reconstitutes persisted facts and verifies child and Bundle digests. */
    public static ActionBundle reconstitute(
            ActionBundleId id,
            ActionAuthoritySnapshot authority,
            List<PlannedAction> actions,
            UtcTimestamp validUntil,
            ActionBundleDigest digest,
            long version,
            AuditMetadata audit) {
        return new ActionBundle(
                id, authority, actions, validUntil,
                Optional.of(Objects.requireNonNull(digest, "digest")), version, audit);
    }

    /** Internal graph constructor used by future domain planners after their own authority checks. */
    static ActionBundle planGraph(
            ActionBundleId id,
            ActionAuthoritySnapshot authority,
            List<PlannedAction> actions,
            UtcTimestamp validUntil,
            Principal actor,
            UtcTimestamp createdAt) {
        UtcTimestamp requiredCreatedAt = Objects.requireNonNull(createdAt, "createdAt");
        UtcTimestamp requiredValidUntil = requireValidity(requiredCreatedAt, validUntil);
        return new ActionBundle(
                id,
                authority,
                actions,
                requiredValidUntil,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(requireActor(actor, authority.scope()), requiredCreatedAt));
    }

    /** Fails closed before confirmation or dispatch when time or any current authority has changed. */
    public void requireCurrent(ActionAuthorityFacts currentFacts, UtcTimestamp now) {
        UtcTimestamp requiredNow = Objects.requireNonNull(now, "now");
        if (requiredNow.compareTo(validUntil) >= 0) {
            throw new StaleActionBundleException(ActionInvalidationReason.EXPIRED);
        }
        final ActionAuthoritySnapshot current;
        try {
            current = ActionAuthoritySnapshot.capture(currentFacts, requiredNow);
        } catch (DomainException exception) {
            throw new StaleActionBundleException(ActionInvalidationReason.AUTHORITY_UNAVAILABLE);
        }
        invalidationReason(current).ifPresent(reason -> {
            throw new StaleActionBundleException(reason);
        });
    }

    public boolean isCurrent(ActionAuthorityFacts currentFacts, UtcTimestamp now) {
        try {
            requireCurrent(currentFacts, now);
            return true;
        } catch (StaleActionBundleException exception) {
            return false;
        }
    }

    private Optional<ActionInvalidationReason> invalidationReason(ActionAuthoritySnapshot current) {
        if (!authority.reviewDecision().equals(current.reviewDecision())
                || !authority.diff().equals(current.diff())) {
            return Optional.of(ActionInvalidationReason.REVIEW_CHANGED);
        }
        if (!authority.responsibility().equals(current.responsibility())) {
            return Optional.of(ActionInvalidationReason.RESPONSIBILITY_CHANGED);
        }
        if (!authority.providerAuthorization().equals(current.providerAuthorization())) {
            return Optional.of(ActionInvalidationReason.PROVIDER_AUTHORIZATION_CHANGED);
        }
        if (!authority.policy().equals(current.policy())) {
            return Optional.of(ActionInvalidationReason.POLICY_CHANGED);
        }
        if (!authority.safetyOverlay().equals(current.safetyOverlay())) {
            return Optional.of(ActionInvalidationReason.SAFETY_OVERLAY_CHANGED);
        }
        if (!authority.targetPrecondition().equals(current.targetPrecondition())) {
            return Optional.of(ActionInvalidationReason.TARGET_PRECONDITION_CHANGED);
        }
        return Optional.empty();
    }

    private ActionBundleDigest calculateDigest() {
        ActionCanonicalEncoder encoder = new ActionCanonicalEncoder("action-bundle-v1")
                .add(id.toString())
                .add(Integer.toString(actions.size()));
        for (PlannedAction action : actions) {
            encoder.add(action.id().toString())
                    .add(action.digest().toString())
                    .add(Integer.toString(action.dependencies().size()));
            action.dependencies().forEach(
                    dependency -> encoder.add(dependency.predecessorActionId().toString()));
        }
        return new ActionBundleDigest(encoder.digest());
    }

    private static List<PlannedAction> requireGraph(
            List<PlannedAction> values, ActionAuthoritySnapshot authority) {
        List<PlannedAction> required = List.copyOf(Objects.requireNonNull(values, "actions"));
        if (required.isEmpty() || required.size() > 100) {
            throw new DomainValidationException(
                    "actionBundle.actions", "must contain 1 to 100 actions");
        }
        Map<PlannedActionId, Integer> seen = new HashMap<>();
        Set<PlannedActionId> ids = new HashSet<>();
        for (int index = 0; index < required.size(); index++) {
            PlannedAction action = Objects.requireNonNull(required.get(index), "plannedAction");
            int expectedSequence = index + 1;
            if (action.kind() == ActionKind.NOTIFY_COLLABORATION) {
                throw new DomainValidationException(
                        "actionBundle.actions",
                        "NOTIFY_COLLABORATION uses the policy-preauthorized notification contract");
            }
            if (action.sequence() != expectedSequence
                    || !action.authority().equals(authority)
                    || !ids.add(action.id())) {
                throw new DomainValidationException(
                        "actionBundle.actions",
                        "must have unique IDs, continuous order and one authority snapshot");
            }
            for (ActionDependency dependency : action.dependencies()) {
                Integer predecessor = seen.get(dependency.predecessorActionId());
                if (predecessor == null || predecessor >= expectedSequence) {
                    throw new DomainValidationException(
                            "actionBundle.dependencies",
                            "must reference a known earlier action in the same Bundle");
                }
            }
            seen.put(action.id(), expectedSequence);
        }
        return required;
    }

    private static UtcTimestamp requireValidity(UtcTimestamp createdAt, UtcTimestamp validUntil) {
        UtcTimestamp required = Objects.requireNonNull(validUntil, "validUntil");
        if (required.compareTo(createdAt) <= 0
                || Duration.between(createdAt.value(), required.value()).compareTo(MAX_VALIDITY) > 0) {
            throw new DomainValidationException(
                    "actionBundle.validUntil", "must be after creation and no more than 15 minutes later");
        }
        return required;
    }

    private static void requireSourceDeliveryAccess(
            ActionAuthorityFacts facts, String authorizedResourceKey) {
        ProviderAccessScope required = new ProviderAccessScope(
                ProviderCapabilities.of("source.write", "pull-request.create"),
                ProviderResourceScope.of(Objects.requireNonNull(
                        authorizedResourceKey, "authorizedResourceKey")));
        ProviderAccessScope effective = Objects.requireNonNull(facts, "facts")
                .providerBinding()
                .effectiveAccess();
        // A partial capability/resource overlap cannot authorize the complete Push + PR graph.
        if (effective.intersection(required).filter(required::equals).isEmpty()) {
            throw new DomainValidationException(
                    "actionBundle.providerAuthorization",
                    "must authorize source writes and Draft PR creation for the target repository");
        }
    }

    private static PrincipalId requireActor(Principal actor, WorkItemScope scope) {
        Principal required = Objects.requireNonNull(actor, "actor");
        boolean wrongTeam = required.scope().teamId().isPresent()
                && required.scope().teamId().filter(scope.teamId()::equals).isEmpty();
        if (!required.canAct()
                || !required.scope().organizationId().equals(scope.organizationId())
                || wrongTeam) {
            throw new DomainValidationException(
                    "actionBundle.createdByPrincipalId", "must be an active Principal in scope");
        }
        return required.id();
    }

    public ActionBundleId id() { return id; }
    public ActionAuthoritySnapshot authority() { return authority; }
    public List<PlannedAction> actions() { return actions; }
    public UtcTimestamp validUntil() { return validUntil; }
    public ActionBundleDigest digest() { return digest; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
