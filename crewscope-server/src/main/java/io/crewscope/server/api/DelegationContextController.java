package io.crewscope.server.api;

import io.crewscope.application.task.DelegationContext;
import io.crewscope.application.task.DelegationContextService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * One read joins every fact the unified delegation form needs (M9b-A05): the responsibility chain,
 * assignable Agent candidates with their conflicts, the A04 project defaults, the in-flight
 * execution fact and the caller's own authority. Model/config preflight stays on its endpoint.
 */
@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}"
        + "/work-items/{workItemId}/delegation-context")
public final class DelegationContextController {

  private final DelegationContextService service;
  private final TeamRequestIdentityResolver identityResolver;

  public DelegationContextController(
      DelegationContextService service, TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  @GetMapping
  public Mono<ResponseEntity<DelegationContextResponse>> get(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      Authentication authentication,
      ServerWebExchange exchange) {
    Scope scope = scope(organizationId, teamId, projectId, workItemId);
    return identityResolver
        .resolve(authentication, scope.organizationId(), ApiCorrelationIds.resolve(exchange))
        .flatMap(
            access ->
                blocking(
                    () ->
                        service.getContext(
                            access, scope.teamId(), scope.projectId(), scope.workItemId())))
        .map(
            value ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(DelegationContextResponse.from(value)));
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  private static Scope scope(
      String organizationId, String teamId, String projectId, String workItemId) {
    return new Scope(
        organizationId(organizationId),
        teamId(teamId),
        projectId(projectId),
        workItemId(workItemId));
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

  public record DelegationContextResponse(
      WorkItemLineResponse workItem,
      List<ResponsibilityLineResponse> responsibilities,
      List<AgentCandidateResponse> candidates,
      DefaultsResponse defaults,
      boolean activeExecution,
      PermissionsResponse permissions) {

    static DelegationContextResponse from(DelegationContext value) {
      return new DelegationContextResponse(
          new WorkItemLineResponse(
              value.workItem().id().toString(),
              value.workItem().projectId().toString(),
              value.workItem().version(),
              value.workItem().title(),
              value.workItem().status().name()),
          value.responsibilities().stream().map(ResponsibilityLineResponse::from).toList(),
          value.candidates().stream().map(AgentCandidateResponse::from).toList(),
          DefaultsResponse.from(value.defaults()),
          value.activeExecution(),
          new PermissionsResponse(
              value.permissions().canAssignResponsibility(),
              value.permissions().canDelegate()));
    }
  }

  public record WorkItemLineResponse(
      String id, String projectId, long version, String title, String status) {}

  public record ResponsibilityLineResponse(
      String assignmentId,
      String role,
      String actorPrincipalId,
      String actorType,
      String actorDisplayName,
      String actorAgentProfileId,
      long version) {

    static ResponsibilityLineResponse from(DelegationContext.ResponsibilityLine line) {
      return new ResponsibilityLineResponse(
          line.assignmentId().toString(),
          line.role().name(),
          line.actorPrincipalId().toString(),
          line.actorType(),
          line.actorDisplayName(),
          line.actorAgentProfileId().map(Object::toString).orElse(null),
          line.version());
    }
  }

  public record AgentCandidateResponse(
      String agentProfileId,
      long agentProfileVersion,
      String agentPrincipalId,
      String displayName,
      String ownershipType,
      String runtimeRole,
      String state,
      String reason) {

    static AgentCandidateResponse from(DelegationContext.AgentCandidate candidate) {
      return new AgentCandidateResponse(
          candidate.agentProfileId().toString(),
          candidate.agentProfileVersion(),
          candidate.agentPrincipalId().toString(),
          candidate.displayName(),
          candidate.ownershipType(),
          candidate.runtimeRole(),
          candidate.state(),
          candidate.reason().orElse(null));
    }
  }

  public record DefaultField<T>(T value, String source, String availability, String reason) {}

  public record DefaultsResponse(
      long version,
      DefaultField<String> repositoryBindingId,
      DefaultField<Long> repositoryBindingVersion,
      DefaultField<String> branch,
      DefaultField<BuildProfileRequest> buildProfile,
      DefaultField<String> agentProfileId,
      DefaultField<Long> agentProfileRevision) {

    /** Mirrors the A04 execution-defaults derivation so both surfaces stay word-for-word equal. */
    static DefaultsResponse from(DelegationContext.DefaultsSnapshot value) {
      String repoReason =
          value.repositoryBindingId().isPresent() ? "项目已选择仓库绑定" : "尚未设置项目仓库";
      String repoAvailability =
          value.repositoryBindingId().isPresent() ? "AVAILABLE" : "MISSING";
      String profileReason =
          value.buildProfile().isPresent() ? "项目已选择受控构建方案" : "尚未设置构建方案";
      String profileAvailability =
          value.buildProfile().isPresent() ? "AVAILABLE" : "MISSING";
      String agentReason =
          value.agentProfileId().isPresent() ? "项目已选择 Agent" : "使用任务/团队解析结果";
      return new DefaultsResponse(
          value.version(),
          new DefaultField<>(
              value.repositoryBindingId().map(RepositoryBindingId::toString).orElse(null),
              "PROJECT_DEFAULT", repoAvailability, repoReason),
          new DefaultField<>(
              value.repositoryBindingVersion().orElse(null),
              "PROJECT_DEFAULT", repoAvailability, repoReason),
          new DefaultField<>(
              value.branch().map(RepositoryBranchName::value).orElse(null),
              "PROJECT_DEFAULT",
              value.branch().isPresent() ? "AVAILABLE" : "MISSING",
              value.branch().isPresent() ? "项目默认分支" : "仓库绑定默认分支将被使用"),
          new DefaultField<>(
              value.buildProfile()
                  .map(reference -> new BuildProfileRequest(
                      reference.key(), reference.version(), reference.profileHash().value()))
                  .orElse(null),
              "PROJECT_DEFAULT", profileAvailability, profileReason),
          new DefaultField<>(
              value.agentProfileId().map(Object::toString).orElse(null),
              "PROJECT_DEFAULT",
              value.agentProfileId().isPresent() ? "AVAILABLE" : "INHERITED",
              agentReason),
          new DefaultField<>(
              value.agentProfileRevision().orElse(null),
              "PROJECT_DEFAULT",
              value.agentProfileRevision().isPresent() ? "AVAILABLE" : "INHERITED",
              agentReason));
    }
  }

  public record BuildProfileRequest(String key, Long version, String profileHash) {}

  public record PermissionsResponse(
      boolean canAssignResponsibility, boolean canDelegate) {}

  private record Scope(
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId) {}
}
