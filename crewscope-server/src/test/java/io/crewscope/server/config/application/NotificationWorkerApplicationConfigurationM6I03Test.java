package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.notification.NotificationAuthorizationFactsResolver;
import io.crewscope.application.notification.NotificationCredentialIssuer;
import io.crewscope.application.notification.NotificationDispatchRepository;
import io.crewscope.application.notification.NotificationProviderPort;
import io.crewscope.application.notification.NotificationReconciliationWorker;
import io.crewscope.application.notification.NotificationWorker;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Conditional composition and bounded settings for the M6-I03 notification runtime. */
class NotificationWorkerApplicationConfigurationM6I03Test {

    @Test
    void composesIndependentWriteAndQuerySchedulersWhenAllPortsExist() {
        runner(true).run(context -> context.assertThat()
                .hasNotFailed()
                .hasSingleBean(NotificationWorker.class)
                .hasSingleBean(NotificationReconciliationWorker.class)
                .hasSingleBean(NotificationWorkerScheduler.class)
                .hasSingleBean(NotificationReconciliationScheduler.class));
    }

    @Test
    void failsClosedWhenProviderAndCredentialPortsAreUnavailable() {
        runner(false).run(context -> context.assertThat()
                .hasNotFailed()
                .doesNotHaveBean(NotificationWorker.class)
                .doesNotHaveBean(NotificationReconciliationWorker.class));
    }

    @Test
    void schedulerSwitchesPreserveManuallyInvocableWorkers() {
        runner(true)
                .withPropertyValues(
                        "crewscope.notification.worker.enabled=false",
                        "crewscope.notification.worker.reconciliation-enabled=false")
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(NotificationWorker.class)
                        .hasSingleBean(NotificationReconciliationWorker.class)
                        .doesNotHaveBean(NotificationWorkerScheduler.class)
                        .doesNotHaveBean(NotificationReconciliationScheduler.class));
    }

    @Test
    void rejectsUnboundedLeaseCredentialRetryAndAttemptSettings() {
        runner(true)
                .withPropertyValues("crewscope.notification.worker.credential-ttl=3m")
                .run(context -> context.assertThat().hasFailed());
        runner(true)
                .withPropertyValues("crewscope.notification.worker.maximum-retry-delay=1s")
                .run(context -> context.assertThat().hasFailed());
        runner(true)
                .withPropertyValues("crewscope.notification.worker.maximum-attempts=101")
                .run(context -> context.assertThat().hasFailed());
        runner(true)
                .withPropertyValues("crewscope.notification.worker.worker-id=   ")
                .run(context -> context.assertThat().hasFailed());
    }

    private ApplicationContextRunner runner(boolean providerPorts) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(NotificationWorkerApplicationConfiguration.class)
                .withBean(NotificationDispatchRepository.class,
                        () -> mock(NotificationDispatchRepository.class))
                .withBean(NotificationAuthorizationFactsResolver.class,
                        () -> mock(NotificationAuthorizationFactsResolver.class))
                .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
                .withBean(TimeProvider.class, () -> mock(TimeProvider.class));
        if (!providerPorts) {
            return runner;
        }
        return runner
                .withBean(NotificationCredentialIssuer.class,
                        () -> mock(NotificationCredentialIssuer.class))
                .withBean(NotificationProviderPort.class,
                        () -> mock(NotificationProviderPort.class));
    }
}
