package io.crewscope.server.config.application;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationEventRepository;
import io.crewscope.application.conversation.ConversationParticipantRepository;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.conversation.ConversationWorkItemLinkRepository;
import io.crewscope.application.conversation.ConversationWorkItemQueryService;
import io.crewscope.application.conversation.MessageRepository;
import io.crewscope.application.conversation.TaskIntentApplicationService;
import io.crewscope.application.conversation.TaskIntentConfirmationCommandPort;
import io.crewscope.application.conversation.TaskIntentConfirmationService;
import io.crewscope.application.conversation.TaskIntentRepository;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.responsibility.GateReviewerPolicyProvider;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.conversation.ConversationVisibilityPolicy;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Wires the framework-free Conversation application slice and its visibility policy. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConversationEventStreamProperties.class)
public class ConversationApplicationConfiguration {

  @Bean
  ConversationVisibilityPolicy conversationVisibilityPolicy() {
    return new ConversationVisibilityPolicy();
  }

  @Bean
  ConversationApplicationService conversationApplicationService(
      ConversationRepository conversationRepository,
      ConversationParticipantRepository participantRepository,
      MessageRepository messageRepository,
      ConversationEventRepository conversationEventRepository,
      TeamRepository teamRepository,
      WorkspaceRepository workspaceRepository,
      TeamMembershipQuery membershipQuery,
      PrincipalRepository principalRepository,
      AgentProfileRepository agentProfileRepository,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider,
      ConversationVisibilityPolicy visibilityPolicy) {
    return new ConversationApplicationService(
        conversationRepository,
        participantRepository,
        messageRepository,
        conversationEventRepository,
        teamRepository,
        workspaceRepository,
        membershipQuery,
        principalRepository,
        agentProfileRepository,
        teamRoleRepository,
        memberRoleRepository,
        domainEventStore,
        outboxRepository,
        receiptStore,
        transactionExecutor,
        timeProvider,
        visibilityPolicy);
  }

  @Bean
  TaskIntentApplicationService taskIntentApplicationService(
      ConversationApplicationService conversationService,
      ConversationRepository conversationRepository,
      ConversationParticipantRepository participantRepository,
      ConversationEventRepository conversationEventRepository,
      TaskIntentRepository taskIntentRepository,
      WorkProjectRepository workProjectRepository,
      TeamMembershipQuery membershipQuery,
      PrincipalRepository principalRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new TaskIntentApplicationService(
        conversationService,
        conversationRepository,
        participantRepository,
        conversationEventRepository,
        taskIntentRepository,
        workProjectRepository,
        membershipQuery,
        principalRepository,
        domainEventStore,
        outboxRepository,
        receiptStore,
        transactionExecutor,
        timeProvider);
  }

  @Bean
  TaskIntentConfirmationCommandPort taskIntentConfirmationCommandPort(
      TaskIntentApplicationService taskIntentService,
      TaskIntentRepository taskIntentRepository,
      ConversationRepository conversationRepository,
      ConversationWorkItemLinkRepository linkRepository,
      WorkProjectRepository workProjectRepository,
      WorkItemRepository workItemRepository,
      WorkItemAccessPolicy workItemAccessPolicy,
      TeamRepository teamRepository,
      TeamMembershipQuery membershipQuery,
      PrincipalRepository principalRepository,
      ResponsibilityAssignmentRepository assignmentRepository,
      GateReviewerPolicyProvider reviewerPolicyProvider,
      BuiltInProviderRegistration registration,
      ProviderBindingResolver bindingResolver,
      DomainEventStore domainEventStore,
      ConversationEventRepository conversationEventRepository,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new TaskIntentConfirmationService(
        taskIntentService,
        taskIntentRepository,
        conversationRepository,
        linkRepository,
        workProjectRepository,
        workItemRepository,
        workItemAccessPolicy,
        teamRepository,
        membershipQuery,
        principalRepository,
        assignmentRepository,
        reviewerPolicyProvider,
        registration,
        bindingResolver,
        domainEventStore,
        conversationEventRepository,
        outboxRepository,
        receiptStore,
        transactionExecutor,
        timeProvider);
  }

  @Bean
  ConversationWorkItemQueryService conversationWorkItemQueryService(
      ConversationApplicationService conversationService,
      ConversationWorkItemLinkRepository linkRepository,
      WorkItemAccessPolicy workItemAccessPolicy,
      TransactionExecutor transactionExecutor) {
    return new ConversationWorkItemQueryService(
        conversationService, linkRepository, workItemAccessPolicy, transactionExecutor);
  }
}
