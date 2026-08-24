package io.crewscope.application.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.github.GitHubRepositoryCatalogEntry;
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
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.ManagedWorkspaceBranch;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ContextPackageReference;
import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M5-A07 proof that stable GitHub Repository IDs remain bound to exact Grant resources. */
class CurrentActionDeliveryPlanningResolverM5A07Test {

    @Test
    void mapsStableCatalogIdToTheExactAuthorizedFullNameResource() {
        Fixture fixture = new Fixture("github:repository:crewscope/crewscope-java");

        ActionDeliveryPlanningFacts result = fixture.resolver().resolve(
                fixture.organizationId,
                fixture.teamId,
                fixture.taskId,
                fixture.executionId,
                fixture.decisionId,
                fixture.bindingId,
                new ExternalRepositoryId("101"));

        assertEquals(
                "github:repository:crewscope/crewscope-java",
                result.providerResourceKey());
        assertEquals(
                "refs/heads/crewscope/tasks/00000000-0000-0000-0000-000000000001/attempt-1",
                result.deliveryBranch().value());
    }

    @Test
    void rejectsCatalogIdWhenTheBindingOnlyGrantsAnotherRepository() {
        Fixture fixture = new Fixture("github:repository:crewscope/another-repository");

        assertThrows(DomainValidationException.class, () -> fixture.resolver().resolve(
                fixture.organizationId,
                fixture.teamId,
                fixture.taskId,
                fixture.executionId,
                fixture.decisionId,
                fixture.bindingId,
                new ExternalRepositoryId("101")));
    }

    private static final class Fixture {
        private final UtcTimestamp now = UtcTimestamp.parse("2026-08-24T14:00:00Z");
        private final OrganizationId organizationId = OrganizationId.generate();
        private final TeamId teamId = TeamId.generate();
        private final WorkItemScope scope = new WorkItemScope(
                organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final ReviewDecisionId decisionId = ReviewDecisionId.generate();
        private final ProviderBindingId bindingId = ProviderBindingId.generate();
        private final ConnectionId connectionId = ConnectionId.generate();
        private final ConnectionGrantId grantId = ConnectionGrantId.generate();
        private final RepositoryBindingId repositoryBindingId = RepositoryBindingId.generate();
        private final WorkItemId workItemId = WorkItemId.generate();
        private final ReviewRequestRepository requests = mock(ReviewRequestRepository.class);
        private final ContextPackageRepository contexts = mock(ContextPackageRepository.class);
        private final ReviewDecisionRepository decisions = mock(ReviewDecisionRepository.class);
        private final ResponsibilityAssignmentRepository responsibilities =
                mock(ResponsibilityAssignmentRepository.class);
        private final ProviderBindingRepository bindings = mock(ProviderBindingRepository.class);
        private final ConnectionRepository connections = mock(ConnectionRepository.class);
        private final ConnectionGrantRepository grants = mock(ConnectionGrantRepository.class);
        private final PolicySnapshotRepository policies = mock(PolicySnapshotRepository.class);
        private final SafetyEnforcementOverlayRepository overlays =
                mock(SafetyEnforcementOverlayRepository.class);
        private final CodingTargetSnapshotRepository targets =
                mock(CodingTargetSnapshotRepository.class);
        private final RepositoryBindingRepository repositories =
                mock(RepositoryBindingRepository.class);
        private final ExecutionWorkspaceRepository workspaces =
                mock(ExecutionWorkspaceRepository.class);
        private final GitHubProviderRepository github = mock(GitHubProviderRepository.class);

        private Fixture(String grantedResource) {
            ReviewRequestId requestId = ReviewRequestId.generate();
            ContextPackageId contextId = ContextPackageId.generate();
            ReviewRequestReference requestReference = mock(ReviewRequestReference.class);
            when(requestReference.taskExecutionId()).thenReturn(executionId);
            when(requestReference.attempt()).thenReturn(1);
            when(requestReference.id()).thenReturn(requestId);
            ReviewDecision decision = mock(ReviewDecision.class);
            when(decision.id()).thenReturn(decisionId);
            when(decision.taskId()).thenReturn(taskId);
            when(decision.workItemId()).thenReturn(workItemId);
            when(decision.reviewRequest()).thenReturn(requestReference);
            when(decisions.findById(organizationId, decisionId)).thenReturn(Optional.of(decision));
            ReviewRequest request = mock(ReviewRequest.class);
            when(request.id()).thenReturn(requestId);
            when(request.scope()).thenReturn(scope);
            when(request.attempt()).thenReturn(1);
            when(request.diff()).thenReturn(mock(ReviewDiffReference.class));
            ContextPackageReference contextReference = mock(ContextPackageReference.class);
            when(contextReference.id()).thenReturn(contextId);
            when(request.contextPackage()).thenReturn(contextReference);
            when(requests.findCurrentByExecution(organizationId, executionId, 1))
                    .thenReturn(Optional.of(request));
            when(decisions.findLatestByRequest(organizationId, requestId))
                    .thenReturn(Optional.of(decision));
            when(contexts.findById(organizationId, contextId))
                    .thenReturn(Optional.of(mock(ContextPackage.class)));
            when(responsibilities.findActiveOwner(organizationId, workItemId))
                    .thenReturn(Optional.of(mock(ResponsibilityAssignment.class)));
            ProviderBinding binding = mock(ProviderBinding.class);
            when(binding.connectionId()).thenReturn(Optional.of(connectionId));
            when(binding.connectionGrantId()).thenReturn(Optional.of(grantId));
            when(binding.executionIdentity())
                    .thenReturn(Optional.of(ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT));
            when(binding.effectiveAccess()).thenReturn(new ProviderAccessScope(
                    ProviderCapabilities.of("source.write", "pull-request.create"),
                    ProviderResourceScope.of(grantedResource)));
            when(bindings.findById(organizationId, bindingId)).thenReturn(Optional.of(binding));
            Connection connection = mock(Connection.class);
            when(connection.id()).thenReturn(connectionId);
            when(connection.version()).thenReturn(0L);
            when(connections.findById(organizationId, connectionId))
                    .thenReturn(Optional.of(connection));
            when(grants.findById(organizationId, grantId))
                    .thenReturn(Optional.of(mock(ConnectionGrant.class)));
            when(policies.findByExecution(organizationId, executionId))
                    .thenReturn(List.of(mock(PolicySnapshot.class)));
            when(overlays.findByExecution(organizationId, executionId))
                    .thenReturn(List.of(mock(SafetyEnforcementOverlay.class)));
            CodingTargetSnapshot target = mock(CodingTargetSnapshot.class);
            when(target.repositoryBindingId()).thenReturn(repositoryBindingId);
            when(targets.findLatestByTask(organizationId, teamId, scope.projectId(), taskId))
                    .thenReturn(Optional.of(target));
            when(repositories.findById(
                            organizationId, teamId, scope.projectId(), repositoryBindingId))
                    .thenReturn(Optional.of(mock(RepositoryBinding.class)));
            ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
            when(workspace.attempt()).thenReturn(1);
            when(workspace.status()).thenReturn(ExecutionWorkspaceStatus.COMPLETED);
            when(workspace.managedBranch()).thenReturn(new ManagedWorkspaceBranch(
                    "crewscope/tasks/00000000-0000-0000-0000-000000000001/attempt-1"));
            when(workspaces.findByTaskExecution(
                            organizationId, teamId, scope.projectId(), executionId))
                    .thenReturn(Optional.of(workspace));
            GitHubRepositoryCatalogEntry catalog = mock(GitHubRepositoryCatalogEntry.class);
            when(catalog.connectionVersion()).thenReturn(0L);
            when(catalog.externalIdentity())
                    .thenReturn(ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT);
            when(catalog.status()).thenReturn(GitHubRepositoryStatus.DELIVERABLE);
            when(catalog.isCurrentAt(now)).thenReturn(true);
            when(catalog.grantResourceKey())
                    .thenReturn("github:repository:crewscope/crewscope-java");
            when(github.findRepository(organizationId, connectionId, "101"))
                    .thenReturn(Optional.of(catalog));
        }

        private CurrentActionDeliveryPlanningResolver resolver() {
            return new CurrentActionDeliveryPlanningResolver(
                    requests,
                    contexts,
                    decisions,
                    responsibilities,
                    bindings,
                    connections,
                    grants,
                    policies,
                    overlays,
                    targets,
                    repositories,
                    workspaces,
                    github,
                    () -> now);
        }
    }
}
