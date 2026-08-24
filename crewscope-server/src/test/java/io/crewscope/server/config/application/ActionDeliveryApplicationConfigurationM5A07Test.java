package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.action.ActionAuthorityFactsResolver;
import io.crewscope.application.action.ActionBundleRepository;
import io.crewscope.application.action.ActionCommandEventPublisher;
import io.crewscope.application.action.ActionDeliveryApplicationService;
import io.crewscope.application.action.ActionDeliveryPlanningResolver;
import io.crewscope.application.action.ActionDispatchRepository;
import io.crewscope.application.action.ActionManualResolutionService;
import io.crewscope.application.action.ActionReceiptRepository;
import io.crewscope.application.action.ActionWorkerEventPublisher;
import io.crewscope.application.action.ConfirmationRepository;
import io.crewscope.application.action.ExternalResultRepository;
import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.review.ContextPackageRepository;
import io.crewscope.application.review.ReviewDecisionRepository;
import io.crewscope.application.review.ReviewRequestRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** M5-A07 explicit composition proof for planning, event and application boundaries. */
class ActionDeliveryApplicationConfigurationM5A07Test {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ActionDeliveryApplicationConfiguration.class)
            .withBean(ReviewRequestRepository.class, () -> mock(ReviewRequestRepository.class))
            .withBean(ContextPackageRepository.class, () -> mock(ContextPackageRepository.class))
            .withBean(ReviewDecisionRepository.class, () -> mock(ReviewDecisionRepository.class))
            .withBean(ResponsibilityAssignmentRepository.class,
                    () -> mock(ResponsibilityAssignmentRepository.class))
            .withBean(ProviderBindingRepository.class, () -> mock(ProviderBindingRepository.class))
            .withBean(ConnectionRepository.class, () -> mock(ConnectionRepository.class))
            .withBean(ConnectionGrantRepository.class,
                    () -> mock(ConnectionGrantRepository.class))
            .withBean(PolicySnapshotRepository.class, () -> mock(PolicySnapshotRepository.class))
            .withBean(SafetyEnforcementOverlayRepository.class,
                    () -> mock(SafetyEnforcementOverlayRepository.class))
            .withBean(CodingTargetSnapshotRepository.class,
                    () -> mock(CodingTargetSnapshotRepository.class))
            .withBean(RepositoryBindingRepository.class,
                    () -> mock(RepositoryBindingRepository.class))
            .withBean(ExecutionWorkspaceRepository.class,
                    () -> mock(ExecutionWorkspaceRepository.class))
            .withBean(GitHubProviderRepository.class, () -> mock(GitHubProviderRepository.class))
            .withBean(TimeProvider.class, () -> mock(TimeProvider.class))
            .withBean(DomainEventStore.class, () -> mock(DomainEventStore.class))
            .withBean(TaskEventRepository.class, () -> mock(TaskEventRepository.class))
            .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
            .withBean(WorkItemAccessPolicy.class, () -> mock(WorkItemAccessPolicy.class))
            .withBean(TaskRepository.class, () -> mock(TaskRepository.class))
            .withBean(TaskExecutionRepository.class, () -> mock(TaskExecutionRepository.class))
            .withBean(ActionAuthorityFactsResolver.class,
                    () -> mock(ActionAuthorityFactsResolver.class))
            .withBean(ActionBundleRepository.class, () -> mock(ActionBundleRepository.class))
            .withBean(ConfirmationRepository.class, () -> mock(ConfirmationRepository.class))
            .withBean(ActionDispatchRepository.class, () -> mock(ActionDispatchRepository.class))
            .withBean(ActionReceiptRepository.class, () -> mock(ActionReceiptRepository.class))
            .withBean(ExternalResultRepository.class, () -> mock(ExternalResultRepository.class))
            .withBean(ActionManualResolutionService.class,
                    () -> mock(ActionManualResolutionService.class))
            .withBean(ActionWorkerEventPublisher.class,
                    () -> mock(ActionWorkerEventPublisher.class))
            .withBean(CommandReceiptStore.class, () -> mock(CommandReceiptStore.class))
            .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class));

    @Test
    void wiresPlanningEventAndApplicationServicesWhenAllRequiredPortsExist() {
        runner.run(context -> context.assertThat()
                .hasNotFailed()
                .hasSingleBean(ActionDeliveryPlanningResolver.class)
                .hasSingleBean(ActionCommandEventPublisher.class)
                .hasSingleBean(ActionDeliveryApplicationService.class));
    }
}
