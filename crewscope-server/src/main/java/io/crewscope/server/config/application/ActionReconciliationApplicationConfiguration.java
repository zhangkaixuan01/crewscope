package io.crewscope.server.config.application;

import io.crewscope.application.action.ActionAuthorityFactsResolver;
import io.crewscope.application.action.ActionBundleRepository;
import io.crewscope.application.action.ActionDispatchRepository;
import io.crewscope.application.action.ActionManualResolutionService;
import io.crewscope.application.action.ActionReceiptRepository;
import io.crewscope.application.action.ActionReconciliationObserver;
import io.crewscope.application.action.ActionReconciliationWorker;
import io.crewscope.application.action.ActionWorkerEventPublisher;
import io.crewscope.application.action.ConfirmationRepository;
import io.crewscope.application.action.ExternalObservationRepository;
import io.crewscope.application.action.ExternalResultMerger;
import io.crewscope.application.action.ExternalResultRepository;
import io.crewscope.application.action.GitHubRepositoryPolicyResolver;
import io.crewscope.application.github.GitHubDraftPullRequestPort;
import io.crewscope.application.github.GitHubPushPort;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.action.ActionWorkerId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.server.observability.ActionReconciliationHealthIndicator;
import io.crewscope.server.observability.ActionReconciliationMetricsObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Production composition for query-only UNKNOWN recovery and external result convergence. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ActionReconciliationProperties.class)
public class ActionReconciliationApplicationConfiguration {

    @Bean
    @ConditionalOnBean({
        ExternalObservationRepository.class,
        ExternalResultRepository.class,
        ActionReceiptRepository.class,
        ActionWorkerEventPublisher.class
    })
    @ConditionalOnMissingBean(ExternalResultMerger.class)
    ExternalResultMerger externalResultMerger(
            ExternalObservationRepository observations,
            ExternalResultRepository results,
            ActionReceiptRepository receipts,
            ActionWorkerEventPublisher events) {
        return new ExternalResultMerger(observations, results, receipts, events);
    }

    @Bean
    @ConditionalOnBean({
        ActionDispatchRepository.class,
        ActionReceiptRepository.class,
        ActionBundleRepository.class,
        ActionAuthorityFactsResolver.class,
        ActionWorkerEventPublisher.class,
        TransactionExecutor.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(ActionManualResolutionService.class)
    ActionManualResolutionService actionManualResolutionService(
            ActionDispatchRepository dispatches,
            ActionReceiptRepository receipts,
            ActionBundleRepository bundles,
            ActionAuthorityFactsResolver authorityResolver,
            ActionWorkerEventPublisher events,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new ActionManualResolutionService(
                dispatches,
                receipts,
                bundles,
                authorityResolver,
                events,
                transactions,
                timeProvider);
    }

    @Bean
    @ConditionalOnBean({MeterRegistry.class, Tracer.class})
    @ConditionalOnMissingBean(ActionReconciliationObserver.class)
    ActionReconciliationObserver actionReconciliationObserver(
            MeterRegistry registry, Tracer tracer) {
        return new ActionReconciliationMetricsObserver(registry, tracer);
    }

    @Bean
    @ConditionalOnMissingBean(ActionReconciliationObserver.class)
    ActionReconciliationObserver noOpActionReconciliationObserver() {
        return ActionReconciliationObserver.noOp();
    }

    @Bean
    @ConditionalOnBean({
        ActionDispatchRepository.class,
        ActionReceiptRepository.class,
        ActionBundleRepository.class,
        ConfirmationRepository.class,
        ExternalObservationRepository.class,
        ExternalResultMerger.class,
        ActionAuthorityFactsResolver.class,
        GitHubRepositoryPolicyResolver.class,
        GitHubPushPort.class,
        GitHubDraftPullRequestPort.class,
        ActionWorkerEventPublisher.class,
        ActionReconciliationObserver.class,
        TransactionExecutor.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(ActionReconciliationWorker.class)
    ActionReconciliationWorker actionReconciliationWorker(
            ActionDispatchRepository dispatches,
            ActionReceiptRepository receipts,
            ActionBundleRepository bundles,
            ConfirmationRepository confirmations,
            ExternalObservationRepository observations,
            ExternalResultMerger externalResults,
            ActionAuthorityFactsResolver authorityResolver,
            GitHubRepositoryPolicyResolver policyResolver,
            GitHubPushPort pushPort,
            GitHubDraftPullRequestPort pullRequestPort,
            ActionWorkerEventPublisher events,
            ActionReconciliationObserver observer,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            ActionReconciliationProperties properties) {
        properties.validatedPollInterval();
        return new ActionReconciliationWorker(
                dispatches,
                receipts,
                bundles,
                confirmations,
                observations,
                externalResults,
                authorityResolver,
                policyResolver,
                pushPort,
                pullRequestPort,
                events,
                observer,
                transactions,
                timeProvider,
                new ActionWorkerId(properties.validatedWorkerId()),
                properties.validatedLeaseDuration(),
                properties.validatedRetryDelay(),
                properties.validatedMaximumUnknownAge(),
                properties.validatedMaximumAttempts(),
                properties.validatedBatchSize());
    }

    @Bean
    @ConditionalOnBean(ActionReconciliationWorker.class)
    @ConditionalOnProperty(
            prefix = "crewscope.action.reconciliation",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    ActionReconciliationScheduler actionReconciliationScheduler(
            ActionReconciliationWorker worker,
            ActionDispatchRepository dispatches,
            ActionReconciliationObserver observer) {
        return new ActionReconciliationScheduler(worker, dispatches, observer);
    }

    @Bean
    @ConditionalOnBean(ActionReconciliationWorker.class)
    @ConditionalOnProperty(
            prefix = "crewscope.action.reconciliation",
            name = {"enabled", "startup-enabled"},
            havingValue = "true",
            matchIfMissing = true)
    ActionReconciliationStartupRunner actionReconciliationStartupRunner(
            ActionReconciliationWorker worker,
            ActionDispatchRepository dispatches,
            ActionReconciliationObserver observer) {
        return new ActionReconciliationStartupRunner(worker, dispatches, observer);
    }

    @Bean
    @ConditionalOnBean({ActionDispatchRepository.class, TimeProvider.class})
    ActionReconciliationHealthIndicator actionReconciliationHealthIndicator(
            ActionDispatchRepository dispatches,
            TimeProvider timeProvider,
            ActionReconciliationProperties properties) {
        return new ActionReconciliationHealthIndicator(
                dispatches, timeProvider, properties.validatedMaximumUnknownAge());
    }
}
