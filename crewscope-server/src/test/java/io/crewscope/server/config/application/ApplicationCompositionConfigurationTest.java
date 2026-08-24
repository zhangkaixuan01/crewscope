package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.agentscope.core.state.AgentStateStore;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.IdentityMappingService;
import io.crewscope.application.conversation.AgentRuntimeSessionRepository;
import io.crewscope.application.conversation.AgentRuntimeSessionService;
import io.crewscope.application.conversation.ConversationParticipantRepository;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationEventRepository;
import io.crewscope.application.conversation.ConversationWorkItemLinkRepository;
import io.crewscope.application.conversation.ConversationWorkItemQueryService;
import io.crewscope.application.conversation.MessageRepository;
import io.crewscope.application.conversation.TaskIntentApplicationService;
import io.crewscope.application.conversation.TaskIntentConfirmationCommandPort;
import io.crewscope.application.conversation.TaskIntentRepository;
import io.crewscope.application.execution.PlatformExecutionContextResolver;
import io.crewscope.application.execution.ExecutionRuntime;
import io.crewscope.application.execution.PersonalAgentExecutionContextResolver;
import io.crewscope.application.execution.PersonalAgentInvocationService;
import io.crewscope.application.execution.AgentStatePreflight;
import io.crewscope.application.execution.ConversationExecutionEventMapper;
import io.crewscope.application.execution.RealtimeDomainEventProjector;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.BuiltInProviderInitializationService;
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ProviderBindingQueryService;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.provider.ProviderBootstrapLock;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.application.provider.TeamProviderInitializer;
import io.crewscope.application.responsibility.GateReviewerAssignmentService;
import io.crewscope.application.responsibility.GateReviewerPolicyProvider;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentService;
import io.crewscope.application.responsibility.ResponsibilityCommandService;
import io.crewscope.application.responsibility.ResponsibilityQueryService;
import io.crewscope.application.team.DefaultPersonalAgentRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamApplicationService;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemCollaborationService;
import io.crewscope.application.workitem.WorkItemCommandService;
import io.crewscope.application.workitem.WorkItemCommentRepository;
import io.crewscope.application.workitem.WorkItemQueryService;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkItemResourceLinkRepository;
import io.crewscope.application.workitem.WorkItemTimelineRepository;
import io.crewscope.application.workitem.WorkItemTimelineService;
import io.crewscope.application.workitem.WorkProjectApplicationService;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.agentscope.AgentCallObservationSink;
import io.crewscope.agentscope.AgentCallTraceContextProvider;
import io.crewscope.agentscope.AgentExecutionAuditSink;
import io.crewscope.agentscope.AgentScopeModelResolver;
import io.crewscope.agentscope.AgentStatePreflightMiddleware;
import io.crewscope.agentscope.PlatformAgentMiddlewareSet;
import io.crewscope.agentscope.PlatformAuditMiddleware;
import io.crewscope.agentscope.PlatformRuntimeContextMiddleware;
import io.crewscope.agentscope.PersonalAgentFactory;
import io.crewscope.agentscope.ProviderBindingSecurityMiddleware;
import io.crewscope.agentscope.agui.ControlledAguiBridge;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.integration.provider.workitem.NativeWorkItemProvider;
import io.crewscope.server.observability.AgentCallObservabilityMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Proves that every business-scoped composition class can build its application services. */
class ApplicationCompositionConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              PlatformApplicationConfiguration.class,
              IdentityApplicationConfiguration.class,
              TeamApplicationConfiguration.class,
              ProviderApplicationConfiguration.class,
              ConversationApplicationConfiguration.class,
              AgentScopeModelConfiguration.class,
              AgentScopeApplicationConfiguration.class,
              WorkItemApplicationConfiguration.class)
          .withBean(PrincipalRepository.class, () -> mock(PrincipalRepository.class))
          .withBean(DomainEventStore.class, () -> mock(DomainEventStore.class))
          .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
          .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
          .withBean(CommandReceiptStore.class, () -> mock(CommandReceiptStore.class))
          .withBean(TeamRepository.class, () -> mock(TeamRepository.class))
          .withBean(WorkspaceRepository.class, () -> mock(WorkspaceRepository.class))
          .withBean(TeamMemberRepository.class, () -> mock(TeamMemberRepository.class))
          .withBean(TeamMembershipQuery.class, () -> mock(TeamMembershipQuery.class))
          .withBean(TeamRoleRepository.class, () -> mock(TeamRoleRepository.class))
          .withBean(MemberRoleRepository.class, () -> mock(MemberRoleRepository.class))
          .withBean(AgentProfileRepository.class, () -> mock(AgentProfileRepository.class))
          .withBean(
              AgentConfigurationRepository.class,
              () -> mock(AgentConfigurationRepository.class))
          .withBean(ConversationRepository.class, () -> mock(ConversationRepository.class))
          .withBean(
              ConversationParticipantRepository.class,
              () -> mock(ConversationParticipantRepository.class))
          .withBean(MessageRepository.class, () -> mock(MessageRepository.class))
          .withBean(
              ConversationEventRepository.class,
              () -> mock(ConversationEventRepository.class))
          .withBean(
              ConversationWorkItemLinkRepository.class,
              () -> mock(ConversationWorkItemLinkRepository.class))
          .withBean(
              AgentRuntimeSessionRepository.class,
              () -> mock(AgentRuntimeSessionRepository.class))
          .withBean(
              DefaultPersonalAgentRepository.class,
              () -> mock(DefaultPersonalAgentRepository.class))
          .withBean(WorkItemRepository.class, () -> mock(WorkItemRepository.class))
          .withBean(
              WorkItemCommentRepository.class, () -> mock(WorkItemCommentRepository.class))
          .withBean(
              WorkItemResourceLinkRepository.class,
              () -> mock(WorkItemResourceLinkRepository.class))
          .withBean(
              WorkItemTimelineRepository.class, () -> mock(WorkItemTimelineRepository.class))
          .withBean(WorkProjectRepository.class, () -> mock(WorkProjectRepository.class))
          .withBean(TaskIntentRepository.class, () -> mock(TaskIntentRepository.class))
          .withBean(
              ProviderBindingRepository.class, () -> mock(ProviderBindingRepository.class))
          .withBean(
              ProviderDefinitionRepository.class,
              () -> mock(ProviderDefinitionRepository.class))
          .withBean(
              ProviderImplementationRepository.class,
              () -> mock(ProviderImplementationRepository.class))
          .withBean(ProviderBootstrapLock.class, () -> mock(ProviderBootstrapLock.class))
          .withBean(ConnectionRepository.class, () -> mock(ConnectionRepository.class))
          .withBean(
              ConnectionGrantRepository.class, () -> mock(ConnectionGrantRepository.class))
          .withBean(
              ResponsibilityAssignmentRepository.class,
              () -> mock(ResponsibilityAssignmentRepository.class))
          .withBean(AgentStatePreflight.class, () -> mock(AgentStatePreflight.class))
          .withBean(AgentStateStore.class, () -> mock(AgentStateStore.class))
          .withBean(Validator.class, () -> mock(Validator.class))
          .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
          .withBean(Tracer.class, () -> mock(Tracer.class));

  @Test
  void wiresEachFrameworkFreeApplicationServiceExactlyOnce() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(TimeProvider.class);
          assertThat(context).hasSingleBean(IdentityMappingService.class);
          assertThat(context).hasSingleBean(ProviderBindingResolver.class);
          assertThat(context).hasSingleBean(NativeWorkItemProvider.class);
          assertThat(context).hasSingleBean(BuiltInProviderRegistration.class);
          assertThat(context).hasSingleBean(BuiltInProviderInitializationService.class);
          assertThat(context).hasSingleBean(TeamProviderInitializer.class);
          assertThat(context).hasSingleBean(ProviderBindingQueryService.class);
          assertThat(context).hasSingleBean(PlatformExecutionContextResolver.class);
          assertThat(context).hasSingleBean(AgentRuntimeSessionService.class);
          assertThat(context).hasSingleBean(AgentScopeModelResolver.class);
          assertThat(context).hasSingleBean(PersonalAgentExecutionContextResolver.class);
          assertThat(context).hasSingleBean(PersonalAgentFactory.class);
          assertThat(context).hasSingleBean(ExecutionRuntime.class);
          assertThat(context).hasSingleBean(PersonalAgentInvocationService.class);
          assertThat(context).hasSingleBean(ConversationExecutionEventMapper.class);
          assertThat(context).hasSingleBean(RealtimeDomainEventProjector.class);
          assertThat(context).hasSingleBean(ControlledAguiBridge.class);
          assertThat(context).hasSingleBean(PlatformRuntimeContextMiddleware.class);
          assertThat(context).hasSingleBean(ProviderBindingSecurityMiddleware.class);
          assertThat(context).hasSingleBean(PlatformAuditMiddleware.class);
          assertThat(context).hasSingleBean(AgentExecutionAuditSink.class);
          assertThat(context).hasSingleBean(AgentCallObservationSink.class);
          assertThat(context).hasSingleBean(AgentCallTraceContextProvider.class);
          assertThat(context).hasSingleBean(AgentCallObservabilityMetrics.class);
          assertThat(context).hasSingleBean(AgentStatePreflightMiddleware.class);
          assertThat(context).hasSingleBean(PlatformAgentMiddlewareSet.class);
          assertThat(context.getBean(PlatformAgentMiddlewareSet.class).ordered())
              .containsExactly(
                  context.getBean(PlatformRuntimeContextMiddleware.class),
                  context.getBean(ProviderBindingSecurityMiddleware.class),
                  context.getBean(PlatformAuditMiddleware.class),
                  context.getBean(AgentStatePreflightMiddleware.class));
          assertThat(context).hasSingleBean(TeamApplicationService.class);
          assertThat(context).hasSingleBean(ConversationApplicationService.class);
          assertThat(context).hasSingleBean(TaskIntentApplicationService.class);
          assertThat(context).hasSingleBean(TaskIntentConfirmationCommandPort.class);
          assertThat(context).hasSingleBean(ConversationWorkItemQueryService.class);
          assertThat(context).hasSingleBean(WorkProjectApplicationService.class);
          assertThat(context).hasSingleBean(WorkItemCommandService.class);
          assertThat(context).hasSingleBean(WorkItemAccessPolicy.class);
          assertThat(context).hasSingleBean(WorkItemQueryService.class);
          assertThat(context).hasSingleBean(WorkItemTimelineService.class);
          assertThat(context).hasSingleBean(WorkItemCollaborationService.class);
          assertThat(context).hasSingleBean(ResponsibilityAssignmentService.class);
          assertThat(context).hasSingleBean(GateReviewerAssignmentService.class);
          assertThat(context).hasSingleBean(GateReviewerPolicyProvider.class);
          assertThat(context).hasSingleBean(ResponsibilityQueryService.class);
          assertThat(context).hasSingleBean(ResponsibilityCommandService.class);
        });
  }
}
