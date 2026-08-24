package io.crewscope.server.api;

import io.crewscope.application.coding.CreateCodingTargetCommand;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.task.AgentTaskCreationService;
import io.crewscope.application.task.CreateAgentTaskCommand;
import io.crewscope.application.task.TaskConversationSource;
import io.crewscope.application.task.TaskAgentExecutionSelection;
import io.crewscope.application.task.TaskAgentSelectionRequest;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.ResolvedModelSelection;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Member-facing API for delegating one visible WorkItem to its assigned Agent. */
@RestController
@RequestMapping(
        "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}"
                + "/work-items/{workItemId}/tasks")
public final class TaskController {

    private final AgentTaskCreationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public TaskController(
            AgentTaskCreationService service, TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @PostMapping
    public Mono<ResponseEntity<CommandReceiptResponse>> create(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            @PathVariable String workItemId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CreateTaskRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, projectId, workItemId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        CreateAgentTaskCommand command = request.toCommand(expectedVersion);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> service.create(
                        new TeamCommandContext(
                                access, idempotencyKey, correlationId, Optional.empty()),
                        route.teamId(),
                        route.projectId(),
                        route.workItemId(),
                        command)))
                .map(CommandReceiptResponse::accepted);
    }

    /** Preflights the exact Agent/model graph that would be pinned by Task creation. */
    @PostMapping("/preflight")
    public Mono<AgentPreflightResponse> preflight(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            @PathVariable String workItemId,
            @Valid @RequestBody AgentSelectionRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, projectId, workItemId);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> service.preview(
                        access,
                        route.teamId(),
                        route.projectId(),
                        route.workItemId(),
                        request.toSelection())))
                .map(AgentPreflightResponse::from);
    }

    private static Route route(
            String organizationId, String teamId, String projectId, String workItemId) {
        try {
            return new Route(
                    OrganizationId.from(organizationId),
                    TeamId.from(teamId),
                    WorkProjectId.from(projectId),
                    WorkItemId.from(workItemId));
        } catch (IllegalArgumentException exception) {
            throw new ApiRequestException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid identifier",
                    Map.of("route", "tasks"));
        }
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record CreateTaskRequest(
            @NotBlank @Size(max = TaskBrief.MAX_OBJECTIVE_LENGTH) String objective,
            @NotNull
                    @Size(min = 1, max = TaskBrief.MAX_ACCEPTANCE_CRITERIA)
                    List<@NotBlank @Size(max = TaskBrief.MAX_ACCEPTANCE_CRITERION_LENGTH) String>
                            acceptanceCriteria,
            @NotNull UUID executorAgentProfileId,
            @Min(1) Long agentConfigurationRevision,
            @Valid ConversationSourceRequest conversationSource,
            @NotNull @Size(max = 200) Set<@NotNull UUID> providerBindingIds,
            @Valid CodingTargetRequest codingTarget) {

        CreateAgentTaskCommand toCommand(long expectedVersion) {
            return new CreateAgentTaskCommand(
                    new TaskBrief(objective, acceptanceCriteria),
                    new AgentProfileId(executorAgentProfileId),
                    Optional.ofNullable(agentConfigurationRevision)
                            .map(AgentConfigurationRevision::new),
                    Optional.ofNullable(conversationSource)
                            .map(ConversationSourceRequest::toSource),
                    providerBindingIds.stream()
                            .map(ProviderBindingId::new)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    Optional.ofNullable(codingTarget).map(CodingTargetRequest::toCommand),
                    expectedVersion);
        }
    }

    public record AgentSelectionRequest(
            @NotNull UUID executorAgentProfileId,
            @Min(1) Long agentConfigurationRevision) {

        TaskAgentSelectionRequest toSelection() {
            return new TaskAgentSelectionRequest(
                    new AgentProfileId(executorAgentProfileId),
                    Optional.ofNullable(agentConfigurationRevision)
                            .map(AgentConfigurationRevision::new));
        }
    }

    /** Secret-free response containing the immutable coordinates used by a Task attempt. */
    public record AgentPreflightResponse(
            UUID agentProfileId,
            long agentProfileVersion,
            String executionScope,
            long configurationRevision,
            String configurationHash,
            String bindingSource,
            String templateVersion,
            ModelSelectionResponse primary,
            Optional<ModelSelectionResponse> fallback,
            UUID policyPackId,
            long policyPackVersion,
            String resolutionHash) {

        static AgentPreflightResponse from(TaskAgentExecutionSelection selection) {
            var resolved = selection.resolvedConfiguration();
            return new AgentPreflightResponse(
                    resolved.agentProfileId().value(),
                    resolved.agentProfileVersion(),
                    resolved.executionScope().name(),
                    resolved.configurationRevision().value(),
                    resolved.configurationHash().toString(),
                    resolved.bindingSource().name(),
                    resolved.templateVersion().toString(),
                    ModelSelectionResponse.from(resolved.primary()),
                    resolved.fallback().map(ModelSelectionResponse::from),
                    resolved.configurationPolicyPack().id().value(),
                    resolved.configurationPolicyPack().version(),
                    resolved.resolutionHash().toString());
        }
    }

    public record ModelSelectionResponse(
            String role,
            String providerKey,
            UUID connectionId,
            String connectionOwnerType,
            String modelId,
            long catalogRevision,
            String modelRevision,
            long priceRevision) {

        static ModelSelectionResponse from(ResolvedModelSelection value) {
            return new ModelSelectionResponse(
                    value.role().name(),
                    value.providerKey().toString(),
                    value.connectionId().value(),
                    value.connectionOwner().type().name(),
                    value.catalogCoordinate().modelId().toString(),
                    value.catalogCoordinate().catalogRevision().value(),
                    value.modelRevision().toString(),
                    value.priceRevision());
        }
    }

    /** Optional repository target; omitting it preserves the non-Coding Task contract. */
    public record CodingTargetRequest(
            @NotNull UUID repositoryBindingId,
            @NotBlank @Size(max = RepositoryBranchName.MAX_LENGTH) String baselineRef,
            @NotNull
                    @Size(min = 1, max = CodingTargetAllowedPaths.MAX_PATHS)
                    List<@NotBlank @Size(max = CodingTargetAllowedPaths.MAX_PATH_LENGTH) String>
                            allowedPaths,
            @NotNull @Valid BuildProfileRequest buildProfile) {

        CreateCodingTargetCommand toCommand() {
            return new CreateCodingTargetCommand(
                    new RepositoryBindingId(repositoryBindingId),
                    new RepositoryBranchName(baselineRef),
                    new CodingTargetAllowedPaths(allowedPaths),
                    buildProfile.toReference());
        }
    }

    /** Exact immutable BuildProfile reference; silent version fall-forward is forbidden. */
    public record BuildProfileRequest(
            @NotBlank @Pattern(regexp = BuildProfileReference.KEY_REGEX) String key,
            @Min(1) long version,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String profileHash) {

        BuildProfileReference toReference() {
            return new BuildProfileReference(key, version, new TaskFactHash(profileHash));
        }
    }

    public record ConversationSourceRequest(
            @NotNull UUID conversationId, @NotNull UUID messageId) {

        TaskConversationSource toSource() {
            return new TaskConversationSource(
                    new ConversationId(conversationId), new MessageId(messageId));
        }
    }

    private record Route(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId) {}
}
