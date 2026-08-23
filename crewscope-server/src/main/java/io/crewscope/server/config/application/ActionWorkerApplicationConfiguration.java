package io.crewscope.server.config.application;

import io.crewscope.application.action.ActionAuthorityFactsResolver;
import io.crewscope.application.action.ActionBundleRepository;
import io.crewscope.application.action.ActionDispatchRepository;
import io.crewscope.application.action.ActionReceiptRepository;
import io.crewscope.application.action.ActionWorker;
import io.crewscope.application.action.ActionWorkerEventPublisher;
import io.crewscope.application.action.ConfirmationRepository;
import io.crewscope.application.action.CurrentActionAuthorityFactsResolver;
import io.crewscope.application.action.DurableActionWorkerEventPublisher;
import io.crewscope.application.action.GitHubRepositoryPolicyResolver;
import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.github.GitHubDraftPullRequestPort;
import io.crewscope.application.github.GitHubPushPort;
import io.crewscope.application.github.GitHubRepositoryPolicy;
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
import io.crewscope.domain.action.ActionWorkerId;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Constructor-based production composition for M5 confirmed external Action execution. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ActionWorkerProperties.class)
public class ActionWorkerApplicationConfiguration {

    @Bean
    @ConditionalOnBean({
        ReviewRequestRepository.class,
        ContextPackageRepository.class,
        ReviewDecisionRepository.class,
        ResponsibilityAssignmentRepository.class,
        ProviderBindingRepository.class,
        ConnectionRepository.class,
        ConnectionGrantRepository.class,
        PolicySnapshotRepository.class,
        SafetyEnforcementOverlayRepository.class,
        CodingTargetSnapshotRepository.class,
        RepositoryBindingRepository.class
    })
    @ConditionalOnMissingBean(ActionAuthorityFactsResolver.class)
    ActionAuthorityFactsResolver actionAuthorityFactsResolver(
            ReviewRequestRepository reviewRequests,
            ContextPackageRepository contexts,
            ReviewDecisionRepository decisions,
            ResponsibilityAssignmentRepository responsibilities,
            ProviderBindingRepository providerBindings,
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            PolicySnapshotRepository policies,
            SafetyEnforcementOverlayRepository safetyOverlays,
            CodingTargetSnapshotRepository codingTargets,
            RepositoryBindingRepository repositories) {
        return new CurrentActionAuthorityFactsResolver(
                reviewRequests,
                contexts,
                decisions,
                responsibilities,
                providerBindings,
                connections,
                grants,
                policies,
                safetyOverlays,
                codingTargets,
                repositories);
    }

    @Bean
    @ConditionalOnMissingBean(GitHubRepositoryPolicyResolver.class)
    GitHubRepositoryPolicyResolver gitHubRepositoryPolicyResolver(
            GitHubProviderProperties properties) {
        return (authority, action) -> new GitHubRepositoryPolicy(
                properties.getRepositoryAllowlist(),
                properties.getAllowedOwnerLogins(),
                properties.isAllowPrivateRepositories(),
                properties.isAllowInternalRepositories(),
                properties.isAllowBroadUserOauth());
    }

    @Bean
    @ConditionalOnBean({DomainEventStore.class, TaskEventRepository.class, OutboxRepository.class})
    @ConditionalOnMissingBean(ActionWorkerEventPublisher.class)
    ActionWorkerEventPublisher actionWorkerEventPublisher(
            DomainEventStore events,
            TaskEventRepository taskEvents,
            OutboxRepository outbox) {
        return new DurableActionWorkerEventPublisher(events, taskEvents, outbox);
    }

    @Bean
    @ConditionalOnBean({
        ActionDispatchRepository.class,
        ActionReceiptRepository.class,
        ActionBundleRepository.class,
        ConfirmationRepository.class,
        ActionAuthorityFactsResolver.class,
        GitHubRepositoryPolicyResolver.class,
        GitHubPushPort.class,
        GitHubDraftPullRequestPort.class,
        ActionWorkerEventPublisher.class,
        TransactionExecutor.class,
        TimeProvider.class
    })
    @ConditionalOnMissingBean(ActionWorker.class)
    ActionWorker actionWorker(
            ActionDispatchRepository dispatches,
            ActionReceiptRepository receipts,
            ActionBundleRepository bundles,
            ConfirmationRepository confirmations,
            ActionAuthorityFactsResolver authorityResolver,
            GitHubRepositoryPolicyResolver policyResolver,
            GitHubPushPort pushPort,
            GitHubDraftPullRequestPort pullRequestPort,
            ActionWorkerEventPublisher events,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            ActionWorkerProperties properties) {
        properties.validatedPollInterval();
        return new ActionWorker(
                dispatches,
                receipts,
                bundles,
                confirmations,
                authorityResolver,
                policyResolver,
                pushPort,
                pullRequestPort,
                events,
                transactions,
                timeProvider,
                new ActionWorkerId(properties.validatedWorkerId()),
                properties.validatedLeaseDuration(),
                properties.validatedRetryDelay(),
                properties.validatedBatchSize());
    }

    @Bean
    @ConditionalOnBean(ActionWorker.class)
    @ConditionalOnProperty(
            prefix = "crewscope.action.worker",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    ActionWorkerScheduler actionWorkerScheduler(ActionWorker worker) {
        return new ActionWorkerScheduler(worker);
    }
}
