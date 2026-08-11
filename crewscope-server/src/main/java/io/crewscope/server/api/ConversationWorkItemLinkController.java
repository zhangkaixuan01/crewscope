package io.crewscope.server.api;

import io.crewscope.application.conversation.ConversationWorkItemAssociation;
import io.crewscope.application.conversation.ConversationWorkItemQueryService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Read-only HTTP boundary for both authorized directions of Conversation/WorkItem relations. */
@RestController
public final class ConversationWorkItemLinkController {

  private final ConversationWorkItemQueryService service;
  private final TeamRequestIdentityResolver identityResolver;

  public ConversationWorkItemLinkController(
      ConversationWorkItemQueryService service, TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  @GetMapping(
      "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/work-items")
  public Mono<ResponseEntity<List<AssociationResponse>>> byConversation(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    ConversationId conversation = conversationId(conversationId);
    return query(
            authentication,
            organization,
            exchange,
            access -> service.byConversation(access, organization, team, conversation))
        .map(ConversationWorkItemLinkController::response);
  }

  @GetMapping(
      "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/conversations")
  public Mono<ResponseEntity<List<AssociationResponse>>> byWorkItem(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectId project = projectId(projectId);
    WorkItemId workItem = workItemId(workItemId);
    return query(
            authentication,
            organization,
            exchange,
            access -> service.byWorkItem(access, organization, team, project, workItem))
        .map(ConversationWorkItemLinkController::response);
  }

  private <T> Mono<T> query(
      Authentication authentication,
      OrganizationId organizationId,
      ServerWebExchange exchange,
      Function<TeamAccessContext, T> action) {
    return identityResolver
        .resolve(authentication, organizationId, ApiCorrelationIds.resolve(exchange))
        .flatMap(access -> blocking(() -> action.apply(access)));
  }

  private static ResponseEntity<List<AssociationResponse>> response(
      List<ConversationWorkItemAssociation> values) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(values.stream().map(AssociationResponse::from).toList());
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  private static OrganizationId organizationId(String value) {
    try {
      return OrganizationId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("organizationId");
    }
  }

  private static TeamId teamId(String value) {
    try {
      return TeamId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("teamId");
    }
  }

  private static ConversationId conversationId(String value) {
    try {
      return ConversationId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("conversationId");
    }
  }

  private static WorkProjectId projectId(String value) {
    try {
      return WorkProjectId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("projectId");
    }
  }

  private static WorkItemId workItemId(String value) {
    try {
      return WorkItemId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("workItemId");
    }
  }

  private static ApiRequestException invalidIdentifier(String field) {
    return new ApiRequestException(
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "invalid_request",
        "Request contains an invalid identifier",
        Map.of("field", field));
  }

  public record AssociationResponse(
      String linkId,
      String origin,
      String createdAt,
      ConversationSummaryResponse conversation,
      WorkItemSummaryResponse workItem) {

    static AssociationResponse from(ConversationWorkItemAssociation value) {
      return new AssociationResponse(
          value.link().id().toString(),
          value.link().origin().name(),
          value.link().audit().createdAt().toString(),
          new ConversationSummaryResponse(
              value.conversation().id().toString(),
              value.conversation().title(),
              value.conversation().visibility().name(),
              value.conversation().status().name()),
          new WorkItemSummaryResponse(
              value.workItem().id().toString(),
              value.workItem().scope().projectId().toString(),
              value.workItem().key().value(),
              value.workItem().title(),
              value.workItem().status().name()));
    }
  }

  public record ConversationSummaryResponse(
      String id, String title, String visibility, String status) {}

  public record WorkItemSummaryResponse(
      String id, String projectId, String key, String title, String status) {}
}
