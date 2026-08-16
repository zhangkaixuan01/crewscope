package io.crewscope.server.config.application;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.GateReviewerAssignmentService;
import io.crewscope.application.responsibility.GateReviewerPolicyProvider;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentService;
import io.crewscope.application.responsibility.ResponsibilityCommandService;
import io.crewscope.application.responsibility.ResponsibilityQueryService;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
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
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires WorkProject and WorkItem use cases to persistence, events and transaction ports. */
@Configuration(proxyBeanMethods = false)
public class WorkItemApplicationConfiguration {

  @Bean
  ResponsibilityAssignmentService responsibilityAssignmentService(
      ResponsibilityAssignmentRepository responsibilityAssignmentRepository,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new ResponsibilityAssignmentService(
        responsibilityAssignmentRepository, transactionExecutor, timeProvider);
  }

  @Bean
  GateReviewerAssignmentService gateReviewerAssignmentService(
      ResponsibilityAssignmentRepository responsibilityAssignmentRepository,
      TeamMembershipQuery teamMembershipQuery,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new GateReviewerAssignmentService(
        responsibilityAssignmentRepository,
        teamMembershipQuery,
        transactionExecutor,
        timeProvider);
  }

  /** M1 defaults to strict separation; a later PolicyPack adapter replaces this provider. */
  @Bean
  GateReviewerPolicyProvider gateReviewerPolicyProvider() {
    return workItem -> ReviewerEligibilityPolicy.strict();
  }

  @Bean
  ResponsibilityQueryService responsibilityQueryService(
      ResponsibilityAssignmentRepository responsibilityAssignmentRepository,
      PrincipalRepository principalRepository,
      AgentProfileRepository agentProfileRepository,
      WorkItemAccessPolicy workItemAccessPolicy,
      TransactionExecutor transactionExecutor) {
    return new ResponsibilityQueryService(
        responsibilityAssignmentRepository,
        principalRepository,
        agentProfileRepository,
        workItemAccessPolicy,
        transactionExecutor);
  }

  @Bean
  ResponsibilityCommandService responsibilityCommandService(
      ResponsibilityAssignmentRepository responsibilityAssignmentRepository,
      ResponsibilityAssignmentService responsibilityAssignmentService,
      GateReviewerAssignmentService gateReviewerAssignmentService,
      GateReviewerPolicyProvider gateReviewerPolicyProvider,
      WorkItemAccessPolicy workItemAccessPolicy,
      PrincipalRepository principalRepository,
      TeamMembershipQuery teamMembershipQuery,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore commandReceiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new ResponsibilityCommandService(
        responsibilityAssignmentRepository,
        responsibilityAssignmentService,
        gateReviewerAssignmentService,
        gateReviewerPolicyProvider,
        workItemAccessPolicy,
        principalRepository,
        teamMembershipQuery,
        domainEventStore,
        outboxRepository,
        commandReceiptStore,
        transactionExecutor,
        timeProvider);
  }

  @Bean
  WorkItemAccessPolicy workItemAccessPolicy(
      WorkItemRepository workItemRepository,
      WorkProjectRepository workProjectRepository,
      TeamRepository teamRepository,
      TeamMembershipQuery teamMembershipQuery,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository) {
    return new WorkItemAccessPolicy(
        workItemRepository,
        workProjectRepository,
        teamRepository,
        teamMembershipQuery,
        teamRoleRepository,
        memberRoleRepository);
  }

  @Bean
  WorkItemQueryService workItemQueryService(
      WorkItemRepository workItemRepository,
      WorkItemCommentRepository workItemCommentRepository,
      WorkItemResourceLinkRepository workItemResourceLinkRepository,
      WorkItemAccessPolicy workItemAccessPolicy,
      TransactionExecutor transactionExecutor) {
    return new WorkItemQueryService(
        workItemRepository,
        workItemCommentRepository,
        workItemResourceLinkRepository,
        workItemAccessPolicy,
        transactionExecutor);
  }

  @Bean
  WorkItemTimelineService workItemTimelineService(
      WorkItemTimelineRepository workItemTimelineRepository,
      WorkItemAccessPolicy workItemAccessPolicy,
      TransactionExecutor transactionExecutor) {
    return new WorkItemTimelineService(
        workItemTimelineRepository, workItemAccessPolicy, transactionExecutor);
  }

  @Bean
  WorkItemCollaborationService workItemCollaborationService(
      WorkItemCommentRepository workItemCommentRepository,
      WorkItemResourceLinkRepository workItemResourceLinkRepository,
      WorkItemAccessPolicy workItemAccessPolicy,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore commandReceiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new WorkItemCollaborationService(
        workItemCommentRepository,
        workItemResourceLinkRepository,
        workItemAccessPolicy,
        domainEventStore,
        outboxRepository,
        commandReceiptStore,
        transactionExecutor,
        timeProvider);
  }

  @Bean
  WorkItemCommandService workItemCommandService(
      WorkItemRepository workItemRepository,
      WorkProjectRepository workProjectRepository,
      TeamRepository teamRepository,
      TeamMembershipQuery teamMembershipQuery,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository,
      ResponsibilityAssignmentRepository responsibilityAssignmentRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore commandReceiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new WorkItemCommandService(
        workItemRepository,
        workProjectRepository,
        teamRepository,
        teamMembershipQuery,
        teamRoleRepository,
        memberRoleRepository,
        responsibilityAssignmentRepository,
        domainEventStore,
        outboxRepository,
        commandReceiptStore,
        transactionExecutor,
        timeProvider);
  }

  @Bean
  WorkProjectApplicationService workProjectApplicationService(
      WorkProjectRepository workProjectRepository,
      TeamRepository teamRepository,
      WorkspaceRepository workspaceRepository,
      TeamMembershipQuery teamMembershipQuery,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore commandReceiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new WorkProjectApplicationService(
        workProjectRepository,
        teamRepository,
        workspaceRepository,
        teamMembershipQuery,
        teamRoleRepository,
        memberRoleRepository,
        domainEventStore,
        outboxRepository,
        commandReceiptStore,
        transactionExecutor,
        timeProvider);
  }
}
