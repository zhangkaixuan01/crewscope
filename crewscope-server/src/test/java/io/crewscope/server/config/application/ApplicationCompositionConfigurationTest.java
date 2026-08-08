package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.IdentityMappingService;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.GateReviewerAssignmentService;
import io.crewscope.application.responsibility.GateReviewerPolicyProvider;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentService;
import io.crewscope.application.responsibility.ResponsibilityCommandService;
import io.crewscope.application.responsibility.ResponsibilityQueryService;
import io.crewscope.application.team.DefaultPersonalAgentRepository;
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
import io.crewscope.domain.shared.time.TimeProvider;
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
          .withBean(
              ResponsibilityAssignmentRepository.class,
              () -> mock(ResponsibilityAssignmentRepository.class));

  @Test
  void wiresEachFrameworkFreeApplicationServiceExactlyOnce() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(TimeProvider.class);
          assertThat(context).hasSingleBean(IdentityMappingService.class);
          assertThat(context).hasSingleBean(TeamApplicationService.class);
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
