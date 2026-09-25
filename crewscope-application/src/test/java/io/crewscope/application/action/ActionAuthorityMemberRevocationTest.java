package io.crewscope.application.action;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.action.CurrentActionAuthorityFactsResolver;
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
import io.crewscope.domain.action.ActionAuthoritySnapshot;
import io.crewscope.domain.action.ActionPolicyReference;
import io.crewscope.domain.action.ActionTargetPrecondition;
import io.crewscope.domain.action.ProviderAuthorizationReference;
import io.crewscope.domain.review.ReviewDecisionReference;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.action.ResponsibilityReference;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ContextPackageReference;
import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestReference;
import io.crewscope.domain.review.ReviewSubjectId;
import io.crewscope.domain.review.ReviewSubjectReference;
import io.crewscope.domain.review.ReviewSubjectType;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * M9b-A07: a suspended or removed OWNER membership must fail the Action authority resolution
 * closed, for both a USER executor and an AGENT executor's owning user, before any downstream
 * Provider fact is even read.
 */
class ActionAuthorityMemberRevocationTest {

    @Test
    void userOwnerRevocationFailsClosedAsUnavailable() {
        Harness harness = new Harness();
        harness.stubOwner(PrincipalType.USER);
        org.mockito.Mockito.doThrow(
                        new PolicyDeniedException("keep executing with this Team membership"))
                .when(harness.guard)
                .requireParticipation(
                        eq(harness.scope.organizationId()),
                        eq(harness.scope.teamId()),
                        eq(harness.principalId));

        DomainValidationException denied = assertThrows(
                DomainValidationException.class,
                () -> harness.resolver.resolveCurrent(harness.snapshot()));

        assertTrue(denied.getMessage().contains("actionDispatch.authority"));
        assertTrue(denied.getMessage().contains("current OWNER membership is unavailable"));
    }

    @Test
    void agentOwnerUserRevocationFailsClosedAsUnavailable() {
        Harness harness = new Harness();
        harness.stubOwner(PrincipalType.TEAM_AGENT);
        org.mockito.Mockito.doThrow(
                        new PolicyDeniedException("keep executing with this Team membership"))
                .when(harness.guard)
                .requireAgentOwnerParticipation(
                        eq(harness.scope.organizationId()),
                        eq(harness.scope.teamId()),
                        eq(harness.principalId));

        DomainValidationException denied = assertThrows(
                DomainValidationException.class,
                () -> harness.resolver.resolveCurrent(harness.snapshot()));

        assertTrue(denied.getMessage().contains("current OWNER membership is unavailable"));
        verify(harness.guard).requireAgentOwnerParticipation(
                harness.scope.organizationId(), harness.scope.teamId(), harness.principalId);
    }

    @Test
    void participatingOwnerKeepsTheOriginalFactOrder() {
        Harness harness = new Harness();
        harness.stubOwner(PrincipalType.USER);

        // The guard passes (default stub) and the next missing fact reports with its own label.
        DomainValidationException missing = assertThrows(
                DomainValidationException.class,
                () -> harness.resolver.resolveCurrent(harness.snapshot()));

        assertTrue(missing.getMessage().contains("current ProviderBinding is unavailable"));
        verify(harness.guard).requireParticipation(
                harness.scope.organizationId(), harness.scope.teamId(), harness.principalId);
    }

    private static final class Harness {

        final WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
                WorkProjectId.generate());
        final WorkItemId workItemId = WorkItemId.generate();
        final TaskId taskId = TaskId.generate();
        final TaskExecutionId executionId = TaskExecutionId.generate();
        final PrincipalId principalId = PrincipalId.generate();
        final ConnectionId connectionId = ConnectionId.generate();
        final ReviewRequestRepository reviewRequests = mock(ReviewRequestRepository.class);
        final ContextPackageRepository contexts = mock(ContextPackageRepository.class);
        final ReviewDecisionRepository decisions = mock(ReviewDecisionRepository.class);
        final ResponsibilityAssignmentRepository responsibilities =
                mock(ResponsibilityAssignmentRepository.class);
        final MemberAuthorizationGuard guard = mock(MemberAuthorizationGuard.class);
        final ActionAuthorityFactsResolver resolver = new CurrentActionAuthorityFactsResolver(
                reviewRequests,
                contexts,
                decisions,
                responsibilities,
                mock(ProviderBindingRepository.class),
                mock(ConnectionRepository.class),
                mock(ConnectionGrantRepository.class),
                mock(PolicySnapshotRepository.class),
                mock(SafetyEnforcementOverlayRepository.class),
                mock(CodingTargetSnapshotRepository.class),
                mock(RepositoryBindingRepository.class),
                guard);

        private Harness() {
            ReviewRequest request = mock(ReviewRequest.class);
            ContextPackageReference context = new ContextPackageReference(
                    ContextPackageId.generate(), 1, TaskFactHash.sha256("context"));
            when(request.contextPackage()).thenReturn(context);
            when(request.id()).thenReturn(ReviewRequestId.generate());
            when(reviewRequests.findCurrentByExecution(
                    scope.organizationId(), executionId, 1))
                    .thenReturn(Optional.of(request));
            when(contexts.findById(eq(scope.organizationId()), any()))
                    .thenReturn(Optional.of(mock(ContextPackage.class)));
            when(decisions.findLatestByRequest(eq(scope.organizationId()), any()))
                    .thenReturn(Optional.of(mock(ReviewDecision.class)));
        }

        private void stubOwner(PrincipalType actorType) {
            ResponsibilityAssignment owner = assignment(actorType, principalId);
            when(responsibilities.findActiveOwner(scope.organizationId(), workItemId))
                    .thenReturn(Optional.of(owner));
        }

        private ResponsibilityAssignment assignment(
                PrincipalType actorType, PrincipalId actorPrincipalId) {
            ResponsibilityAssignment assignment = mock(ResponsibilityAssignment.class);
            when(assignment.actorType()).thenReturn(actorType);
            when(assignment.actorPrincipalId()).thenReturn(actorPrincipalId);
            when(assignment.role()).thenReturn(ResponsibilityRole.OWNER);
            return assignment;
        }

        private ActionAuthoritySnapshot snapshot() {
            ReviewSubjectReference subject = new ReviewSubjectReference(
                    ReviewSubjectId.generate(), ReviewSubjectType.CODE_CHANGE,
                    TaskFactHash.sha256("subject"));
            ContextPackageReference context = new ContextPackageReference(
                    ContextPackageId.generate(), 1, TaskFactHash.sha256("context"));
            ReviewRequestReference request = new ReviewRequestReference(
                    scope, taskId, executionId, 1, ReviewRequestId.generate(), 1, 2,
                    subject, context, TaskFactHash.sha256("request"));
            CodingTargetSnapshotReference target = new CodingTargetSnapshotReference(
                    CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256("target"));
            String patch = "+class A07 {}\n";
            ReviewDiffReference diff = new ReviewDiffReference(
                    scope, taskId, executionId, 1,
                    new DiffArtifactReference(
                            DiffArtifactId.generate(), TaskFactHash.sha256("diff")),
                    target,
                    new RepositoryCommitId("a".repeat(40)),
                    new RepositoryCommitId("b".repeat(40)),
                    DiffGeneration.first(),
                    RuntimeContentHash.sha256("manifest"),
                    new PatchArtifactReference(
                            ArtifactId.generate(),
                            patch.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                            RuntimeContentHash.sha256(patch)),
                    List.of(new DiffPath("src/A07.java")));
            return new ActionAuthoritySnapshot(
                    scope,
                    workItemId,
                    taskId,
                    executionId,
                    1,
                    new ReviewDecisionReference(
                            ReviewDecisionId.generate(), 1, request,
                            ReviewDecisionType.APPROVED,
                            TaskFactHash.sha256("decision")),
                    diff,
                    new ResponsibilityReference(
                            ResponsibilityAssignmentId.generate(),
                            0, ResponsibilityRole.OWNER, principalId),
                    new ProviderAuthorizationReference(
                            ProviderBindingId.generate(), 0,
                            ProviderDefinitionId.generate(), 1,
                            ProviderImplementationId.generate(), 1,
                            ProviderType.SOURCE_CODE,
                            ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT,
                            connectionId, 0,
                            ConnectionGrantId.generate(), 0,
                            TaskFactHash.sha256("access")),
                    new ActionPolicyReference(
                            PolicySnapshotId.generate(), 1, TaskFactHash.sha256("policy")),
                    new SafetyEnforcementOverlayReference(
                            SafetyEnforcementOverlayId.generate(), 1,
                            TaskFactHash.sha256("safety")),
                    new ActionTargetPrecondition(
                            RepositoryBindingId.generate(), 0,
                            new RepositoryKey("crewscope-java"),
                            new RepositoryBranchName("main"),
                            target,
                            new RepositoryCommitId("a".repeat(40)),
                            new RepositoryCommitId("b".repeat(40))));
        }
    }
}
