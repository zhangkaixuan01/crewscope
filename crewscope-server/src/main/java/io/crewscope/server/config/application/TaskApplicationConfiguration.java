package io.crewscope.server.config.application;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.execution.DurableAgentRunResumeService;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationEventRepository;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.runtime.RuntimeObservationRepository;
import io.crewscope.application.runtime.RuntimeObservationService;
import io.crewscope.application.task.AgentTaskCreationService;
import io.crewscope.application.task.MemberTaskCommandService;
import io.crewscope.application.task.ConversationTaskLinkRepository;
import io.crewscope.application.task.AgentInterruptRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.AgentStateSnapshotRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.PlanVersionRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.task.TaskAgentRuntimeSessionRepository;
import io.crewscope.application.task.TaskAssociationRepository;
import io.crewscope.application.task.TaskAssociationService;
import io.crewscope.application.task.TaskCreationPolicySpec;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.TaskEventService;
import io.crewscope.application.task.TaskPlanPublicationService;
import io.crewscope.application.task.TaskQueryService;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.TaskExecutionPriority;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Wires member-facing durable Task commands separately from trusted Worker command ports. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TaskEventStreamProperties.class, RuntimeObservationProperties.class})
public class TaskApplicationConfiguration {

    @Bean
    TaskCreationPolicySpec taskCreationPolicySpec() {
        UUID policyId = UUID.nameUUIDFromBytes(
                "io.crewscope/policy/m3-controlled-task/v1".getBytes(StandardCharsets.UTF_8));
        return new TaskCreationPolicySpec(
                new PolicyPackReference(new PolicyPackId(policyId), 1),
                Set.of(ExecutionCapability.PLAN, ExecutionCapability.CONTEXT_USAGE),
                TaskPlanPublicationService.M3_CONTROLLED_TOOLS,
                new PolicyBudget(100_000, 32, 64, 900),
                3,
                TaskExecutionPriority.NORMAL);
    }

    @Bean
    AgentTaskCreationService agentTaskCreationService(
            WorkItemAccessPolicy workItemAccessPolicy,
            WorkItemRepository workItemRepository,
            ResponsibilityAssignmentRepository responsibilityAssignmentRepository,
            PrincipalRepository principalRepository,
            AgentProfileRepository agentProfileRepository,
            ConversationApplicationService conversationApplicationService,
            ProviderBindingResolver providerBindingResolver,
            TaskRepository taskRepository,
            TaskExecutionRepository taskExecutionRepository,
            PolicySnapshotRepository policySnapshotRepository,
            SafetyEnforcementOverlayRepository safetyEnforcementOverlayRepository,
            ConversationTaskLinkRepository conversationTaskLinkRepository,
            DomainEventStore domainEventStore,
            ConversationEventRepository conversationEventRepository,
            TaskEventRepository taskEventRepository,
            OutboxRepository outboxRepository,
            CommandReceiptStore commandReceiptStore,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider,
            TaskCreationPolicySpec taskCreationPolicySpec) {
        return new AgentTaskCreationService(
                workItemAccessPolicy,
                workItemRepository,
                responsibilityAssignmentRepository,
                principalRepository,
                agentProfileRepository,
                conversationApplicationService,
                providerBindingResolver,
                taskRepository,
                taskExecutionRepository,
                policySnapshotRepository,
                safetyEnforcementOverlayRepository,
                conversationTaskLinkRepository,
                domainEventStore,
                conversationEventRepository,
                taskEventRepository,
                outboxRepository,
                commandReceiptStore,
                transactionExecutor,
                timeProvider,
                taskCreationPolicySpec);
    }

    @Bean
    TaskQueryService taskQueryService(
            WorkItemAccessPolicy workItemAccessPolicy,
            TaskRepository taskRepository,
            TaskExecutionRepository taskExecutionRepository,
            PlanVersionRepository planVersionRepository,
            StepExecutionRepository stepExecutionRepository,
            TaskAgentRuntimeSessionRepository taskAgentRuntimeSessionRepository,
            AgentRunRepository agentRunRepository,
            AgentInterruptRepository agentInterruptRepository,
            AgentStateSnapshotRepository agentStateSnapshotRepository,
            ExecutionLeaseRepository executionLeaseRepository,
            TransactionExecutor transactionExecutor) {
        return new TaskQueryService(
                workItemAccessPolicy,
                taskRepository,
                taskExecutionRepository,
                planVersionRepository,
                stepExecutionRepository,
                taskAgentRuntimeSessionRepository,
                agentRunRepository,
                agentInterruptRepository,
                agentStateSnapshotRepository,
                executionLeaseRepository,
                transactionExecutor);
    }

    @Bean
    TaskAssociationService taskAssociationService(
            WorkItemAccessPolicy workItemAccessPolicy,
            ConversationApplicationService conversationApplicationService,
            TaskRepository taskRepository,
            TaskAssociationRepository taskAssociationRepository,
            TransactionExecutor transactionExecutor) {
        return new TaskAssociationService(
                workItemAccessPolicy,
                conversationApplicationService,
                taskRepository,
                taskAssociationRepository,
                transactionExecutor);
    }

    @Bean
    TaskEventService taskEventService(
            WorkItemAccessPolicy workItemAccessPolicy,
            TaskRepository taskRepository,
            TaskEventRepository taskEventRepository,
            TransactionExecutor transactionExecutor) {
        return new TaskEventService(
                workItemAccessPolicy, taskRepository, taskEventRepository, transactionExecutor);
    }

    @Bean
    RuntimeObservationService runtimeObservationService(
            WorkItemAccessPolicy workItemAccessPolicy,
            RuntimeObservationRepository runtimeObservationRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider authoritativeTimeProvider,
            RuntimeObservationProperties properties) {
        return new RuntimeObservationService(
                workItemAccessPolicy,
                runtimeObservationRepository,
                transactionExecutor,
                authoritativeTimeProvider,
                properties.validatedHeartbeatTimeout());
    }

    @Bean
    DurableAgentRunResumeService durableAgentRunResumeService(
            AgentRunRepository agentRunRepository,
            AgentInterruptRepository agentInterruptRepository,
            PrincipalRepository principalRepository,
            DomainEventStore domainEventStore,
            TaskEventRepository taskEventRepository,
            OutboxRepository outboxRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider authoritativeTimeProvider) {
        return new DurableAgentRunResumeService(
                agentRunRepository,
                agentInterruptRepository,
                principalRepository,
                domainEventStore,
                taskEventRepository,
                outboxRepository,
                transactionExecutor,
                authoritativeTimeProvider);
    }

    @Bean
    MemberTaskCommandService memberTaskCommandService(
            WorkItemAccessPolicy workItemAccessPolicy,
            ResponsibilityAssignmentRepository responsibilityAssignmentRepository,
            PrincipalRepository principalRepository,
            AgentProfileRepository agentProfileRepository,
            ProviderBindingResolver providerBindingResolver,
            TaskRepository taskRepository,
            TaskExecutionRepository taskExecutionRepository,
            PolicySnapshotRepository policySnapshotRepository,
            SafetyEnforcementOverlayRepository safetyEnforcementOverlayRepository,
            AgentRunRepository agentRunRepository,
            AgentInterruptRepository agentInterruptRepository,
            DurableAgentRunResumeService durableAgentRunResumeService,
            DomainEventStore domainEventStore,
            TaskEventRepository taskEventRepository,
            OutboxRepository outboxRepository,
            CommandReceiptStore commandReceiptStore,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider authoritativeTimeProvider) {
        return new MemberTaskCommandService(
                workItemAccessPolicy,
                responsibilityAssignmentRepository,
                principalRepository,
                agentProfileRepository,
                providerBindingResolver,
                taskRepository,
                taskExecutionRepository,
                policySnapshotRepository,
                safetyEnforcementOverlayRepository,
                agentRunRepository,
                agentInterruptRepository,
                durableAgentRunResumeService,
                domainEventStore,
                taskEventRepository,
                outboxRepository,
                commandReceiptStore,
                transactionExecutor,
                authoritativeTimeProvider);
    }
}
