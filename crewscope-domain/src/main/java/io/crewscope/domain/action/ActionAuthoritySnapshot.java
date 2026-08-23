package io.crewscope.domain.action;

import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.review.ReviewDecisionReference;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestReference;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.SafetyRestriction;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable closure over Review, responsibility, Provider, policy and target authority. */
public record ActionAuthoritySnapshot(
        WorkItemScope scope,
        WorkItemId workItemId,
        TaskId taskId,
        TaskExecutionId taskExecutionId,
        int attempt,
        ReviewDecisionReference reviewDecision,
        ReviewDiffReference diff,
        ResponsibilityReference responsibility,
        ProviderAuthorizationReference providerAuthorization,
        ActionPolicyReference policy,
        SafetyEnforcementOverlayReference safetyOverlay,
        ActionTargetPrecondition targetPrecondition) {

    private static final Set<SafetyRestriction> BLOCKING_RESTRICTIONS = EnumSet.of(
            SafetyRestriction.PRINCIPAL_DISABLED,
            SafetyRestriction.MEMBERSHIP_DISABLED,
            SafetyRestriction.PROVIDER_BINDING_DISABLED,
            SafetyRestriction.CONNECTION_REVOKED,
            SafetyRestriction.CREDENTIAL_REVOKED,
            SafetyRestriction.RESOURCE_BLOCKED,
            SafetyRestriction.PLUGIN_KILL_SWITCH);
    private static final Set<String> ACTION_TOOLS =
            Set.of("pull-request.create-draft", "repository.push");

    public ActionAuthoritySnapshot {
        scope = Objects.requireNonNull(scope, "scope");
        workItemId = Objects.requireNonNull(workItemId, "workItemId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new IllegalArgumentException("Action attempt must be positive");
        }
        reviewDecision = Objects.requireNonNull(reviewDecision, "reviewDecision");
        diff = Objects.requireNonNull(diff, "diff");
        responsibility = Objects.requireNonNull(responsibility, "responsibility");
        providerAuthorization = Objects.requireNonNull(providerAuthorization, "providerAuthorization");
        policy = Objects.requireNonNull(policy, "policy");
        safetyOverlay = Objects.requireNonNull(safetyOverlay, "safetyOverlay");
        targetPrecondition = Objects.requireNonNull(targetPrecondition, "targetPrecondition");
        if (reviewDecision.type() != ReviewDecisionType.APPROVED) {
            throw new DomainValidationException(
                    "actionBundle.reviewDecision", "must be an APPROVED member Gate Decision");
        }
    }

    /** Resolves and validates every current server-owned object before a Bundle can be planned. */
    public static ActionAuthoritySnapshot capture(ActionAuthorityFacts facts, UtcTimestamp now) {
        ActionAuthorityFacts current = Objects.requireNonNull(facts, "facts");
        UtcTimestamp requiredNow = Objects.requireNonNull(now, "now");
        ReviewRequest request = current.reviewRequest();
        request.requireCurrent(current.contextPackage());
        if (request.status() != ReviewRequestStatus.COMPLETED) {
            throw new DomainValidationException(
                    "actionBundle.reviewRequest", "must be current and COMPLETED");
        }
        if (current.reviewDecision().type() != ReviewDecisionType.APPROVED
                || !current.reviewDecision().reviewRequest().equals(ReviewRequestReference.from(request))) {
            throw new DomainValidationException(
                    "actionBundle.reviewDecision", "must approve the exact current ReviewRequest");
        }
        if (!current.diff().equals(request.diff())
                || !current.diff().equals(current.contextPackage().diff())
                || !request.subject().equals(current.contextPackage().subject())) {
            throw new DomainValidationException(
                    "actionBundle.diff", "must be the exact reviewed Diff and subject");
        }
        requireLineage(current);
        ResponsibilityAssignment responsibility = requireResponsibility(current);
        ProviderBinding binding = requireProvider(current, requiredNow);
        PolicySnapshot policy = requirePolicy(current, binding);
        requireTarget(current);
        return new ActionAuthoritySnapshot(
                request.scope(),
                current.reviewDecision().workItemId(),
                request.taskId(),
                request.taskExecutionId(),
                request.attempt(),
                current.reviewDecision().reference(),
                current.diff(),
                new ResponsibilityReference(
                        responsibility.id(),
                        responsibility.version(),
                        responsibility.role(),
                        responsibility.actorPrincipalId()),
                providerReference(binding, current.connection(), current.connectionGrant()),
                new ActionPolicyReference(policy.id(), policy.revision(), policy.snapshotHash()),
                current.safetyOverlay().reference(),
                new ActionTargetPrecondition(
                        current.repositoryBinding().id(),
                        current.repositoryBinding().version(),
                        current.repositoryBinding().repositoryKey(),
                        current.repositoryBinding().defaultBranch(),
                        current.codingTarget().reference(),
                        current.diff().baselineCommit(),
                        current.diff().deliveryCommit()));
    }

    private static void requireLineage(ActionAuthorityFacts current) {
        ReviewRequest request = current.reviewRequest();
        boolean mismatch = !request.scope().equals(current.diff().scope())
                || !request.taskId().equals(current.diff().taskId())
                || !request.taskExecutionId().equals(current.diff().taskExecutionId())
                || request.attempt() != current.diff().attempt()
                || !request.scope().equals(current.policySnapshot().scope())
                || !request.taskId().equals(current.policySnapshot().taskId())
                || !request.taskExecutionId().equals(current.policySnapshot().executionId())
                || !request.scope().equals(current.safetyOverlay().scope())
                || !request.taskId().equals(current.safetyOverlay().taskId())
                || !request.taskExecutionId().equals(current.safetyOverlay().executionId());
        if (mismatch) {
            throw new DomainValidationException(
                    "actionBundle.authority", "all facts must share exact Scope, Task and execution");
        }
        if (!java.util.Collections.disjoint(
                current.safetyOverlay().restrictions(), BLOCKING_RESTRICTIONS)) {
            throw new DomainValidationException(
                    "actionBundle.safetyOverlay", "contains a blocking real-time restriction");
        }
    }

    private static ResponsibilityAssignment requireResponsibility(ActionAuthorityFacts current) {
        ResponsibilityAssignment value = current.responsibility();
        if (!value.isActive()
                || value.role() != ResponsibilityRole.OWNER
                || !value.scope().equals(current.reviewRequest().scope())
                || !value.workItemId().equals(current.reviewDecision().workItemId())) {
            throw new DomainValidationException(
                    "actionBundle.responsibility", "must be the current active WorkItem OWNER fact");
        }
        return value;
    }

    private static ProviderBinding requireProvider(
            ActionAuthorityFacts current, UtcTimestamp now) {
        ProviderBinding binding = current.providerBinding();
        Connection connection = current.connection();
        ConnectionGrant grant = current.connectionGrant();
        boolean wrongProject = binding.target().workProjectId()
                .filter(current.reviewRequest().scope().projectId()::equals)
                .isEmpty() && binding.target().workProjectId().isPresent();
        boolean mismatch = binding.status() != ProviderRegistrationStatus.ACTIVE
                || binding.providerType() != ProviderType.SOURCE_CODE
                || !binding.organizationId().equals(current.reviewRequest().scope().organizationId())
                || !binding.target().teamId().equals(current.reviewRequest().scope().teamId())
                || !binding.target().workspaceId().equals(current.reviewRequest().scope().workspaceId())
                || wrongProject
                || !binding.connectionId().equals(java.util.Optional.of(connection.id()))
                || !binding.connectionVersion().equals(java.util.Optional.of(connection.version()))
                || !binding.connectionGrantId().equals(java.util.Optional.of(grant.id()))
                || !binding.connectionGrantVersion().equals(java.util.Optional.of(grant.version()))
                || binding.executionIdentity().isEmpty()
                || !binding.owner().equals(grant.grantee())
                || binding.executionIdentity().filter(
                        expectedExecutionIdentity(connection.owner().type())::equals).isEmpty()
                || !binding.effectiveAccess().capabilities().includes(
                        ProviderCapabilities.of("source.write", "pull-request.create"))
                || !connection.isUsableAt(now)
                || grant.effectiveAccess(binding.effectiveAccess(), connection, now)
                        .filter(binding.effectiveAccess()::equals)
                        .isEmpty();
        if (mismatch) {
            throw new DomainValidationException(
                    "actionBundle.providerAuthorization",
                    "Binding, Connection and Grant must remain active at their pinned versions");
        }
        return binding;
    }

    private static PolicySnapshot requirePolicy(
            ActionAuthorityFacts current, ProviderBinding binding) {
        PolicySnapshot policy = current.policySnapshot();
        if (!policy.providerBindingIds().contains(binding.id())) {
            throw new DomainValidationException(
                    "actionBundle.policySnapshot", "must authorize the selected ProviderBinding");
        }
        if (!current.safetyOverlay().permits(policy, Set.of(), ACTION_TOOLS)) {
            throw new DomainValidationException(
                    "actionBundle.policySnapshot",
                    "PolicySnapshot and Safety Overlay must permit the delivery action tools");
        }
        return policy;
    }

    private static ProviderExecutionIdentity expectedExecutionIdentity(ProviderOwnerType ownerType) {
        return switch (ownerType) {
            case USER -> ProviderExecutionIdentity.DELEGATED_USER;
            case TEAM -> ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT;
            case ORGANIZATION -> ProviderExecutionIdentity.ORGANIZATION_SERVICE_ACCOUNT;
        };
    }

    private static void requireTarget(ActionAuthorityFacts current) {
        RepositoryBinding repository = current.repositoryBinding();
        boolean mismatch = !repository.acceptsNewTargets()
                || !current.codingTarget().scope().equals(current.reviewRequest().scope())
                || !current.codingTarget().taskId().equals(current.reviewRequest().taskId())
                || !current.codingTarget().reference().equals(current.diff().codingTarget())
                || !current.codingTarget().repositoryBindingId().equals(repository.id())
                || current.codingTarget().repositoryBindingVersion() != repository.version()
                || !current.codingTarget().repositoryKey().equals(repository.repositoryKey())
                || !current.codingTarget().baselineCommit().equals(current.diff().baselineCommit());
        if (mismatch) {
            throw new DomainValidationException(
                    "actionBundle.targetPrecondition",
                    "must match the active reviewed CodingTarget and RepositoryBinding");
        }
    }

    private static ProviderAuthorizationReference providerReference(
            ProviderBinding binding, Connection connection, ConnectionGrant grant) {
        return new ProviderAuthorizationReference(
                binding.id(),
                binding.version(),
                binding.definitionId(),
                binding.definitionVersion(),
                binding.implementationId(),
                binding.implementationVersion(),
                binding.providerType(),
                binding.executionIdentity().orElseThrow(),
                connection.id(),
                connection.version(),
                grant.id(),
                grant.version(),
                accessHash(binding.effectiveAccess()));
    }

    private static io.crewscope.domain.task.TaskFactHash accessHash(ProviderAccessScope access) {
        String capabilities = access.capabilities().values().stream()
                .map(Object::toString)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(","));
        String resources = access.resources().resources().stream()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(","));
        return new ActionCanonicalEncoder("provider-access-v1")
                .add(capabilities)
                .add(Boolean.toString(access.resources().unrestricted()))
                .add(resources)
                .digest();
    }

    void appendCanonical(ActionCanonicalEncoder encoder) {
        ReviewRequestReference request = reviewDecision.reviewRequest();
        encoder.add(scope.organizationId().toString())
                .add(scope.teamId().toString())
                .add(scope.workspaceId().toString())
                .add(scope.projectId().toString())
                .add(workItemId.toString())
                .add(taskId.toString())
                .add(taskExecutionId.toString())
                .add(Integer.toString(attempt))
                .add(reviewDecision.id().toString())
                .add(Long.toString(reviewDecision.revision()))
                .add(reviewDecision.decisionHash().toString())
                .add(request.id().toString())
                .add(Long.toString(request.revision()))
                .add(Long.toString(request.version()))
                .add(request.requestHash().toString())
                .add(request.subject().subjectHash().toString())
                .add(request.contextPackage().contextHash().toString())
                .add(diff.artifact().id().toString())
                .add(diff.artifact().finalHash().toString())
                .add(responsibility.id().toString())
                .add(Long.toString(responsibility.version()))
                .add(responsibility.role().name())
                .add(responsibility.actorPrincipalId().toString());
        appendProvider(encoder);
        encoder.add(policy.id().toString())
                .add(Long.toString(policy.revision()))
                .add(policy.snapshotHash().toString())
                .add(safetyOverlay.id().toString())
                .add(Long.toString(safetyOverlay.version()))
                .add(safetyOverlay.overlayHash().toString())
                .add(targetPrecondition.repositoryBindingId().toString())
                .add(Long.toString(targetPrecondition.repositoryBindingVersion()))
                .add(targetPrecondition.repositoryKey().value())
                .add(targetPrecondition.defaultBranch().value())
                .add(targetPrecondition.codingTarget().snapshotId().toString())
                .add(Long.toString(targetPrecondition.codingTarget().revision()))
                .add(targetPrecondition.codingTarget().snapshotHash().toString())
                .add(targetPrecondition.baselineCommit().value())
                .add(targetPrecondition.deliveryCommit().value());
    }

    private void appendProvider(ActionCanonicalEncoder encoder) {
        ProviderAuthorizationReference provider = providerAuthorization;
        encoder.add(provider.bindingId().toString())
                .add(Long.toString(provider.bindingVersion()))
                .add(provider.definitionId().toString())
                .add(Long.toString(provider.definitionVersion()))
                .add(provider.implementationId().toString())
                .add(Long.toString(provider.implementationVersion()))
                .add(provider.providerType().name())
                .add(provider.executionIdentity().name())
                .add(provider.connectionId().toString())
                .add(Long.toString(provider.connectionVersion()))
                .add(provider.grantId().toString())
                .add(Long.toString(provider.grantVersion()))
                .add(provider.effectiveAccessHash().toString());
    }
}
