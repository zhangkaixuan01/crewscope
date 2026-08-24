package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.action.ActionBundleView;
import io.crewscope.application.action.ActionDeliveryApplicationService;
import io.crewscope.application.review.ReviewRequestApplicationService;
import io.crewscope.application.review.ReviewRequestProjection;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentModelBindingSource;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.ResolvedModelSelection;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPlanningContext;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M5-A08 application evidence for safe joined summaries and continuous visibility checks. */
class TaskDeliverySummaryServiceM5A08Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
    private final WorkItemId workItemId = WorkItemId.generate();
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final TeamAccessContext access = mock(TeamAccessContext.class);
    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    private final PolicySnapshotRepository policies = mock(PolicySnapshotRepository.class);
    private final ReviewRequestApplicationService reviews =
            mock(ReviewRequestApplicationService.class);
    private final ActionDeliveryApplicationService actions =
            mock(ActionDeliveryApplicationService.class);
    private final Task task = mock(Task.class);
    private final TaskExecution execution = mock(TaskExecution.class);

    @BeforeEach
    void setUp() {
        when(task.id()).thenReturn(taskId);
        when(task.scope()).thenReturn(scope);
        when(task.workItemId()).thenReturn(workItemId);
        when(task.status()).thenReturn(TaskStatus.ACTIVE);
        when(task.currentExecutionId()).thenReturn(Optional.of(executionId));
        when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));
        when(execution.id()).thenReturn(executionId);
        when(execution.taskId()).thenReturn(taskId);
        when(execution.scope()).thenReturn(scope);
        when(execution.attempt()).thenReturn(2);
        when(executions.findById(organizationId, executionId)).thenReturn(Optional.of(execution));
        configureAgent();
        ReviewRequestProjection projection = reviewProjection();
        ActionBundleView action = actionView();
        when(reviews.list(access, organizationId, teamId, taskId, executionId))
                .thenReturn(List.of(projection));
        when(actions.list(access, organizationId, teamId, taskId, executionId))
                .thenReturn(List.of(action));
    }

    @Test
    void joinsAgentReviewActionAndGithubFactsThroughASensitiveFieldWhitelist() {
        TaskDeliverySummary summary = service().get(access, organizationId, teamId, taskId);

        assertEquals("coding", summary.agent().templateKey());
        assertEquals("deepseek", summary.agent().primaryModel().provider());
        assertEquals("deepseek-v4-flash", summary.agent().primaryModel().model());
        assertEquals("APPROVED", summary.review().gateDecision());
        assertEquals(3, summary.review().findingCount());
        assertEquals("repo", summary.action().repository());
        assertEquals("OPEN", summary.action().stages().get(1).externalStatus());
        assertEquals("b".repeat(64), summary.action().stages().get(1).externalIdentityHash());

        Set<String> publicFields = Arrays.stream(TaskDeliverySummary.class.getDeclaredClasses())
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(component -> component.getName().toLowerCase())
                .collect(Collectors.toSet());
        assertFalse(publicFields.stream().anyMatch(name -> name.contains("credential")
                || name.contains("connection")
                || name.contains("lease")
                || name.contains("fencing")
                || name.contains("worker")
                || name.contains("idempotency")
                || name.equals("externalid")));
    }

    @Test
    void rechecksVisibilityOnEveryReadAfterMembershipRevocation() {
        when(accessPolicy.requireVisibleWorkItem(
                        access,
                        organizationId,
                        teamId,
                        scope.projectId(),
                        workItemId))
                .thenReturn(mock(WorkItem.class))
                .thenThrow(new PolicyDeniedException("current Team membership required"));

        assertNotNull(service().get(access, organizationId, teamId, taskId));
        assertThrows(
                PolicyDeniedException.class,
                () -> service().get(access, organizationId, teamId, taskId));
    }

    @Test
    void enrichesAnAssociationPageInOneTransactionWithoutReloadingItsTasks() {
        TaskListItem projection = mock(TaskListItem.class);
        when(projection.id()).thenReturn(taskId);
        when(projection.scope()).thenReturn(scope);
        when(projection.workItemId()).thenReturn(workItemId);
        when(projection.status()).thenReturn(TaskStatus.ACTIVE);
        when(projection.currentExecutionId()).thenReturn(Optional.of(executionId));
        TaskAssociationItem item = mock(TaskAssociationItem.class);
        when(item.task()).thenReturn(projection);
        TaskAssociationPage page = mock(TaskAssociationPage.class);
        when(page.items()).thenReturn(List.of(item));
        AtomicInteger transactionCount = new AtomicInteger();
        TransactionExecutor executor = new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                transactionCount.incrementAndGet();
                return operation.get();
            }
        };

        List<TaskDeliverySummary> summaries = service(executor)
                .summarizePage(access, organizationId, teamId, page);

        assertEquals(1, summaries.size());
        assertEquals(1, transactionCount.get());
        verifyNoInteractions(tasks);
    }

    private void configureAgent() {
        PolicySnapshotId policyId = PolicySnapshotId.generate();
        TaskExecutionPlanningContext planning = mock(TaskExecutionPlanningContext.class);
        when(planning.policySnapshotId()).thenReturn(policyId);
        when(execution.planningContext()).thenReturn(Optional.of(planning));
        PolicySnapshot policy = mock(PolicySnapshot.class);
        ResolvedAgentExecutionConfiguration configuration =
                mock(ResolvedAgentExecutionConfiguration.class);
        ResolvedModelSelection model = mock(ResolvedModelSelection.class);
        when(policy.agentExecutionConfiguration()).thenReturn(Optional.of(configuration));
        when(policies.findById(organizationId, policyId)).thenReturn(Optional.of(policy));
        when(configuration.agentProfileId())
                .thenReturn(io.crewscope.domain.workspace.AgentProfileId.generate());
        when(configuration.templateVersion()).thenReturn(AgentTemplateVersion.of("coding", 3));
        when(configuration.configurationRevision()).thenReturn(new AgentConfigurationRevision(7));
        when(configuration.executionScope()).thenReturn(AgentExecutionScope.PERSONAL);
        when(configuration.bindingSource()).thenReturn(AgentModelBindingSource.DIRECT);
        when(configuration.primary()).thenReturn(model);
        when(configuration.fallback()).thenReturn(Optional.empty());
        when(model.providerKey()).thenReturn(new ModelProviderKey("deepseek"));
        when(model.catalogCoordinate()).thenReturn(new ModelCatalogCoordinate(
                ModelCatalogEntryId.generate(),
                new ModelProviderKey("deepseek"),
                new ModelId("deepseek-v4-flash"),
                new ModelCatalogRevision(5)));
    }

    private ReviewRequestProjection reviewProjection() {
        ReviewRequestProjection value = mock(ReviewRequestProjection.class);
        when(value.reviewRequestId()).thenReturn(ReviewRequestId.generate());
        when(value.requestRevision()).thenReturn(2L);
        when(value.status()).thenReturn(ReviewRequestStatus.COMPLETED);
        when(value.findingCount()).thenReturn(3);
        when(value.blockerCount()).thenReturn(1);
        when(value.highCount()).thenReturn(1);
        when(value.latestDecisionType()).thenReturn(Optional.of(ReviewDecisionType.APPROVED));
        when(value.modificationRound()).thenReturn(1L);
        return value;
    }

    private ActionBundleView actionView() {
        return new ActionBundleView(
                "bundle",
                1,
                "a".repeat(64),
                "CURRENT",
                null,
                taskId.toString(),
                executionId.toString(),
                "decision",
                "binding",
                "repo",
                "1".repeat(40),
                "2".repeat(40),
                new ActionBundleView.ConfirmationView(
                        "confirmation", 0, "ACTIVE", "member", "now", "later", null),
                List.of(
                        new ActionBundleView.PlannedActionView(
                                "push", 1, "PUSH_BRANCH", "EXTERNAL_WRITE", "c".repeat(64),
                                "later", List.of(), null,
                                new ActionBundleView.DispatchView(
                                        "dispatch-1", 1, "SUCCEEDED", 1, 0, "now", null,
                                        "NOT_REQUIRED"),
                                null,
                                null),
                        new ActionBundleView.PlannedActionView(
                                "pr", 2, "CREATE_DRAFT_PULL_REQUEST", "EXTERNAL_WRITE",
                                "d".repeat(64), "later", List.of("push"), null,
                                new ActionBundleView.DispatchView(
                                        "dispatch-2", 1, "SUCCEEDED", 1, 0, "now", null,
                                        "NOT_REQUIRED"),
                                null,
                                new ActionBundleView.ExternalResultView(
                                        "OPEN", "PULL_REQUEST", "b".repeat(64), 1L,
                                        "now", "WRITE_RESPONSE", "now", 0))));
    }

    private TaskDeliverySummaryService service() {
        return service(new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        });
    }

    private TaskDeliverySummaryService service(TransactionExecutor transactionExecutor) {
        return new TaskDeliverySummaryService(
                accessPolicy,
                tasks,
                executions,
                policies,
                reviews,
                actions,
                transactionExecutor);
    }
}
