package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.action.ActionAuthorityFactsResolver;
import io.crewscope.application.action.ActionBundleRepository;
import io.crewscope.application.action.ActionDispatchRepository;
import io.crewscope.application.action.ActionReceiptRepository;
import io.crewscope.application.action.ActionWorker;
import io.crewscope.application.action.ActionWorkerEventPublisher;
import io.crewscope.application.action.ConfirmationRepository;
import io.crewscope.application.action.GitHubRepositoryPolicyResolver;
import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.github.GitHubDraftPullRequestPort;
import io.crewscope.application.github.GitHubPushPort;
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
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Conditional Spring composition and bounded configuration proof for the M5-I11 Worker. */
class ActionWorkerApplicationConfigurationM5I11Test {

    @Test
    void wiresWorkerAndSchedulerOnlyWhenBothGitHubWriteBoundariesExist() {
        runner(true).run(context -> context.assertThat()
                .hasNotFailed()
                .hasSingleBean(ActionWorker.class)
                .hasSingleBean(ActionWorkerScheduler.class));

        runner(false).run(context -> context.assertThat()
                .hasNotFailed()
                .doesNotHaveBean(ActionWorker.class)
                .doesNotHaveBean(ActionWorkerScheduler.class));
    }

    @Test
    void composesRepositoryBackedAuthorityPolicyAndDurableEvents() {
        compositionRunner().run(context -> context.assertThat()
                .hasNotFailed()
                .hasSingleBean(ActionAuthorityFactsResolver.class)
                .hasSingleBean(GitHubRepositoryPolicyResolver.class)
                .hasSingleBean(ActionWorkerEventPublisher.class)
                .hasSingleBean(ActionWorker.class)
                .hasSingleBean(ActionWorkerScheduler.class));
    }

    @Test
    void disablingPollingPreservesTheManuallyInvocableWorker() {
        runner(true)
                .withPropertyValues("crewscope.action.worker.enabled=false")
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(ActionWorker.class)
                        .doesNotHaveBean(ActionWorkerScheduler.class));
    }

    @Test
    void rejectsWorkerIdentityTimingAndBatchValuesOutsideTheirBounds() {
        runner(true)
                .withPropertyValues("crewscope.action.worker.poll-interval=10ms")
                .run(context -> context.assertThat().hasFailed());
        runner(true)
                .withPropertyValues("crewscope.action.worker.lease-duration=6m")
                .run(context -> context.assertThat().hasFailed());
        runner(true)
                .withPropertyValues("crewscope.action.worker.retry-delay=0s")
                .run(context -> context.assertThat().hasFailed());
        runner(true)
                .withPropertyValues("crewscope.action.worker.batch-size=101")
                .run(context -> context.assertThat().hasFailed());
        runner(true)
                .withPropertyValues("crewscope.action.worker.worker-id=   ")
                .run(context -> context.assertThat().hasFailed());
    }

    private ApplicationContextRunner runner(boolean includeGitHubWrites) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ActionWorkerApplicationConfiguration.class)
                .withBean(GitHubProviderProperties.class, GitHubProviderProperties::new)
                .withBean(ActionDispatchRepository.class, () -> mock(ActionDispatchRepository.class))
                .withBean(ActionReceiptRepository.class, () -> mock(ActionReceiptRepository.class))
                .withBean(ActionBundleRepository.class, () -> mock(ActionBundleRepository.class))
                .withBean(ConfirmationRepository.class, () -> mock(ConfirmationRepository.class))
                .withBean(ActionAuthorityFactsResolver.class,
                        () -> mock(ActionAuthorityFactsResolver.class))
                .withBean(GitHubRepositoryPolicyResolver.class,
                        () -> mock(GitHubRepositoryPolicyResolver.class))
                .withBean(ActionWorkerEventPublisher.class,
                        () -> mock(ActionWorkerEventPublisher.class))
                .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
                .withBean(TimeProvider.class, () -> mock(TimeProvider.class));
        if (!includeGitHubWrites) {
            return runner;
        }
        return runner
                .withBean(GitHubPushPort.class, () -> mock(GitHubPushPort.class))
                .withBean(GitHubDraftPullRequestPort.class,
                        () -> mock(GitHubDraftPullRequestPort.class));
    }

    private ApplicationContextRunner compositionRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ActionWorkerApplicationConfiguration.class)
                .withBean(GitHubProviderProperties.class, GitHubProviderProperties::new)
                .withBean(ReviewRequestRepository.class, () -> mock(ReviewRequestRepository.class))
                .withBean(ContextPackageRepository.class, () -> mock(ContextPackageRepository.class))
                .withBean(ReviewDecisionRepository.class, () -> mock(ReviewDecisionRepository.class))
                .withBean(ResponsibilityAssignmentRepository.class,
                        () -> mock(ResponsibilityAssignmentRepository.class))
                .withBean(ProviderBindingRepository.class,
                        () -> mock(ProviderBindingRepository.class))
                .withBean(ConnectionRepository.class, () -> mock(ConnectionRepository.class))
                .withBean(ConnectionGrantRepository.class,
                        () -> mock(ConnectionGrantRepository.class))
                .withBean(PolicySnapshotRepository.class,
                        () -> mock(PolicySnapshotRepository.class))
                .withBean(SafetyEnforcementOverlayRepository.class,
                        () -> mock(SafetyEnforcementOverlayRepository.class))
                .withBean(CodingTargetSnapshotRepository.class,
                        () -> mock(CodingTargetSnapshotRepository.class))
                .withBean(RepositoryBindingRepository.class,
                        () -> mock(RepositoryBindingRepository.class))
                .withBean(DomainEventStore.class, () -> mock(DomainEventStore.class))
                .withBean(TaskEventRepository.class, () -> mock(TaskEventRepository.class))
                .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
                .withBean(ActionDispatchRepository.class, () -> mock(ActionDispatchRepository.class))
                .withBean(ActionReceiptRepository.class, () -> mock(ActionReceiptRepository.class))
                .withBean(ActionBundleRepository.class, () -> mock(ActionBundleRepository.class))
                .withBean(ConfirmationRepository.class, () -> mock(ConfirmationRepository.class))
                .withBean(GitHubPushPort.class, () -> mock(GitHubPushPort.class))
                .withBean(GitHubDraftPullRequestPort.class,
                        () -> mock(GitHubDraftPullRequestPort.class))
                .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
                .withBean(TimeProvider.class, () -> mock(TimeProvider.class));
    }
}
