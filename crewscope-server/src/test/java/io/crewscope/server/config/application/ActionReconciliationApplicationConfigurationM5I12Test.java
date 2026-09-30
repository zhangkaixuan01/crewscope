package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.action.ActionAuthorityFactsResolver;
import io.crewscope.application.action.ActionBundleRepository;
import io.crewscope.application.action.ActionDispatchRepository;
import io.crewscope.application.action.ActionManualResolutionService;
import io.crewscope.application.action.ActionReceiptRepository;
import io.crewscope.application.action.ActionReconciliationWorker;
import io.crewscope.application.action.ActionWorkerEventPublisher;
import io.crewscope.application.action.ConfirmationRepository;
import io.crewscope.application.action.ExternalObservationRepository;
import io.crewscope.application.action.ExternalResultRepository;
import io.crewscope.application.action.GitHubRepositoryPolicyResolver;
import io.crewscope.application.github.GitHubDraftPullRequestPort;
import io.crewscope.application.github.GitHubPushPort;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.server.observability.ActionReconciliationMetricsObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Conditional composition and bounded setting proof for the M5-I12 reconciliation fleet. */
class ActionReconciliationApplicationConfigurationM5I12Test {

    @Test
    void completeQueryBoundariesWireWorkerSchedulerStartupAndManualResolution() {
        runner(true).run(context -> context.assertThat()
                .hasNotFailed()
                .hasSingleBean(ActionReconciliationWorker.class)
                .hasSingleBean(ActionReconciliationScheduler.class)
                .hasSingleBean(ActionReconciliationStartupRunner.class)
                .hasSingleBean(ActionManualResolutionService.class));

        runner(false).run(context -> context.assertThat()
                .hasNotFailed()
                .doesNotHaveBean(ActionReconciliationWorker.class)
                .doesNotHaveBean(ActionReconciliationScheduler.class)
                .doesNotHaveBean(ActionReconciliationStartupRunner.class)
                .hasSingleBean(ActionManualResolutionService.class));
    }

    @Test
    void wiresTheFleetWhenTheGitHubWritePortsRegisterAfterThisConfiguration() {
        // Same registration-order proof as ActionWorkerApplicationConfigurationM5I11Test:
        // production defines the GitHub write ports in a later-scanned configuration class,
        // and presence conditions evaluated at registration time cannot see them (M9b-Q02
        // defect 27 silently dropped this fleet from real deployments the same way).
        baseRunner()
                .withUserConfiguration(LateGitHubWriteBoundaries.class)
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(ActionReconciliationWorker.class)
                        .hasSingleBean(ActionReconciliationScheduler.class)
                        .hasSingleBean(ActionReconciliationStartupRunner.class));
    }

    @Test
    void failsFastWhenAWorkerCapableCompositionLacksTheGitHubWritePorts() {
        baseRunner().run(context -> context.assertThat().hasFailed());
    }

    @Test
    void observerResolvesMetricsAtInjectionTimeAndFallsBackToNoOpOtherwise() {
        // M9b-Q02 defect 4: MeterRegistry and Tracer are auto-configured beans registering
        // after every user configuration class, so the former presence condition always
        // evaluated false and the fleet ran on the no-op observer even with actuator present.
        runner(true).run(context -> {
            context.assertThat().hasNotFailed();
            context.assertThat().doesNotHaveBean(ActionReconciliationMetricsObserver.class);
        });
        runner(true)
                .withBean(MeterRegistry.class, () -> mock(MeterRegistry.class))
                .withBean(Tracer.class, () -> mock(Tracer.class))
                .run(context -> {
                    context.assertThat().hasNotFailed();
                    context.assertThat().hasSingleBean(ActionReconciliationMetricsObserver.class);
                });
    }

    @Test
    void schedulerAndColdStartCanBeDisabledIndependentlyFromTheWorker() {
        runner(true)
                .withPropertyValues("crewscope.action.reconciliation.startup-enabled=false")
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(ActionReconciliationWorker.class)
                        .hasSingleBean(ActionReconciliationScheduler.class)
                        .doesNotHaveBean(ActionReconciliationStartupRunner.class));
        runner(true)
                .withPropertyValues("crewscope.action.reconciliation.enabled=false")
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(ActionReconciliationWorker.class)
                        .doesNotHaveBean(ActionReconciliationScheduler.class)
                        .doesNotHaveBean(ActionReconciliationStartupRunner.class));
    }

    @Test
    void rejectsTimingAttemptIdentityAndBatchValuesOutsideTheirBounds() {
        invalid("crewscope.action.reconciliation.poll-interval=10ms");
        invalid("crewscope.action.reconciliation.lease-duration=6m");
        invalid("crewscope.action.reconciliation.retry-delay=0s");
        invalid("crewscope.action.reconciliation.maximum-unknown-age=30s");
        invalid("crewscope.action.reconciliation.maximum-attempts=101");
        invalid("crewscope.action.reconciliation.batch-size=0");
        invalid("crewscope.action.reconciliation.worker-id=   ");
    }

    private void invalid(String property) {
        runner(true).withPropertyValues(property)
                .run(context -> context.assertThat().hasFailed());
    }

    private ApplicationContextRunner runner(boolean workerCapableProfile) {
        ApplicationContextRunner runner = baseRunner()
                .withBean(GitHubPushPort.class, () -> mock(GitHubPushPort.class))
                .withBean(GitHubDraftPullRequestPort.class,
                        () -> mock(GitHubDraftPullRequestPort.class));
        if (workerCapableProfile) {
            return runner;
        }
        return runner.withPropertyValues("crewscope.runtime.execution-profile=api");
    }

    private ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ActionReconciliationApplicationConfiguration.class)
                .withBean(ActionDispatchRepository.class,
                        () -> mock(ActionDispatchRepository.class))
                .withBean(ActionReceiptRepository.class,
                        () -> mock(ActionReceiptRepository.class))
                .withBean(ActionBundleRepository.class,
                        () -> mock(ActionBundleRepository.class))
                .withBean(ConfirmationRepository.class,
                        () -> mock(ConfirmationRepository.class))
                .withBean(ExternalObservationRepository.class,
                        () -> mock(ExternalObservationRepository.class))
                .withBean(ExternalResultRepository.class,
                        () -> mock(ExternalResultRepository.class))
                .withBean(ActionAuthorityFactsResolver.class,
                        () -> mock(ActionAuthorityFactsResolver.class))
                .withBean(GitHubRepositoryPolicyResolver.class,
                        () -> mock(GitHubRepositoryPolicyResolver.class))
                .withBean(ActionWorkerEventPublisher.class,
                        () -> mock(ActionWorkerEventPublisher.class))
                .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
                .withBean(TimeProvider.class, () -> mock(TimeProvider.class));
    }

    /** Stand-in for GitHubProviderApplicationConfiguration: registers after this config. */
    @Configuration(proxyBeanMethods = false)
    static class LateGitHubWriteBoundaries {

        @Bean
        GitHubPushPort gitHubPushPort() {
            return mock(GitHubPushPort.class);
        }

        @Bean
        GitHubDraftPullRequestPort gitHubDraftPullRequestPort() {
            return mock(GitHubDraftPullRequestPort.class);
        }
    }
}
