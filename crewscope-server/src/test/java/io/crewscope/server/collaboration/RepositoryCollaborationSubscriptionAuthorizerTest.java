package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.collaboration.CollaborationResourceType;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.collaboration.WorkProjectScope;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.server.api.TeamRequestIdentityResolver;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.security.core.Authentication;

/**
 * The one adjudication seam: each granularity routes to its existing policy, and every failure
 * mode — resolver error, policy denial, missing aggregate, infrastructure throw — resolves to
 * DENIED, never an error signal. Presence of the resource stays indistinguishable from
 * permission on it.
 */
class RepositoryCollaborationSubscriptionAuthorizerTest {

  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
  private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
  private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
  private static final UUID ITEM = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
  private static final Duration BLOCK = Duration.ofSeconds(5);

  private final WorkItemAccessPolicy workItemAccessPolicy = mock(WorkItemAccessPolicy.class);
  private final WorkItemRepository workItemRepository = mock(WorkItemRepository.class);
  private final ConversationApplicationService conversations =
      mock(ConversationApplicationService.class);

  @Test
  void teamScopeAllowedWhenTheTeamPolicyPasses() {
    TeamAccessContext context = mock(TeamAccessContext.class);
    TeamScope scope = new TeamScope(new OrganizationId(ORG), new TeamId(TEAM));
    RepositoryCollaborationSubscriptionAuthorizer authorizer =
        authorizer((authentication, organizationId, correlationId) -> Mono.just(context));

    assertThat(authorizer.authorize(authentication(), scope).block(BLOCK))
        .isEqualTo(CollaborationSubscriptionAuthorizer.Decision.ALLOWED);
    verify(workItemAccessPolicy).requireVisibleTeam(context, scope.organizationId(), scope.teamId());
  }

  @Test
  void workProjectScopeRoutesToTheProjectPolicy() {
    TeamAccessContext context = mock(TeamAccessContext.class);
    WorkProjectScope scope =
        new WorkProjectScope(
            new OrganizationId(ORG), new TeamId(TEAM), new WorkProjectId(PROJECT));
    RepositoryCollaborationSubscriptionAuthorizer authorizer =
        authorizer((authentication, organizationId, correlationId) -> Mono.just(context));

    assertThat(authorizer.authorize(authentication(), scope).block(BLOCK))
        .isEqualTo(CollaborationSubscriptionAuthorizer.Decision.ALLOWED);
    verify(workItemAccessPolicy)
        .requireVisibleProject(
            context, scope.organizationId(), scope.teamId(), scope.projectId());
  }

  @Test
  void workItemScopeResolvesTheProjectThroughTheRepositoryBeforeAdjudicating() {
    TeamAccessContext context = mock(TeamAccessContext.class);
    ResourceScope scope =
        new ResourceScope(
            new OrganizationId(ORG), new TeamId(TEAM), CollaborationResourceType.WORK_ITEM, ITEM);
    WorkItem item = mock(WorkItem.class);
    when(item.scope())
        .thenReturn(
            new WorkItemScope(
                new OrganizationId(ORG),
                new TeamId(TEAM),
                new WorkspaceId(WORKSPACE),
                new WorkProjectId(PROJECT)));
    when(item.id()).thenReturn(new WorkItemId(ITEM));
    when(workItemRepository.findById(scope.organizationId(), new WorkItemId(ITEM)))
        .thenReturn(Optional.of(item));
    RepositoryCollaborationSubscriptionAuthorizer authorizer =
        authorizer((authentication, organizationId, correlationId) -> Mono.just(context));

    assertThat(authorizer.authorize(authentication(), scope).block(BLOCK))
        .isEqualTo(CollaborationSubscriptionAuthorizer.Decision.ALLOWED);
    // The policy sees the item's OWN project, not any client-supplied guess.
    verify(workItemAccessPolicy)
        .requireVisibleWorkItem(
            context,
            scope.organizationId(),
            scope.teamId(),
            new WorkProjectId(PROJECT),
            new WorkItemId(ITEM));
  }

  @Test
  void conversationScopeRoutesThroughTheConversationService() {
    TeamAccessContext context = mock(TeamAccessContext.class);
    ResourceScope scope =
        new ResourceScope(
            new OrganizationId(ORG),
            new TeamId(TEAM),
            CollaborationResourceType.CONVERSATION,
            ITEM);
    RepositoryCollaborationSubscriptionAuthorizer authorizer =
        authorizer((authentication, organizationId, correlationId) -> Mono.just(context));

    assertThat(authorizer.authorize(authentication(), scope).block(BLOCK))
        .isEqualTo(CollaborationSubscriptionAuthorizer.Decision.ALLOWED);
    verify(conversations)
        .get(eq(context), eq(scope.organizationId()), eq(scope.teamId()), any());
  }

  @Test
  void everyFailureModeDeniesWithoutErroring() {
    TeamAccessContext context = mock(TeamAccessContext.class);
    RepositoryCollaborationSubscriptionAuthorizer authorizer =
        authorizer((authentication, organizationId, correlationId) -> Mono.just(context));
    TeamScope teamScope = new TeamScope(new OrganizationId(ORG), new TeamId(TEAM));

    // Policy denial.
    doThrow(new PolicyDeniedException("access this Team's WorkItems"))
        .when(workItemAccessPolicy)
        .requireVisibleTeam(context, teamScope.organizationId(), teamScope.teamId());
    assertThat(authorizer.authorize(authentication(), teamScope).block(BLOCK))
        .isEqualTo(CollaborationSubscriptionAuthorizer.Decision.DENIED);

    // Missing aggregate (work_item not found).
    ResourceScope missing =
        new ResourceScope(
            new OrganizationId(ORG),
            new TeamId(TEAM),
            CollaborationResourceType.WORK_ITEM,
            ITEM);
    when(workItemRepository.findById(missing.organizationId(), new WorkItemId(ITEM)))
        .thenReturn(Optional.empty());
    assertThat(authorizer.authorize(authentication(), missing).block(BLOCK))
        .isEqualTo(CollaborationSubscriptionAuthorizer.Decision.DENIED);
    verify(workItemAccessPolicy, never())
        .requireVisibleWorkItem(any(), any(), any(), any(), any());

    // Infrastructure throw from the conversation service.
    ResourceScope conversation =
        new ResourceScope(
            new OrganizationId(ORG),
            new TeamId(TEAM),
            CollaborationResourceType.CONVERSATION,
            ITEM);
    when(conversations.get(eq(context), eq(conversation.organizationId()), eq(conversation.teamId()), any()))
        .thenThrow(new AggregateNotFoundException("Conversation", new WorkItemId(ITEM)));
    assertThat(authorizer.authorize(authentication(), conversation).block(BLOCK))
        .isEqualTo(CollaborationSubscriptionAuthorizer.Decision.DENIED);

    // Resolver failure.
    RepositoryCollaborationSubscriptionAuthorizer failing =
        authorizer((authentication, organizationId, correlationId) -> Mono.error(new IllegalStateException("db down")));
    assertThat(failing.authorize(authentication(), teamScope).block(BLOCK))
        .isEqualTo(CollaborationSubscriptionAuthorizer.Decision.DENIED);
  }

  private RepositoryCollaborationSubscriptionAuthorizer authorizer(
      TeamRequestIdentityResolver resolver) {
    return new RepositoryCollaborationSubscriptionAuthorizer(
        resolver,
        workItemAccessPolicy,
        workItemRepository,
        conversations,
        Schedulers.immediate());
  }

  private static Authentication authentication() {
    return mock(Authentication.class);
  }
}
