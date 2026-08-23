package io.crewscope.domain.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.action.event.ActionBundlePlanned;
import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.AcceptanceStatus;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderBindingTargetType;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ReviewCommandEvidenceReference;
import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewDiffHunk;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewSubject;
import io.crewscope.domain.review.ReviewSubjectId;
import io.crewscope.domain.review.ReviewTestEvidenceReference;
import io.crewscope.domain.review.ReviewerExecutionReference;
import io.crewscope.domain.review.ReviewerRelationship;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.SafetyRestriction;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPriority;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.team.TeamStatus;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionBundleTest {

    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-23T02:00:00Z");
    private static final UtcTimestamp VALID_UNTIL = UtcTimestamp.parse("2026-08-23T02:10:00Z");
    private static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-23T02:01:00Z");

    @Test
    void plansTypedPushAndDraftPullRequestFromApprovedCurrentFacts() {
        Fixture fixture = new Fixture();

        ActionBundle bundle = fixture.bundle();

        assertEquals(List.of(ActionKind.PUSH_BRANCH, ActionKind.CREATE_DRAFT_PR),
                bundle.actions().stream().map(PlannedAction::kind).toList());
        assertEquals(ActionRiskLevel.HIGH_RISK_WRITE, bundle.actions().get(0).risk());
        assertEquals(ActionRiskLevel.LOW_RISK_WRITE, bundle.actions().get(1).risk());
        assertEquals(
                List.of(new ActionDependency(bundle.actions().get(0).id())),
                bundle.actions().get(1).dependencies());
        assertTrue(bundle.isCurrent(fixture.facts(), LATER));
        assertEquals(64, bundle.digest().toString().length());

        ActionBundlePlanned event = ActionBundlePlanned.from(bundle);
        assertEquals(bundle.id().value(), event.actionBundleId());
        assertEquals(List.of("PUSH_BRANCH", "CREATE_DRAFT_PR"), event.actionKinds());
        assertEquals(2, event.actionDigests().size());
    }

    @Test
    void changesDigestForParametersTargetBaselineRiskOrderDependenciesAndValidity() {
        Fixture fixture = new Fixture();
        ActionBundle original = fixture.bundle();
        ActionBundle changedTitle = fixture.bundle("A different title", VALID_UNTIL);
        ActionBundle changedValidity = fixture.bundle(
                "Create reviewed delivery", UtcTimestamp.parse("2026-08-23T02:09:00Z"));

        assertNotEquals(original.actions().get(1).digest(), changedTitle.actions().get(1).digest());
        assertNotEquals(original.digest(), changedTitle.digest());
        assertNotEquals(original.digest(), changedValidity.digest());

        PlannedAction push = original.actions().get(0);
        PlannedAction changedRisk = PlannedAction.plan(
                push.id(), push.sequence(), push.parameters(), push.dependencies(), push.authority(),
                ActionRiskLevel.DESTRUCTIVE, push.validUntil());
        assertNotEquals(push.digest(), changedRisk.digest());

        ActionTargetPrecondition oldTarget = push.authority().targetPrecondition();
        ActionAuthoritySnapshot changedBaseline = copyAuthority(
                push.authority(),
                new ActionTargetPrecondition(
                        oldTarget.repositoryBindingId(), oldTarget.repositoryBindingVersion(),
                        oldTarget.repositoryKey(), oldTarget.defaultBranch(), oldTarget.codingTarget(),
                        new RepositoryCommitId("d".repeat(40)), oldTarget.deliveryCommit()));
        PlannedAction baselineAction = PlannedAction.plan(
                push.id(), push.sequence(), push.parameters(), push.dependencies(), changedBaseline,
                push.risk(), push.validUntil());
        assertNotEquals(push.digest(), baselineAction.digest());

        PlannedAction noDependencyPullRequest = PlannedAction.plan(
                original.actions().get(1).id(), 2, original.actions().get(1).parameters(), List.of(),
                original.authority(), ActionRiskLevel.LOW_RISK_WRITE, VALID_UNTIL);
        ActionBundle withoutEdge = ActionBundle.planGraph(
                original.id(), original.authority(), List.of(push, noDependencyPullRequest),
                VALID_UNTIL, fixture.owner, CREATED_AT);
        assertNotEquals(original.digest(), withoutEdge.digest());

        assertThrows(DomainValidationException.class, () -> ActionBundle.planGraph(
                original.id(), original.authority(), List.of(original.actions().get(1), push),
                VALID_UNTIL, fixture.owner, CREATED_AT));
    }

    @Test
    void rejectsUnapprovedOrHistoricalReviewAndDiff() {
        Fixture fixture = new Fixture();
        ReviewDecision rejected = fixture.decision(ReviewDecisionType.CHANGES_REQUESTED);
        ActionAuthorityFacts unapproved = fixture.facts(rejected, fixture.diff);

        assertThrows(DomainValidationException.class, () -> fixture.bundle(unapproved));

        ReviewDiffReference changedDiff = fixture.diff(
                new DiffArtifactReference(DiffArtifactId.generate(), TaskFactHash.sha256("changed")),
                new RepositoryCommitId("c".repeat(40)));
        ActionAuthorityFacts staleDiff = fixture.facts(fixture.approval, changedDiff);
        assertThrows(DomainValidationException.class, () -> fixture.bundle(staleDiff));
        assertFalse(fixture.bundle().isCurrent(staleDiff, LATER));
    }

    @Test
    void failsClosedWhenResponsibilityOrProviderAuthorizationDrifts() {
        Fixture fixture = new Fixture();
        ActionBundle bundle = fixture.bundle();
        ResponsibilityAssignment released = fixture.ownerAssignment.release(fixture.owner, LATER);
        ActionAuthorityFacts releasedFacts = fixture.facts(
                fixture.approval, fixture.diff, released, fixture.binding,
                fixture.connection, fixture.grant, fixture.policy, fixture.overlay,
                fixture.repository);
        ConnectionGrant revoked = fixture.grant.revoke(
                fixture.grant.version(), fixture.owner, "access removed", LATER);
        ConnectionGrant partiallyAuthorized = ConnectionGrant.reconstitute(
                fixture.grant.id(), fixture.grant.organizationId(), fixture.grant.connectionId(),
                fixture.grant.connectionOwner(), fixture.grant.grantee(),
                new ProviderAccessScope(
                        ProviderCapabilities.of("source.write"),
                        ProviderResourceScope.of("repository:101")),
                fixture.grant.validFrom(), fixture.grant.expiresAt(), fixture.grant.status(),
                fixture.grant.terminalReason(), fixture.grant.version(), fixture.grant.audit());
        ActionAuthorityFacts revokedFacts = fixture.facts(
                fixture.approval, fixture.diff, fixture.ownerAssignment, fixture.binding,
                fixture.connection, revoked, fixture.policy, fixture.overlay, fixture.repository);
        ActionAuthorityFacts partiallyAuthorizedFacts = fixture.facts(
                fixture.approval, fixture.diff, fixture.ownerAssignment, fixture.binding,
                fixture.connection, partiallyAuthorized, fixture.policy, fixture.overlay,
                fixture.repository);

        assertFalse(bundle.isCurrent(releasedFacts, LATER));
        assertFalse(bundle.isCurrent(revokedFacts, LATER));
        assertFalse(bundle.isCurrent(partiallyAuthorizedFacts, LATER));
        assertThrows(StaleActionBundleException.class, () -> bundle.requireCurrent(revokedFacts, LATER));
    }

    @Test
    void requiresCompleteSourceWriteAndPullRequestCapabilitiesWhenPlanning() {
        Fixture fixture = new Fixture();
        ProviderAccessScope sourceWriteOnly = new ProviderAccessScope(
                ProviderCapabilities.of("source.write"),
                ProviderResourceScope.of("repository:101"));
        ProviderBinding partialBinding = fixture.bindingWithAccess(sourceWriteOnly, 0);
        ConnectionGrant partialGrant = ConnectionGrant.reconstitute(
                fixture.grant.id(),
                fixture.grant.organizationId(),
                fixture.grant.connectionId(),
                fixture.grant.connectionOwner(),
                fixture.grant.grantee(),
                sourceWriteOnly,
                fixture.grant.validFrom(),
                fixture.grant.expiresAt(),
                fixture.grant.status(),
                fixture.grant.terminalReason(),
                fixture.grant.version(),
                fixture.grant.audit());
        ActionAuthorityFacts partialFacts = fixture.facts(
                fixture.approval,
                fixture.diff,
                fixture.ownerAssignment,
                partialBinding,
                fixture.connection,
                partialGrant,
                fixture.policy,
                fixture.overlay,
                fixture.repository);

        assertThrows(DomainValidationException.class, () -> fixture.bundle(partialFacts));
    }

    @Test
    void failsClosedForBindingPolicyOverlayTargetAndExpiryDrift() {
        Fixture fixture = new Fixture();
        ActionBundle bundle = fixture.bundle();
        ProviderBinding changedBinding = fixture.bindingWithVersion(1);
        PolicySnapshot changedPolicy = fixture.policy();
        SafetyEnforcementOverlay changedOverlay = fixture.overlay.tighten(
                Set.of(SafetyRestriction.MODEL_DISABLED), Set.of(), Set.of(),
                fixture.owner, LATER);
        RepositoryBinding disabledRepository = fixture.repository.disable(
                fixture.repository.version(), fixture.owner, LATER);

        assertFalse(bundle.isCurrent(fixture.facts(
                fixture.approval, fixture.diff, fixture.ownerAssignment, changedBinding,
                fixture.connection, fixture.grant, fixture.policy, fixture.overlay,
                fixture.repository), LATER));
        StaleActionBundleException bindingDrift = assertThrows(
                StaleActionBundleException.class,
                () -> bundle.requireCurrent(fixture.facts(
                        fixture.approval, fixture.diff, fixture.ownerAssignment, changedBinding,
                        fixture.connection, fixture.grant, fixture.policy, fixture.overlay,
                        fixture.repository), LATER));
        assertEquals(ActionInvalidationReason.PROVIDER_AUTHORIZATION_CHANGED, bindingDrift.reason());
        assertFalse(bundle.isCurrent(fixture.facts(
                fixture.approval, fixture.diff, fixture.ownerAssignment, fixture.binding,
                fixture.connection, fixture.grant, changedPolicy, fixture.overlay,
                fixture.repository), LATER));
        assertFalse(bundle.isCurrent(fixture.facts(
                fixture.approval, fixture.diff, fixture.ownerAssignment, fixture.binding,
                fixture.connection, fixture.grant, fixture.policy, changedOverlay,
                fixture.repository), LATER));
        assertFalse(bundle.isCurrent(fixture.facts(
                fixture.approval, fixture.diff, fixture.ownerAssignment, fixture.binding,
                fixture.connection, fixture.grant, fixture.policy, fixture.overlay,
                disabledRepository), LATER));
        assertFalse(bundle.isCurrent(fixture.facts(), VALID_UNTIL));
    }

    @Test
    void rejectsUnknownForwardDuplicateAndSelfDependenciesAndTamperedHashes() {
        Fixture fixture = new Fixture();
        ActionBundle bundle = fixture.bundle();
        PlannedAction push = bundle.actions().get(0);
        CreateDraftPullRequestActionParameters pullRequest =
                (CreateDraftPullRequestActionParameters) bundle.actions().get(1).parameters();

        assertThrows(DomainValidationException.class, () ->
                new CreateDraftPullRequestActionParameters(
                        pullRequest.repositoryId(), pullRequest.base(), pullRequest.base(),
                        pullRequest.headSha(), pullRequest.title(), pullRequest.body(), true,
                        pullRequest.connectionId()));
        assertThrows(DomainValidationException.class, () -> PlannedAction.plan(
                push.id(), 1, push.parameters(), List.of(new ActionDependency(push.id())),
                push.authority(), push.risk(), push.validUntil()));
        assertThrows(DomainValidationException.class, () -> PlannedAction.plan(
                PlannedActionId.generate(), 1, push.parameters(),
                List.of(new ActionDependency(push.id()), new ActionDependency(push.id())),
                push.authority(), push.risk(), push.validUntil()));
        assertThrows(DomainValidationException.class, () -> PlannedAction.reconstitute(
                push.id(), push.sequence(), push.parameters(), push.dependencies(), push.authority(),
                push.risk(), push.validUntil(), new ActionDigest(TaskFactHash.sha256("tampered"))));
        assertThrows(DomainValidationException.class, () -> ActionBundle.reconstitute(
                bundle.id(), bundle.authority(), bundle.actions(), bundle.validUntil(),
                new ActionBundleDigest(TaskFactHash.sha256("tampered")), bundle.version(),
                bundle.audit()));
        assertThrows(DomainValidationException.class, () -> ActionBundle.reconstitute(
                bundle.id(), bundle.authority(), bundle.actions(), bundle.validUntil(),
                bundle.digest(), bundle.version(), AuditMetadata.createdBy(
                        fixture.owner.id(), UtcTimestamp.parse("2026-08-23T01:00:00Z"))));
    }

    private static ActionAuthoritySnapshot copyAuthority(
            ActionAuthoritySnapshot source, ActionTargetPrecondition target) {
        return new ActionAuthoritySnapshot(
                source.scope(), source.workItemId(), source.taskId(), source.taskExecutionId(),
                source.attempt(), source.reviewDecision(), source.diff(), source.responsibility(),
                source.providerAuthorization(), source.policy(), source.safetyOverlay(), target);
    }

    static final class Fixture {

        private final WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
                WorkProjectId.generate());
        final Principal owner = user("Owner");
        private final Principal codingAgent = agent("Coding Agent", PrincipalType.SPECIALIST_AGENT);
        final Principal reviewer = user("Gate Reviewer");
        private final TeamScope teamScope = new TeamScope(scope.organizationId(), scope.teamId());
        private final TeamMember ownerMember = TeamMember.join(
                TeamMemberId.generate(), teamScope, owner, TeamJoinMethod.BOOTSTRAP, CREATED_AT);
        private final TeamMember reviewerMember = TeamMember.join(
                TeamMemberId.generate(), teamScope, reviewer, TeamJoinMethod.OIDC, CREATED_AT);
        private final Team team = Team.reconstitute(
                scope.teamId(), scope.organizationId(), "Platform", ownerMember.id(),
                scope.workspaceId(), TeamStatus.ACTIVE, 0,
                AuditMetadata.createdBy(owner.id(), CREATED_AT));
        private final WorkItem workItem = WorkItem.reconstitute(
                WorkItemId.generate(), scope, new WorkItemKey("CRW-501"), "Deliver reviewed code",
                WorkItemStatus.READY, 1, AuditMetadata.createdBy(owner.id(), CREATED_AT));
        private final ResponsibilityAssignment ownerAssignment = assignment(
                ResponsibilityRole.OWNER, owner, Optional.of(ownerMember.id()));
        private final ResponsibilityAssignment executorAssignment = assignment(
                ResponsibilityRole.EXECUTOR, codingAgent, Optional.empty());
        private final ResponsibilityAssignment reviewerAssignment = assignment(
                ResponsibilityRole.REVIEWER, reviewer, Optional.of(reviewerMember.id()));
        private final List<ResponsibilityAssignment> assignments =
                List.of(ownerAssignment, executorAssignment, reviewerAssignment);
        private final Task initialTask = Task.create(
                TaskId.generate(), workItem, TaskSource.fromWorkItem(workItem),
                new TaskBrief("Deliver reviewed code", List.of("Code compiles")),
                TaskResponsibilitySnapshot.capture(workItem, assignments, CREATED_AT), owner, CREATED_AT);
        private final RepositoryBinding repository = RepositoryBinding.reconstitute(
                RepositoryBindingId.generate(),
                new RepositoryBindingScope(
                        scope.organizationId(), scope.teamId(), scope.workspaceId(), scope.projectId()),
                RepositoryKind.LOCAL_MANAGED, new RepositoryKey("crewscope-java"),
                new RepositoryBranchName("main"), RepositoryBindingStatus.ACTIVE, 0,
                AuditMetadata.createdBy(owner.id(), CREATED_AT));
        private final CodingTargetSnapshot codingTarget = CodingTargetSnapshot.initial(
                CodingTargetSnapshotId.generate(), initialTask, repository,
                new RepositoryBranchName("main"), new RepositoryCommitId("a".repeat(40)),
                CodingTargetAllowedPaths.of("src"),
                new BuildProfileReference("maven-java", 1, TaskFactHash.sha256("build-profile")),
                owner, CREATED_AT);
        private final TaskExecution execution = TaskExecution.firstAttempt(
                TaskExecutionId.generate(), initialTask, 3, TaskExecutionPriority.NORMAL,
                CREATED_AT, owner, CREATED_AT);
        private final Task task = initialTask.switchCurrentExecution(
                Optional.empty(), execution.id(), 0, owner, CREATED_AT);
        private final ReviewDiffReference diff = diff(
                new DiffArtifactReference(DiffArtifactId.generate(), TaskFactHash.sha256("diff")),
                new RepositoryCommitId("b".repeat(40)));
        private final ReviewSubject subject = ReviewSubject.codeChange(
                ReviewSubjectId.generate(), scope, task.id(), execution.id(), 1, diff,
                owner, CREATED_AT);
        private final ReviewTestEvidenceReference testEvidence = testEvidence();
        private final ReviewerExecutionReference reviewerExecution = reviewerExecution();
        private final ContextPackage context = ContextPackage.initial(
                ContextPackageId.generate(), subject, diff, testEvidence,
                List.of(ReviewDiffHunk.captured(
                        "src/Main.java", 1, 1, "+class Main {}\n")),
                reviewerExecution, owner, CREATED_AT);
        private final ReviewRequest request = ReviewRequest.initial(
                        ReviewRequestId.generate(), context, owner, CREATED_AT)
                .start(context, 0, owner, CREATED_AT)
                .complete(context, 1, owner, CREATED_AT);
        private final ReviewDecision approval = decision(ReviewDecisionType.APPROVED);
        private final ProviderOwner providerOwner = ProviderOwner.team(team);
        private final ProviderAccessScope access = new ProviderAccessScope(
                ProviderCapabilities.of("source.read", "source.write", "pull-request.create"),
                ProviderResourceScope.of("repository:101"));
        private final Connection connection = Connection.authorize(
                ConnectionId.generate(), providerOwner, "github", "installation:101",
                CredentialId.generate(), Optional.of(UtcTimestamp.parse("2026-08-24T02:00:00Z")),
                owner, CREATED_AT);
        private final ConnectionGrant grant = ConnectionGrant.grant(
                ConnectionGrantId.generate(), connection, providerOwner, access, CREATED_AT,
                Optional.of(UtcTimestamp.parse("2026-08-24T02:00:00Z")), owner, CREATED_AT);
        private final ProviderBindingId providerBindingId = ProviderBindingId.generate();
        private final ProviderDefinitionId providerDefinitionId = ProviderDefinitionId.generate();
        private final ProviderImplementationId providerImplementationId =
                ProviderImplementationId.generate();
        private final ProviderBinding binding = bindingWithVersion(0);
        private final PolicySnapshot policy = policy();
        private final SafetyEnforcementOverlay overlay = SafetyEnforcementOverlay.unrestricted(
                SafetyEnforcementOverlayId.generate(), task, execution, owner, CREATED_AT);
        private final ActionBundleId bundleId = ActionBundleId.generate();
        private final PlannedActionId pushId = PlannedActionId.generate();
        private final PlannedActionId pullRequestId = PlannedActionId.generate();

        ActionBundle bundle() {
            return bundle("Create reviewed delivery", VALID_UNTIL);
        }

        private ActionBundle bundle(String title, UtcTimestamp validUntil) {
            return ActionBundle.planSourceCodeDelivery(
                    bundleId, pushId, pullRequestId, facts(), new ExternalRepositoryId("101"),
                    new RepositoryBranchReference("refs/heads/crewscope/crw-501"),
                    Optional.of(new RepositoryCommitId("a".repeat(40))), title,
                    "Reviewed delivery for CRW-501", validUntil, owner, CREATED_AT);
        }

        private ActionBundle bundle(ActionAuthorityFacts facts) {
            return ActionBundle.planSourceCodeDelivery(
                    bundleId, pushId, pullRequestId, facts, new ExternalRepositoryId("101"),
                    new RepositoryBranchReference("refs/heads/crewscope/crw-501"), Optional.empty(),
                    "Create reviewed delivery", "Reviewed delivery for CRW-501",
                    VALID_UNTIL, owner, CREATED_AT);
        }

        ActionAuthorityFacts facts() {
            return facts(approval, diff);
        }

        private ActionAuthorityFacts facts(
                ReviewDecision reviewDecision, ReviewDiffReference reviewDiff) {
            return facts(reviewDecision, reviewDiff, ownerAssignment, binding, connection, grant,
                    policy, overlay, repository);
        }

        private ActionAuthorityFacts facts(
                ReviewDecision reviewDecision,
                ReviewDiffReference reviewDiff,
                ResponsibilityAssignment responsibility,
                ProviderBinding providerBinding,
                Connection currentConnection,
                ConnectionGrant currentGrant,
                PolicySnapshot currentPolicy,
                SafetyEnforcementOverlay currentOverlay,
                RepositoryBinding currentRepository) {
            return new ActionAuthorityFacts(
                    request, context, reviewDecision, reviewDiff, responsibility, providerBinding,
                    currentConnection, currentGrant, currentPolicy, currentOverlay, codingTarget,
                    currentRepository);
        }

        private ReviewDecision decision(ReviewDecisionType type) {
            return ReviewDecision.initial(
                    ReviewDecisionId.generate(), request, context, task, workItem, type,
                    "Gate conclusion", 2, ReviewerEligibilityPolicy.strict(), reviewer,
                    reviewerMember, List.of(ownerMember, reviewerMember), assignments, LATER);
        }

        private ResponsibilityAssignment assignment(
                ResponsibilityRole role, Principal principal, Optional<TeamMemberId> memberId) {
            return ResponsibilityAssignment.reconstitute(
                    ResponsibilityAssignmentId.generate(), scope, workItem.id(), role,
                    principal.id(), principal.type(), memberId,
                    ResponsibilityAssignmentStatus.ACTIVE, owner.id(), CREATED_AT, CREATED_AT,
                    Optional.empty(), Optional.empty(), 0,
                    AuditMetadata.createdBy(owner.id(), CREATED_AT));
        }

        private ReviewDiffReference diff(
                DiffArtifactReference artifact, RepositoryCommitId delivery) {
            String patch = "+class Main {}\n";
            return new ReviewDiffReference(
                    scope, task.id(), execution.id(), 1, artifact, codingTarget.reference(),
                    codingTarget.baselineCommit(), delivery, new DiffGeneration(1),
                    RuntimeContentHash.sha256("manifest"),
                    new PatchArtifactReference(
                            ArtifactId.generate(), patch.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                            RuntimeContentHash.sha256(patch)),
                    List.of(new DiffPath("src/Main.java")));
        }

        private ReviewTestEvidenceReference testEvidence() {
            CommandEvidenceReference command = new CommandEvidenceReference(
                    CommandEvidenceId.generate(), EvidenceSequence.first(),
                    TaskFactHash.sha256("test-command"), Optional.empty());
            ReviewCommandEvidenceReference reviewCommand = new ReviewCommandEvidenceReference(
                    command, CommandKind.TEST, CommandTermination.EXITED, Optional.of(0),
                    new EvidenceSummary("Tests passed"));
            AcceptanceResult acceptance = new AcceptanceResult(
                    1, "Code compiles", AcceptanceStatus.PASSED, List.of(command),
                    new EvidenceSummary("Compilation succeeded"));
            return new ReviewTestEvidenceReference(
                    scope, task.id(), execution.id(), 1, codingTarget.reference(),
                    TestEvidenceId.generate(), TaskFactHash.sha256("test-evidence"),
                    diff.generation(), diff.manifestHash(), List.of(reviewCommand),
                    List.of(acceptance));
        }

        private ReviewerExecutionReference reviewerExecution() {
            return new ReviewerExecutionReference(
                    scope, task.id(), execution.id(), AgentProfileId.generate(), 1,
                    PrincipalId.generate(), Optional.of(reviewerMember.id()),
                    Optional.of(ownerMember.id()), ReviewerRelationship.INDEPENDENT,
                    AgentTemplateVersion.of("reviewer", 1),
                    AgentTemplateHash.sha256("reviewer-template"),
                    new AgentConfigurationRevision(1),
                    new AgentConfigurationHash(TaskFactHash.sha256("reviewer-config").value()),
                    PolicySnapshotId.generate(), 1, TaskFactHash.sha256("reviewer-policy"));
        }

        private ProviderBinding bindingWithVersion(long version) {
            return bindingWithAccess(access, version);
        }

        private ProviderBinding bindingWithAccess(
                ProviderAccessScope effectiveAccess, long version) {
            return ProviderBinding.reconstitute(
                    providerBindingId, scope.organizationId(),
                    new ProviderBindingTarget(
                            scope.organizationId(), scope.teamId(), scope.workspaceId(),
                            ProviderBindingTargetType.WORK_PROJECT, Optional.of(scope.projectId())),
                    providerOwner, providerDefinitionId, 1, ProviderType.SOURCE_CODE,
                    providerImplementationId, 1, Optional.of(connection.id()),
                    Optional.of(connection.version()), Optional.of(grant.id()),
                    Optional.of(grant.version()), Optional.of(ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT),
                    effectiveAccess, true, ProviderRegistrationStatus.ACTIVE, version,
                    AuditMetadata.createdBy(owner.id(), CREATED_AT));
        }

        private PolicySnapshot policy() {
            return PolicySnapshot.initial(
                    PolicySnapshotId.generate(), task, execution, codingAgent,
                    new PolicyPackReference(PolicyPackId.generate(), 1), AgentProfileId.generate(), 1,
                    Set.of(ExecutionCapability.WORKTREE),
                    Set.of("repository.push", "pull-request.create-draft"),
                    Set.of(binding.id()), new PolicyBudget(10_000, 5, 5, 900),
                    owner, CREATED_AT);
        }

        private Principal user(String name) {
            return Principal.create(
                    PrincipalId.generate(), PrincipalScope.team(scope.organizationId(), scope.teamId()),
                    PrincipalType.USER, Optional.empty(), name, Optional.empty(),
                    PrincipalVisibility.TEAM, CREATED_AT);
        }

        private Principal agent(String name, PrincipalType type) {
            return Principal.create(
                    PrincipalId.generate(), PrincipalScope.team(scope.organizationId(), scope.teamId()),
                    type, Optional.of(owner.id()), name, Optional.empty(),
                    PrincipalVisibility.TEAM, CREATED_AT);
        }
    }
}
