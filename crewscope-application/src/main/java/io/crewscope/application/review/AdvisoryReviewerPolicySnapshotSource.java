package io.crewscope.application.review;

import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.CreateResolvedPolicySnapshotRequest;
import io.crewscope.application.agent.ResolveAgentExecutionConfigurationRequest;
import io.crewscope.application.agent.ResolvedAgentPolicySnapshotService;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentExecutionScopeFacts;
import io.crewscope.domain.agent.AgentExecutionScopePolicy;
import io.crewscope.domain.agent.AgentModelPolicyConstraints;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workitem.WorkItem;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Production {@link ReviewerPolicySnapshotAutoSource}: resolves the single advisory Reviewer Agent
 * holding the active REVIEWER responsibility, then resolves its current model graph through the
 * same trusted pipeline Task delegation uses and pins it as one immutable per-attempt snapshot.
 * The reviewer executes read-only structured output over a bounded read-only tool face.
 */
public final class AdvisoryReviewerPolicySnapshotSource implements ReviewerPolicySnapshotAutoSource {

    /** Reviewer executions only produce structured findings; the budget stays finite and flat. */
    private static final PolicyBudget REVIEWER_BUDGET = new PolicyBudget(
            65_536L, 8, 1, 3_600L);
    /** Read-only tool face: reading the delivered diff grounds the reviewer's findings. */
    private static final Set<String> REVIEWER_TOOLS = Set.of("repository.read");
    private static final Set<ExecutionCapability> REVIEWER_CAPABILITIES = Set.of(
            ExecutionCapability.STRUCTURED_OUTPUT);

    private final AgentProfileRepository profiles;
    private final PrincipalRepository principals;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final ModelConnectionRepository connections;
    private final AgentModelGovernance governance;
    private final AgentExecutionConfigurationService executionConfigurations;
    private final ResolvedAgentPolicySnapshotService snapshots;
    private final PolicySnapshotRepository policies;
    private final TimeProvider timeProvider;

    public AdvisoryReviewerPolicySnapshotSource(
            AgentProfileRepository profiles,
            PrincipalRepository principals,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            ModelConnectionRepository connections,
            AgentModelGovernance governance,
            AgentExecutionConfigurationService executionConfigurations,
            ResolvedAgentPolicySnapshotService snapshots,
            PolicySnapshotRepository policies,
            TimeProvider timeProvider) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.executionConfigurations = Objects.requireNonNull(
                executionConfigurations, "executionConfigurations");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public PolicySnapshot resolveSnapshot(
            TeamAccessContext context,
            Task task,
            WorkItem workItem,
            TaskExecution execution,
            List<ResponsibilityAssignment> assignments) {
        TeamAccessContext trusted = Objects.requireNonNull(context, "context");
        Task requiredTask = Objects.requireNonNull(task, "task");
        WorkItem requiredItem = Objects.requireNonNull(workItem, "workItem");
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        List<ResponsibilityAssignment> currentAssignments = List.copyOf(
                Objects.requireNonNull(assignments, "assignments"));

        Principal reviewerAgent = soleAdvisoryReviewer(
                requiredTask.scope().organizationId(), currentAssignments);
        AgentProfile profile = activeReviewerProfile(
                requiredTask.scope().organizationId(), reviewerAgent);

        // The snapshot is pinned per attempt, so the same execution reuses its exact reviewer
        // coordinates instead of re-inheriting a binding that may have changed in between.
        Optional<PolicySnapshot> reusable = policies.findByExecution(
                        requiredTask.scope().organizationId(), requiredExecution.id()).stream()
                .filter(value -> value.agentExecutionConfiguration().isPresent())
                .filter(value -> matchesReviewer(value.agentExecutionConfiguration().orElseThrow(),
                        profile, requiredTask, requiredExecution))
                .findFirst();
        if (reusable.isPresent()) {
            return reusable.orElseThrow();
        }

        ResolvedFacts facts = resolvedFacts(trusted, requiredItem, profile, reviewerAgent,
                currentAssignments);
        UtcTimestamp reviewAt = timeProvider.now();
        ResolveAgentExecutionConfigurationRequest request =
                new ResolveAgentExecutionConfigurationRequest(
                        requiredItem.scope().organizationId(),
                        profile.id(),
                        Optional.empty(),
                        facts.scopeFacts(),
                        facts.policyConstraints(),
                        facts.authorization(),
                        reviewAt);
        ResolvedAgentExecutionConfiguration resolved = executionConfigurations.resolve(request);
        // The Reviewer snapshot pins the agent's own REVIEWER seat in a review-time recapture of
        // the current responsibilities — a reviewer is assigned after the Task delegated its
        // execution, so the Task's creation-time snapshot (OWNER + EXECUTOR only) can never
        // carry the seat, and the EXECUTOR seat belongs to the coding agent anyway.
        TaskResponsibilitySnapshot reviewResponsibility = TaskResponsibilitySnapshot.capture(
                requiredItem, currentAssignments, reviewAt);
        return snapshots.createInitial(new CreateResolvedPolicySnapshotRequest(
                PolicySnapshotId.generate(),
                requiredTask,
                requiredExecution,
                reviewerAgent,
                io.crewscope.domain.responsibility.ResponsibilityRole.REVIEWER,
                reviewResponsibility,
                request,
                REVIEWER_CAPABILITIES,
                REVIEWER_TOOLS,
                Set.of(),
                REVIEWER_BUDGET,
                trusted.actor(),
                reviewAt),
                resolved);
    }

    private Principal soleAdvisoryReviewer(
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            List<ResponsibilityAssignment> assignments) {
        List<PrincipalId> reviewerPrincipals = assignments.stream()
                .filter(ResponsibilityAssignment::isActive)
                .filter(value -> value.role() == ResponsibilityRole.REVIEWER)
                .filter(value -> value.actorType().isAgent())
                .map(ResponsibilityAssignment::actorPrincipalId)
                .distinct()
                .toList();
        if (reviewerPrincipals.isEmpty()) {
            throw new DomainValidationException(
                    "reviewRequest.reviewerAgent",
                    "assign an advisory Reviewer Agent before requesting a review");
        }
        if (reviewerPrincipals.size() > 1) {
            throw new DomainValidationException(
                    "reviewRequest.reviewerAgent",
                    "multiple advisory Reviewer Agents are assigned; select a reviewer policy snapshot explicitly");
        }
        return principals.findById(organizationId, reviewerPrincipals.get(0))
                .filter(Principal::canAct)
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal", reviewerPrincipals.get(0)));
    }

    private AgentProfile activeReviewerProfile(
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            Principal reviewerAgent) {
        return profiles.findActiveByAgentPrincipalId(organizationId, reviewerAgent.id())
                .filter(value -> value.templateVersion().key()
                        .equals(new AgentTemplateKey("reviewer")))
                .orElseThrow(() -> new DomainValidationException(
                        "reviewRequest.reviewerAgent",
                        "the advisory Reviewer Agent has no active reviewer-template profile"));
    }

    private static boolean matchesReviewer(
            ResolvedAgentExecutionConfiguration resolved,
            AgentProfile profile,
            Task task,
            TaskExecution execution) {
        return resolved.agentPrincipalId().equals(profile.agentPrincipalId())
                && resolved.agentProfileId().equals(profile.id())
                && resolved.agentProfileVersion() == profile.version()
                && resolved.templateVersion().key().equals(new AgentTemplateKey("reviewer"))
                && resolved.ownership().organizationId().equals(task.scope().organizationId());
    }

    private ResolvedFacts resolvedFacts(
            TeamAccessContext context,
            WorkItem item,
            AgentProfile profile,
            Principal reviewerAgent,
            List<ResponsibilityAssignment> assignments) {
        Principal actor = Objects.requireNonNull(context.actor(), "actor");
        Team team = teams.findById(item.scope().organizationId(), item.scope().teamId())
                .filter(Team::isActive)
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Team", item.scope().teamId()));
        TeamMember actorMember = memberships.findByTeam(
                        item.scope().organizationId(), item.scope().teamId()).stream()
                .filter(value -> value.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new DomainValidationException(
                        "reviewRequest.actorMemberId", "must be an active Team member"));
        Optional<TeamMember> ownerMember = profile.ownership().ownerMemberId()
                .map(ownerId -> memberships.findByTeam(
                                item.scope().organizationId(), item.scope().teamId()).stream()
                        .filter(value -> value.id().equals(ownerId))
                        .filter(TeamMember::canParticipate)
                        .findFirst()
                        .orElseThrow(() -> new DomainValidationException(
                                "reviewRequest.reviewerOwnerMemberId",
                                "Reviewer Agent owner must be an active Team member")));
        // The scope classification mirrors Task delegation: a user-owned reviewer whose owner also
        // holds the whole OWNER/EXECUTOR chain stays PERSONAL; anything team-shaped is TEAM.
        boolean teamOwned = profile.ownership().type() != AgentOwnershipType.USER;
        boolean ownerIsActor = ownerMember.filter(value -> value.id().equals(actorMember.id()))
                .isPresent();
        boolean personalResponsibilityChain = ownerIsActor && assignments.stream()
                .filter(ResponsibilityAssignment::isActive)
                .filter(value -> value.role() == ResponsibilityRole.OWNER
                        || value.role() == ResponsibilityRole.EXECUTOR)
                .allMatch(value -> value.actorPrincipalId().equals(actor.id())
                        || value.actorPrincipalId().equals(reviewerAgent.id()));
        AgentExecutionScopeFacts scopeFacts = new AgentExecutionScopeFacts(
                teamOwned, !personalResponsibilityChain, false, false);
        AgentExecutionScope scope = AgentExecutionScopePolicy.resolve(scopeFacts);
        List<ModelConnection> usable = usableConnections(team, profile, ownerMember, actor, scope);
        AgentModelPolicyConstraints policyConstraints = governance.resolve(
                actor, team.id(), profile, usable).policyConstraints();
        Optional<Principal> ownerUser = ownerMember.map(value -> principals.findById(
                        item.scope().organizationId(), value.userPrincipalId())
                .filter(Principal::canAct)
                .filter(candidate -> candidate.type() == PrincipalType.USER)
                .orElseThrow(() -> new DomainValidationException(
                        "reviewRequest.reviewerOwnerMemberId",
                        "Reviewer Agent owner principal is unavailable")));
        Principal requestingPrincipal = scope == AgentExecutionScope.PERSONAL && ownerUser.isPresent()
                ? ownerUser.orElseThrow()
                : actor;
        Set<ModelConnectionId> usableIds = usable.stream()
                .map(ModelConnection::id)
                .collect(Collectors.toUnmodifiableSet());
        AgentExecutionAuthorizationFacts authorization = new AgentExecutionAuthorizationFacts(
                requestingPrincipal.id(),
                requestingPrincipal.canAct(),
                actorMember.canParticipate()
                        && ownerMember.stream().allMatch(TeamMember::canParticipate),
                true,
                true,
                true,
                usableIds);
        return new ResolvedFacts(scopeFacts, policyConstraints, authorization);
    }

    private List<ModelConnection> usableConnections(
            Team team,
            AgentProfile profile,
            Optional<TeamMember> ownerMember,
            Principal actor,
            AgentExecutionScope scope) {
        Map<ModelConnectionId, ModelConnection> result = new LinkedHashMap<>();
        if (scope == AgentExecutionScope.PERSONAL
                && profile.ownership().type() == AgentOwnershipType.USER) {
            connections.findByOwner(ModelConnectionOwner.user(actor))
                    .forEach(value -> result.put(value.id(), value));
        }
        if (profile.ownership().type() != AgentOwnershipType.ORGANIZATION) {
            connections.findByOwner(ModelConnectionOwner.team(team))
                    .forEach(value -> result.put(value.id(), value));
        }
        connections.findByOwner(ModelConnectionOwner.organization(team.organizationId()))
                .forEach(value -> result.put(value.id(), value));
        return result.values().stream()
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .toList();
    }

    private record ResolvedFacts(
            AgentExecutionScopeFacts scopeFacts,
            AgentModelPolicyConstraints policyConstraints,
            AgentExecutionAuthorizationFacts authorization) {}
}
