package io.crewscope.server.config.application;

import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.ResolvedAgentPolicySnapshotService;
import io.crewscope.application.coding.BuildProfileCatalog;
import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.CodingTargetSelectionService;
import io.crewscope.application.coding.CodingTaskTimelinePublisher;
import io.crewscope.application.coding.CodingTaskEventCompletionPolicy;
import io.crewscope.application.coding.DurableCodingTaskTimelinePublisher;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.ImmutableBuildProfileCatalog;
import io.crewscope.application.coding.RepositoryBindingPreflightError;
import io.crewscope.application.coding.RepositoryBindingPreflightException;
import io.crewscope.application.coding.RepositoryBindingPreflightPort;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.coding.query.CodingAttemptQueryPort;
import io.crewscope.application.coding.query.TaskCodingQueryService;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.execution.DurableAgentRunResumeService;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationEventRepository;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.model.ModelConnectionAvailabilityVerifier;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.runtime.RuntimeObservationRepository;
import io.crewscope.application.runtime.RuntimeObservationService;
import io.crewscope.application.runtime.CodingRuntimeOperationsPort;
import io.crewscope.application.action.TeamActionReconciliationHealthRepository;
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
import io.crewscope.application.task.TaskAgentSelectionService;
import io.crewscope.application.task.TaskAssociationRepository;
import io.crewscope.application.task.TaskAssociationService;
import io.crewscope.application.task.TaskCreationPolicySpec;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.TaskEventCompletionPolicy;
import io.crewscope.application.task.TaskEventService;
import io.crewscope.application.task.TaskPlanPublicationService;
import io.crewscope.application.task.TaskQueryService;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
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
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildTool;
import io.crewscope.domain.coding.CommandCatalog;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandSelectorPolicy;
import io.crewscope.domain.coding.SandboxImageReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;

/** Wires member-facing durable Task commands separately from trusted Worker command ports. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TaskEventStreamProperties.class, RuntimeObservationProperties.class})
public class TaskApplicationConfiguration {

    @Bean
    TaskAgentSelectionService taskAgentSelectionService(
            AgentProfileRepository profiles,
            PrincipalRepository principals,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            ModelConnectionRepository connections,
            ModelConnectionAvailabilityVerifier availability,
            AgentModelGovernance governance,
            AgentExecutionConfigurationService configurations) {
        return new TaskAgentSelectionService(
                profiles,
                principals,
                teams,
                memberships,
                connections,
                availability,
                governance,
                configurations);
    }

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

    /** Frozen M4 Java/Maven profile; upgrades are introduced as new immutable versions. */
    @Bean
    BuildProfileCatalog buildProfileCatalog() {
        CommandSelectorPolicy testSelectors =
                new CommandSelectorPolicy(List.of(), 0, 20, 256);
        BuildProfile mavenJava17 = BuildProfile.define(
                "maven-java-17",
                1,
                BuildTool.MAVEN,
                17,
                new SandboxImageReference(
                        "maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4"),
                new CommandCatalog(Map.of(
                        CommandKind.COMPILE,
                        new BuildCommand(
                                "coding.maven.compile",
                                List.of("mvn", "-B", "-ntp", "-DskipTests", "compile"),
                                ".",
                                300,
                                900),
                        CommandKind.TEST,
                        new BuildCommand(
                                "coding.maven.test",
                                List.of("mvn", "-B", "-ntp", "test"),
                                ".",
                                300,
                                900,
                                testSelectors),
                        CommandKind.VERIFY,
                        new BuildCommand(
                                "coding.maven.verify",
                                List.of("mvn", "-B", "-ntp", "verify"),
                                ".",
                                300,
                                900,
                                testSelectors))));
        return new ImmutableBuildProfileCatalog(List.of(mavenJava17));
    }

    @Bean
    CodingTaskTimelinePublisher codingTaskTimelinePublisher(
            ExecutionWorkspaceRepository executionWorkspaceRepository,
            DomainEventStore domainEventStore,
            TaskEventRepository taskEventRepository,
            OutboxRepository outboxRepository,
            TransactionExecutor transactionExecutor) {
        return new DurableCodingTaskTimelinePublisher(
                executionWorkspaceRepository,
                domainEventStore,
                taskEventRepository,
                outboxRepository,
                transactionExecutor);
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
            RepositoryBindingRepository repositoryBindingRepository,
            ObjectProvider<RepositoryBindingPreflightPort> repositoryPreflightPorts,
            BuildProfileCatalog buildProfileCatalog,
            CodingTargetSnapshotRepository codingTargetSnapshotRepository,
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
            TaskCreationPolicySpec taskCreationPolicySpec,
            TaskAgentSelectionService taskAgentSelectionService,
            ResolvedAgentPolicySnapshotService resolvedAgentPolicySnapshotService) {
        RepositoryBindingPreflightPort repositoryPreflight =
                repositoryPreflightPorts.getIfAvailable(() -> (binding, baselineRef) -> {
                    throw new RepositoryBindingPreflightException(
                            RepositoryBindingPreflightError.SERVICE_UNAVAILABLE,
                            "Repository Preflight is unavailable on this server");
                });
        return new AgentTaskCreationService(
                workItemAccessPolicy,
                workItemRepository,
                responsibilityAssignmentRepository,
                principalRepository,
                agentProfileRepository,
                conversationApplicationService,
                providerBindingResolver,
                repositoryBindingRepository,
                repositoryPreflight,
                buildProfileCatalog,
                codingTargetSnapshotRepository,
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
                taskCreationPolicySpec,
                taskAgentSelectionService,
                resolvedAgentPolicySnapshotService);
    }

    @Bean
    CodingTargetSelectionService codingTargetSelectionService(
            WorkItemAccessPolicy workItemAccessPolicy,
            RepositoryBindingRepository repositoryBindingRepository,
            ObjectProvider<RepositoryBindingPreflightPort> repositoryPreflightPorts,
            BuildProfileCatalog buildProfileCatalog,
            TransactionExecutor transactionExecutor) {
        RepositoryBindingPreflightPort repositoryPreflight =
                repositoryPreflightPorts.getIfAvailable(() -> (binding, baselineRef) -> {
                    throw new RepositoryBindingPreflightException(
                            RepositoryBindingPreflightError.SERVICE_UNAVAILABLE,
                            "Repository Preflight is unavailable on this server");
                });
        return new CodingTargetSelectionService(
                workItemAccessPolicy,
                repositoryBindingRepository,
                repositoryPreflight,
                buildProfileCatalog,
                transactionExecutor);
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
    TaskCodingQueryService taskCodingQueryService(
            WorkItemAccessPolicy workItemAccessPolicy,
            TaskRepository taskRepository,
            TaskExecutionRepository taskExecutionRepository,
            CodingAttemptQueryPort codingAttemptQueryPort,
            TransactionExecutor transactionExecutor) {
        return new TaskCodingQueryService(
                workItemAccessPolicy,
                taskRepository,
                taskExecutionRepository,
                codingAttemptQueryPort,
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
            TransactionExecutor transactionExecutor,
            TaskEventCompletionPolicy taskEventCompletionPolicy) {
        return new TaskEventService(
                workItemAccessPolicy,
                taskRepository,
                taskEventRepository,
                transactionExecutor,
                taskEventCompletionPolicy);
    }

    @Bean
    TaskEventCompletionPolicy taskEventCompletionPolicy(
            CodingTargetSnapshotRepository codingTargetSnapshotRepository,
            ExecutionWorkspaceRepository executionWorkspaceRepository) {
        return new CodingTaskEventCompletionPolicy(
                codingTargetSnapshotRepository, executionWorkspaceRepository);
    }

    @Bean
    RuntimeObservationService runtimeObservationService(
            WorkItemAccessPolicy workItemAccessPolicy,
            RuntimeObservationRepository runtimeObservationRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider authoritativeTimeProvider,
            RuntimeObservationProperties properties,
            ObjectProvider<CodingRuntimeOperationsPort> codingRuntimeOperations,
            ObjectProvider<TeamActionReconciliationHealthRepository> actionDeliveryHealth,
            ObjectProvider<ActionReconciliationProperties> actionReconciliationProperties) {
        ActionReconciliationProperties actionProperties =
                actionReconciliationProperties.getIfAvailable(ActionReconciliationProperties::new);
        return new RuntimeObservationService(
                workItemAccessPolicy,
                runtimeObservationRepository,
                transactionExecutor,
                authoritativeTimeProvider,
                properties.validatedHeartbeatTimeout(),
                codingRuntimeOperations.getIfAvailable(),
                actionDeliveryHealth.getIfAvailable(),
                actionProperties.validatedMaximumUnknownAge());
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
            AuthoritativeTimeProvider authoritativeTimeProvider,
            TaskAgentSelectionService taskAgentSelectionService,
            ResolvedAgentPolicySnapshotService resolvedAgentPolicySnapshotService) {
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
                authoritativeTimeProvider,
                taskAgentSelectionService,
                resolvedAgentPolicySnapshotService);
    }
}
