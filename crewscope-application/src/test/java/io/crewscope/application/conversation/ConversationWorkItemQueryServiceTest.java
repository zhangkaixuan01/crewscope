package io.crewscope.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.ConversationWorkItemLink;
import io.crewscope.domain.conversation.ConversationWorkItemLinkOrigin;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Proves counterpart authorization is applied independently in both link directions. */
class ConversationWorkItemQueryServiceTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-11T12:00:00Z");

  @Test
  void readsTheWorkItemOnlyAfterTheConversationAndWorkItemPoliciesBothPass() {
    Fixture fixture = new Fixture();
    when(fixture.conversationService.get(
            fixture.access,
            fixture.organizationId,
            fixture.team.team().id(),
            fixture.conversation.conversation().id()))
        .thenReturn(new ConversationDetails(fixture.conversation.conversation(), List.of()));
    when(fixture.linkRepository.findLinksByConversation(
            fixture.organizationId, fixture.conversation.conversation().id()))
        .thenReturn(List.of(fixture.link));
    when(fixture.workItemAccessPolicy.requireVisibleWorkItem(
            fixture.access,
            fixture.organizationId,
            fixture.team.team().id(),
            fixture.project.id(),
            fixture.workItem.id()))
        .thenReturn(fixture.workItem);

    List<ConversationWorkItemAssociation> result =
        fixture.service.byConversation(
            fixture.access,
            fixture.organizationId,
            fixture.team.team().id(),
            fixture.conversation.conversation().id());

    assertEquals(List.of(fixture.workItem.id()), result.stream().map(v -> v.workItem().id()).toList());
  }

  @Test
  void hidesAPrivateConversationFromAnAuthorizedWorkItemViewer() {
    Fixture fixture = new Fixture();
    when(fixture.workItemAccessPolicy.requireVisibleWorkItem(
            fixture.access,
            fixture.organizationId,
            fixture.team.team().id(),
            fixture.project.id(),
            fixture.workItem.id()))
        .thenReturn(fixture.workItem);
    when(fixture.linkRepository.findLinksByWorkItem(
            fixture.organizationId, fixture.workItem.id()))
        .thenReturn(List.of(fixture.link));
    when(fixture.conversationService.get(any(), any(), any(), any()))
        .thenThrow(new PolicyDeniedException("discover this private Conversation"));

    assertEquals(
        List.of(),
        fixture.service.byWorkItem(
            fixture.access,
            fixture.organizationId,
            fixture.team.team().id(),
            fixture.project.id(),
            fixture.workItem.id()));
  }

  private static final class Fixture {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal owner =
        Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization team = TeamInitialization.create(owner, "CrewScope", NOW);
    private final PersonalConversationInitialization conversation =
        PersonalConversationInitialization.start(
            ConversationId.generate(),
            team.defaultWorkspace(),
            team.ownerMember(),
            owner,
            team.ownerPersonalAgent(),
            "Private work discussion",
            ConversationVisibility.PRIVATE,
            NOW);
    private final WorkProject project =
        WorkProject.create(
            WorkProjectId.generate(),
            new WorkProjectKey("CRW"),
            "CrewScope",
            team.team(),
            team.defaultWorkspace(),
            owner,
            NOW);
    private final WorkItem workItem =
        WorkItem.createNative(
            WorkItemId.generate(),
            project,
            new WorkItemKey("CRW-1"),
            WorkItemType.TASK,
            "Protect private links",
            Optional.empty(),
            WorkItemPriority.MEDIUM,
            Set.of(),
            Optional.empty(),
            owner,
            NOW);
    private final ConversationWorkItemLink link =
        ConversationWorkItemLink.link(
            conversation.conversation(),
            workItem,
            ConversationWorkItemLinkOrigin.TASK_INTENT_CONFIRMATION,
            owner,
            NOW);
    private final TeamAccessContext access = new TeamAccessContext(owner, false);
    private final ConversationApplicationService conversationService =
        mock(ConversationApplicationService.class);
    private final ConversationWorkItemLinkRepository linkRepository =
        mock(ConversationWorkItemLinkRepository.class);
    private final WorkItemAccessPolicy workItemAccessPolicy = mock(WorkItemAccessPolicy.class);
    private final ConversationWorkItemQueryService service =
        new ConversationWorkItemQueryService(
            conversationService,
            linkRepository,
            workItemAccessPolicy,
            new TransactionExecutor() {
              @Override
              public <T> T required(Supplier<T> operation) {
                return operation.get();
              }
            });
  }
}
