package io.crewscope.application.action;

import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.github.GitHubRepositoryStatus;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.review.ContextPackageRepository;
import io.crewscope.application.review.ReviewDecisionRepository;
import io.crewscope.application.review.ReviewRequestRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.domain.action.ActionAuthorityFacts;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.action.RepositoryBranchReference;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.shared.time.TimeProvider;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

/** Repository-backed planning resolver that always selects the latest mutable authority facts. */
public final class CurrentActionDeliveryPlanningResolver implements ActionDeliveryPlanningResolver {

    private static final Set<ExecutionWorkspaceStatus> DELIVERABLE_WORKSPACE_STATUSES =
            Set.of(ExecutionWorkspaceStatus.COMPLETED, ExecutionWorkspaceStatus.ARCHIVED);

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
    private final ExecutionWorkspaceRepository workspaces;
    private final GitHubProviderRepository github;
    private final TimeProvider timeProvider;

    public CurrentActionDeliveryPlanningResolver(
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
            ExecutionWorkspaceRepository workspaces,
            GitHubProviderRepository github,
            TimeProvider timeProvider) {
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
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.github = Objects.requireNonNull(github, "github");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public ActionDeliveryPlanningFacts resolve(
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ReviewDecisionId reviewDecisionId,
            ProviderBindingId providerBindingId,
            ExternalRepositoryId externalRepositoryId) {
        var decision = decisions.findById(organizationId, reviewDecisionId)
                .filter(value -> value.taskId().equals(taskId))
                .filter(value -> value.reviewRequest().taskExecutionId().equals(executionId))
                .orElseThrow(() -> unavailable("selected ReviewDecision"));
        var request = reviewRequests.findCurrentByExecution(
                        organizationId, executionId, decision.reviewRequest().attempt())
                .filter(value -> value.id().equals(decision.reviewRequest().id()))
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> unavailable("current ReviewRequest"));
        var latestDecision = decisions.findLatestByRequest(organizationId, request.id())
                .filter(value -> value.id().equals(decision.id()))
                .orElseThrow(() -> unavailable("latest ReviewDecision"));
        var context = contexts.findById(organizationId, request.contextPackage().id())
                .orElseThrow(() -> unavailable("current ContextPackage"));
        var responsibility = responsibilities.findActiveOwner(
                        organizationId, latestDecision.workItemId())
                .orElseThrow(() -> unavailable("current OWNER responsibility"));
        var binding = providerBindings.findById(organizationId, providerBindingId)
                .orElseThrow(() -> unavailable("selected ProviderBinding"));
        var connection = binding.connectionId()
                .flatMap(id -> connections.findById(organizationId, id))
                .orElseThrow(() -> unavailable("pinned Connection"));
        var grant = binding.connectionGrantId()
                .flatMap(id -> grants.findById(organizationId, id))
                .orElseThrow(() -> unavailable("pinned ConnectionGrant"));
        var catalog = github.findRepository(
                        organizationId, connection.id(), externalRepositoryId.value())
                .filter(value -> value.connectionVersion() == connection.version())
                .filter(value -> value.externalIdentity().equals(
                        binding.executionIdentity().orElse(null)))
                .filter(value -> value.status() == GitHubRepositoryStatus.DELIVERABLE)
                .filter(value -> value.isCurrentAt(timeProvider.now()))
                .orElseThrow(() -> unavailable("deliverable GitHub Repository Catalog entry"));
        boolean catalogAuthorized = binding.effectiveAccess().resources().unrestricted()
                || binding.effectiveAccess().resources().resources().contains(
                        catalog.grantResourceKey());
        if (!catalogAuthorized) {
            throw unavailable("GitHub Repository Grant");
        }
        var policy = policies.findByExecution(organizationId, executionId).stream()
                .max(Comparator.comparingLong(value -> value.revision()))
                .orElseThrow(() -> unavailable("current PolicySnapshot"));
        var safety = safetyOverlays.findByExecution(organizationId, executionId).stream()
                .max(Comparator.comparingLong(value -> value.version()))
                .orElseThrow(() -> unavailable("current SafetyEnforcementOverlay"));
        var target = codingTargets.findLatestByTask(
                        organizationId, teamId, request.scope().projectId(), taskId)
                .orElseThrow(() -> unavailable("current CodingTargetSnapshot"));
        var repository = repositories.findById(
                        organizationId, teamId, request.scope().projectId(),
                        target.repositoryBindingId())
                .orElseThrow(() -> unavailable("current RepositoryBinding"));
        var workspace = workspaces.findByTaskExecution(
                        organizationId, teamId, request.scope().projectId(), executionId)
                .filter(value -> value.attempt() == request.attempt())
                .filter(value -> DELIVERABLE_WORKSPACE_STATUSES.contains(value.status()))
                .orElseThrow(() -> unavailable("completed managed ExecutionWorkspace"));
        ActionAuthorityFacts facts = new ActionAuthorityFacts(
                request,
                context,
                latestDecision,
                request.diff(),
                responsibility,
                binding,
                connection,
                grant,
                policy,
                safety,
                target,
                repository);
        return new ActionDeliveryPlanningFacts(
                facts,
                new RepositoryBranchReference("refs/heads/" + workspace.managedBranch().value()),
                catalog.grantResourceKey());
    }

    private static DomainValidationException unavailable(String fact) {
        return new DomainValidationException("actionBundle.authority", fact + " is unavailable");
    }
}
