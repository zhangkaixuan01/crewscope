package io.crewscope.server.collaboration;

import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.collaboration.CollaborationResourceScope;
import io.crewscope.domain.collaboration.CollaborationResourceType;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.collaboration.WorkProjectScope;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.server.api.TeamRequestIdentityResolver;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Product adjudication for subscribe frames: the existing resolver chain (principal-in-org plus
 * account securityVersion) followed by the same visibility policies the REST endpoints use —
 * there is no second authorization rulebook for the socket. Everything blocking rides the
 * boundedElastic scheduler; every failure mode resolves to {@link
 * CollaborationSubscriptionAuthorizer.Decision#DENIED}, so a missing resource and a forbidden
 * one are indistinguishable from the client's point of view (the ADR-032 probe-resistance line).
 */
public final class RepositoryCollaborationSubscriptionAuthorizer
    implements CollaborationSubscriptionAuthorizer {

  private static final Logger log =
      LoggerFactory.getLogger(RepositoryCollaborationSubscriptionAuthorizer.class);

  private final TeamRequestIdentityResolver identityResolver;
  private final WorkItemAccessPolicy workItemAccessPolicy;
  private final WorkItemRepository workItemRepository;
  private final ConversationApplicationService conversationService;
  private final Scheduler blockingBridge;

  public RepositoryCollaborationSubscriptionAuthorizer(
      TeamRequestIdentityResolver identityResolver,
      WorkItemAccessPolicy workItemAccessPolicy,
      WorkItemRepository workItemRepository,
      ConversationApplicationService conversationService) {
    this(
        identityResolver,
        workItemAccessPolicy,
        workItemRepository,
        conversationService,
        Schedulers.boundedElastic());
  }

  /** Test seam: the scheduler is injectable so a fake stack never needs a real pool. */
  RepositoryCollaborationSubscriptionAuthorizer(
      TeamRequestIdentityResolver identityResolver,
      WorkItemAccessPolicy workItemAccessPolicy,
      WorkItemRepository workItemRepository,
      ConversationApplicationService conversationService,
      Scheduler blockingBridge) {
    this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
    this.workItemAccessPolicy = Objects.requireNonNull(workItemAccessPolicy, "workItemAccessPolicy");
    this.workItemRepository = Objects.requireNonNull(workItemRepository, "workItemRepository");
    this.conversationService =
        Objects.requireNonNull(conversationService, "conversationService");
    this.blockingBridge = Objects.requireNonNull(blockingBridge, "blockingBridge");
  }

  @Override
  public Mono<Decision> authorize(Authentication authentication, CollaborationResourceScope scope) {
    return identityResolver
        .resolve(authentication, scope.organizationId(), UUID.randomUUID())
        .flatMap(
            context ->
                Mono.fromCallable(() -> decide(context, scope)).subscribeOn(blockingBridge))
        .map(allowed -> Decision.ALLOWED)
        .onErrorResume(
            failure -> {
              // Policy denials are expected traffic; only the cause matters for forensics.
              log.debug(
                  "collaboration subscription denied for scope {}", scope, failure);
              return Mono.just(Decision.DENIED);
            });
  }

  /** Runs the visibility policies; any throw means denied (fail-closed). */
  private boolean decide(TeamAccessContext context, CollaborationResourceScope scope) {
    if (scope instanceof TeamScope team) {
      workItemAccessPolicy.requireVisibleTeam(
          context, team.organizationId(), team.teamId());
      return true;
    }
    if (scope instanceof WorkProjectScope project) {
      workItemAccessPolicy.requireVisibleProject(
          context,
          project.organizationId(),
          project.teamId(),
          project.projectId());
      return true;
    }
    ResourceScope resource = (ResourceScope) scope;
    if (resource.type() == CollaborationResourceType.WORK_ITEM) {
      WorkItem item =
          workItemRepository
              .findById(resource.organizationId(), new WorkItemId(resource.resourceId()))
              .orElseThrow(
                  () ->
                      new AggregateNotFoundException(
                          "WorkItem", new WorkItemId(resource.resourceId())));
      workItemAccessPolicy.requireVisibleWorkItem(
          context,
          resource.organizationId(),
          resource.teamId(),
          item.scope().projectId(),
          item.id());
      return true;
    }
    // Conversation visibility (including PRIVATE probe-resistance) is the application
    // service's own rule; reusing it verbatim is the point of not writing a second one.
    conversationService.get(
        context,
        resource.organizationId(),
        resource.teamId(),
        new ConversationId(resource.resourceId()));
    return true;
  }
}
