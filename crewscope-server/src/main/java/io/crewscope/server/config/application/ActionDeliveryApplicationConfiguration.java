package io.crewscope.server.config.application;

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
import io.crewscope.application.action.CurrentActionDeliveryPlanningResolver;
import io.crewscope.application.action.DurableActionCommandEventPublisher;
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
import io.crewscope.application.review.ReviewRequestApplicationService;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.task.TaskDeliverySummaryService;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit production composition for the M5 Action preview, confirmation and result API. */
@Configuration(proxyBeanMethods = false)
public class ActionDeliveryApplicationConfiguration {

    @Bean
    @ConditionalOnMissingBean(ActionDeliveryPlanningResolver.class)
    ActionDeliveryPlanningResolver actionDeliveryPlanningResolver(
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
            RepositoryBindingRepository repositories,
            ExecutionWorkspaceRepository workspaces,
            GitHubProviderRepository github,
            TimeProvider timeProvider) {
        return new CurrentActionDeliveryPlanningResolver(
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
                repositories,
                workspaces,
                github,
                timeProvider);
    }

    @Bean
    @ConditionalOnMissingBean(ActionCommandEventPublisher.class)
    ActionCommandEventPublisher actionCommandEventPublisher(
            DomainEventStore events,
            TaskEventRepository taskEvents,
            OutboxRepository outbox) {
        return new DurableActionCommandEventPublisher(events, taskEvents, outbox);
    }

    @Bean
    @ConditionalOnMissingBean(ActionDeliveryApplicationService.class)
    ActionDeliveryApplicationService actionDeliveryApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            ResponsibilityAssignmentRepository responsibilities,
            ActionDeliveryPlanningResolver planningResolver,
            ActionAuthorityFactsResolver authorityResolver,
            ActionBundleRepository bundles,
            ConfirmationRepository confirmations,
            ActionDispatchRepository dispatches,
            ActionReceiptRepository actionReceipts,
            ExternalResultRepository externalResults,
            ActionManualResolutionService manualResolution,
            ActionCommandEventPublisher commandEvents,
            ActionWorkerEventPublisher workerEvents,
            CommandReceiptStore commandReceipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new ActionDeliveryApplicationService(
                accessPolicy,
                tasks,
                executions,
                responsibilities,
                planningResolver,
                authorityResolver,
                bundles,
                confirmations,
                dispatches,
                actionReceipts,
                externalResults,
                manualResolution,
                commandEvents,
                workerEvents,
                commandReceipts,
                transactions,
                timeProvider);
    }

    @Bean
    @ConditionalOnMissingBean(TaskDeliverySummaryService.class)
    TaskDeliverySummaryService taskDeliverySummaryService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            PolicySnapshotRepository policies,
            ReviewRequestApplicationService reviews,
            ActionDeliveryApplicationService actions,
            TransactionExecutor transactions) {
        return new TaskDeliverySummaryService(
                accessPolicy, tasks, executions, policies, reviews, actions, transactions);
    }
}
