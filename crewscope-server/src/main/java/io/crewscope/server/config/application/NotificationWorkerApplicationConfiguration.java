package io.crewscope.server.config.application;

import io.crewscope.application.notification.NotificationAuthorizationFactsResolver;
import io.crewscope.application.notification.NotificationCredentialIssuer;
import io.crewscope.application.notification.NotificationDispatchRepository;
import io.crewscope.application.notification.NotificationProviderPort;
import io.crewscope.application.notification.NotificationPlanningApplicationService;
import io.crewscope.application.notification.NotificationRecoveryScheduleRepository;
import io.crewscope.application.notification.NotificationRedeliveryWorker;
import io.crewscope.application.notification.NotificationReconciliationWorker;
import io.crewscope.application.notification.NotificationWorker;
import io.crewscope.application.notification.NotificationWorkerId;
import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Constructor-based composition for the Provider-neutral M6 notification execution core. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationWorkerProperties.class)
public class NotificationWorkerApplicationConfiguration {

    @Bean
    @ConditionalOnBean({
        NotificationDispatchRepository.class,
        NotificationAuthorizationFactsResolver.class,
        NotificationCredentialIssuer.class,
        NotificationProviderPort.class,
        TransactionExecutor.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(NotificationWorker.class)
    NotificationWorker notificationWorker(
            NotificationDispatchRepository dispatches,
            NotificationAuthorizationFactsResolver factsResolver,
            NotificationCredentialIssuer credentials,
            NotificationProviderPort provider,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            NotificationWorkerProperties properties) {
        properties.validatedPollInterval();
        return new NotificationWorker(
                dispatches, factsResolver, credentials, provider, transactions, timeProvider,
                new NotificationWorkerId(properties.validatedWorkerId()),
                properties.validatedLeaseDuration(), properties.validatedCredentialTtl(),
                properties.validatedRetryDelay(), properties.validatedMaximumRetryDelay(),
                properties.validatedMaximumAttempts(), properties.validatedBatchSize());
    }

    @Bean
    @ConditionalOnBean({
        NotificationDispatchRepository.class,
        NotificationAuthorizationFactsResolver.class,
        NotificationCredentialIssuer.class,
        NotificationProviderPort.class,
        TransactionExecutor.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(NotificationReconciliationWorker.class)
    NotificationReconciliationWorker notificationReconciliationWorker(
            NotificationDispatchRepository dispatches,
            NotificationAuthorizationFactsResolver factsResolver,
            NotificationCredentialIssuer credentials,
            NotificationProviderPort provider,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            NotificationWorkerProperties properties) {
        properties.validatedReconciliationPollInterval();
        return new NotificationReconciliationWorker(
                dispatches, factsResolver, credentials, provider, transactions, timeProvider,
                new NotificationWorkerId(properties.validatedReconciliationWorkerId()),
                properties.validatedLeaseDuration(), properties.validatedCredentialTtl(),
                properties.validatedReconciliationRetryDelay(),
                properties.validatedReconciliationMaximumAttempts(),
                properties.validatedBatchSize());
    }

    @Bean
    @ConditionalOnBean({
        NotificationRecoveryScheduleRepository.class,
        NotificationPlanningApplicationService.class,
        TransactionExecutor.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(NotificationRedeliveryWorker.class)
    NotificationRedeliveryWorker notificationRedeliveryWorker(
            NotificationRecoveryScheduleRepository schedules,
            NotificationPlanningApplicationService planning,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            NotificationWorkerProperties properties) {
        properties.validatedRedeliveryPollInterval();
        return new NotificationRedeliveryWorker(
                schedules, planning, transactions, timeProvider,
                new NotificationWorkerId(properties.validatedRedeliveryWorkerId()),
                properties.validatedLeaseDuration(), properties.validatedBatchSize());
    }

    @Bean
    @ConditionalOnBean(NotificationWorker.class)
    @ConditionalOnProperty(
            prefix = "crewscope.notification.worker",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    NotificationWorkerScheduler notificationWorkerScheduler(
            NotificationWorker worker,
            ObjectProvider<OperationalTelemetry> telemetry) {
        return new NotificationWorkerScheduler(
                worker, telemetry.getIfAvailable(OperationalTelemetry::noop));
    }

    @Bean
    @ConditionalOnBean(NotificationReconciliationWorker.class)
    @ConditionalOnProperty(
            prefix = "crewscope.notification.worker",
            name = "reconciliation-enabled",
            havingValue = "true",
            matchIfMissing = true)
    NotificationReconciliationScheduler notificationReconciliationScheduler(
            NotificationReconciliationWorker worker,
            ObjectProvider<OperationalTelemetry> telemetry) {
        return new NotificationReconciliationScheduler(
                worker, telemetry.getIfAvailable(OperationalTelemetry::noop));
    }

    @Bean
    @ConditionalOnBean(NotificationRedeliveryWorker.class)
    @ConditionalOnProperty(
            prefix = "crewscope.notification.worker",
            name = "redelivery-enabled",
            havingValue = "true",
            matchIfMissing = true)
    NotificationRedeliveryScheduler notificationRedeliveryScheduler(
            NotificationRedeliveryWorker worker,
            ObjectProvider<OperationalTelemetry> telemetry) {
        return new NotificationRedeliveryScheduler(
                worker, telemetry.getIfAvailable(OperationalTelemetry::noop));
    }
}
