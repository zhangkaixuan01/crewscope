package io.crewscope.application.action;

import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.review.ContextPackageRepository;
import io.crewscope.application.review.ReviewDecisionRepository;
import io.crewscope.application.review.ReviewRequestRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.team.MemberAuthorizationGuard;
import io.crewscope.domain.action.ActionAuthorityFacts;
import io.crewscope.domain.action.ActionAuthoritySnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Comparator;
import java.util.Objects;

/** Repository-backed resolver that deliberately selects the latest mutable authority facts. */
public final class CurrentActionAuthorityFactsResolver implements ActionAuthorityFactsResolver {

    private final ReviewRequestRepository reviewRequests;
    private final ContextPackageRepository contexts;
    private final ReviewDecisionRepository decisions;
    private final ResponsibilityAssignmentRepository responsibilities;
    private final ProviderBindingRepository providerBindings;
    private final ConnectionRepository connections;
    private final ConnectionGrantRepository grants;
    private final PolicySnapshotRepository policies;
    private final SafetyEnforcementOverlayRepository safetyOverlays;
    private final CodingTargetSnapshotRepository codingTargets;
    private final RepositoryBindingRepository repositories;
    private final MemberAuthorizationGuard memberGuard;

    public CurrentActionAuthorityFactsResolver(
            ReviewRequestRepository reviewRequests,
            ContextPackageRepository contexts,
            ReviewDecisionRepository decisions,
            ResponsibilityAssignmentRepository responsibilities,
            ProviderBindingRepository providerBindings,
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            PolicySnapshotRepository policies,
            SafetyEnforcementOverlayRepository safetyOverlays,
            CodingTargetSnapshotRepository codingTargets,
            RepositoryBindingRepository repositories,
            MemberAuthorizationGuard memberGuard) {
        this.reviewRequests = Objects.requireNonNull(reviewRequests, "reviewRequests");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.responsibilities = Objects.requireNonNull(responsibilities, "responsibilities");
        this.providerBindings = Objects.requireNonNull(providerBindings, "providerBindings");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.safetyOverlays = Objects.requireNonNull(safetyOverlays, "safetyOverlays");
        this.codingTargets = Objects.requireNonNull(codingTargets, "codingTargets");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.memberGuard = Objects.requireNonNull(memberGuard, "memberGuard");
    }

    @Override
    public ActionAuthorityFacts resolveCurrent(ActionAuthoritySnapshot authority) {
        ActionAuthoritySnapshot confirmed = Objects.requireNonNull(authority, "authority");
        var scope = confirmed.scope();
        var organizationId = scope.organizationId();
        var request = reviewRequests.findCurrentByExecution(
                        organizationId, confirmed.taskExecutionId(), confirmed.attempt())
                .orElseThrow(() -> unavailable("current ReviewRequest"));
        var context = contexts.findById(organizationId, request.contextPackage().id())
                .orElseThrow(() -> unavailable("current ContextPackage"));
        var decision = decisions.findLatestByRequest(organizationId, request.id())
                .orElseThrow(() -> unavailable("current ReviewDecision"));
        var responsibility = responsibilities.findActiveOwner(
                        organizationId, confirmed.workItemId())
                .orElseThrow(() -> unavailable("current OWNER responsibility"));
        // M9b-A07: the OWNER responsibility alone is not current authority — the acting member
        // (or the Agent principal's owning member) must still participate in the Team, or the
        // dispatch refuses to hand out fresh adapter credentials.
        try {
            requireOwnerMembership(scope, responsibility);
        } catch (RuntimeException revoked) {
            throw unavailable("current OWNER membership");
        }
        var providerBinding = providerBindings.findById(
                        organizationId, confirmed.providerAuthorization().bindingId())
                .orElseThrow(() -> unavailable("current ProviderBinding"));
        var connection = connections.findById(
                        organizationId, confirmed.providerAuthorization().connectionId())
                .orElseThrow(() -> unavailable("current Connection"));
        var grant = grants.findById(
                        organizationId, confirmed.providerAuthorization().grantId())
                .orElseThrow(() -> unavailable("current ConnectionGrant"));
        PolicySnapshot policy = policies.findByExecution(
                        organizationId, confirmed.taskExecutionId()).stream()
                .max(Comparator.comparingLong(PolicySnapshot::revision))
                .orElseThrow(() -> unavailable("current PolicySnapshot"));
        SafetyEnforcementOverlay safety = safetyOverlays.findByExecution(
                        organizationId, confirmed.taskExecutionId()).stream()
                .max(Comparator.comparingLong(SafetyEnforcementOverlay::version))
                .orElseThrow(() -> unavailable("current SafetyEnforcementOverlay"));
        CodingTargetSnapshot codingTarget = codingTargets.findLatestByTask(
                        organizationId, scope.teamId(), scope.projectId(), confirmed.taskId())
                .orElseThrow(() -> unavailable("current CodingTargetSnapshot"));
        var repository = repositories.findById(
                        organizationId,
                        scope.teamId(),
                        scope.projectId(),
                        confirmed.targetPrecondition().repositoryBindingId())
                .orElseThrow(() -> unavailable("current RepositoryBinding"));
        return new ActionAuthorityFacts(
                request,
                context,
                decision,
                request.diff(),
                responsibility,
                providerBinding,
                connection,
                grant,
                policy,
                safety,
                codingTarget,
                repository);
    }

    private void requireOwnerMembership(
            WorkItemScope scope, ResponsibilityAssignment owner) {
        if (owner.actorType().isAgent()) {
            memberGuard.requireAgentOwnerParticipation(
                    scope.organizationId(), scope.teamId(), owner.actorPrincipalId());
        } else {
            memberGuard.requireParticipation(
                    scope.organizationId(), scope.teamId(), owner.actorPrincipalId());
        }
    }

    private static DomainValidationException unavailable(String fact) {
        return new DomainValidationException(
                "actionDispatch.authority", fact + " is unavailable");
    }
}
