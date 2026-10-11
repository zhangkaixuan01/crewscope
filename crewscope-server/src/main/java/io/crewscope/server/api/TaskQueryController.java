package io.crewscope.server.api;

import io.crewscope.application.task.TaskListCursor;
import io.crewscope.application.task.TaskQueryService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Member-facing Task discovery and safe durable Runtime fact APIs. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/tasks")
public final class TaskQueryController {

    private final TaskQueryService service;
    private final TeamRequestIdentityResolver identityResolver;
    private final TaskListCursorCodec cursorCodec = new TaskListCursorCodec();

    public TaskQueryController(
            TaskQueryService service, TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public Mono<ResponseEntity<TaskPageResponse>> list(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) String ownerPrincipalId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        Optional<WorkProjectId> project = Optional.ofNullable(projectId)
                .map(value -> projectId(value, "projectId"));
        Optional<TaskStatus> selectedStatus = Optional.ofNullable(status);
        Optional<PrincipalId> selectedOwner = Optional.ofNullable(ownerPrincipalId)
                .map(value -> principalId(value, "ownerPrincipalId"));
        Optional<TaskListCursor> cursor = Optional.ofNullable(after).map(value -> cursorCodec.decode(
                value,
                route.organizationId(),
                route.teamId(),
                project,
                selectedStatus,
                selectedOwner));
        return query(authentication, route.organizationId(), exchange, access -> service.list(
                        access,
                        route.organizationId(),
                        route.teamId(),
                        project,
                        selectedStatus,
                        selectedOwner,
                        cursor,
                        ApiPagination.limit(limit)))
                .map(page -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(TaskPageResponse.from(
                                page,
                                cursorCodec,
                                route.organizationId(),
                                route.teamId(),
                                project,
                                selectedStatus,
                                selectedOwner)));
    }

    @GetMapping("/{taskId}")
    public Mono<ResponseEntity<TaskDetailsResponse>> get(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        TaskId task = taskId(taskId, "taskId");
        return query(authentication, route.organizationId(), exchange, access ->
                        service.get(access, route.organizationId(), route.teamId(), task))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(Long.toString(value.task().version()))
                        .body(TaskDetailsResponse.from(value)));
    }

    @GetMapping("/{taskId}/attempts")
    public Mono<ResponseEntity<List<TaskExecutionResponse>>> attempts(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        TaskId task = taskId(taskId, "taskId");
        return query(authentication, route.organizationId(), exchange, access ->
                        service.attempts(access, route.organizationId(), route.teamId(), task))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(values.stream().map(TaskExecutionResponse::from).toList()));
    }

    @GetMapping("/{taskId}/attempts/{executionId}/runtime-facts")
    public Mono<ResponseEntity<TaskRuntimeFactsResponse>> runtimeFacts(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        TaskId task = taskId(taskId, "taskId");
        TaskExecutionId execution = executionId(executionId, "executionId");
        return query(authentication, route.organizationId(), exchange, access ->
                        service.runtimeFacts(
                                access, route.organizationId(), route.teamId(), task, execution))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(Long.toString(value.execution().version()))
                        .body(TaskRuntimeFactsResponse.from(value)));
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

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static Route route(String organizationId, String teamId) {
        try {
            return new Route(OrganizationId.from(organizationId), TeamId.from(teamId));
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("route");
        }
    }

    private static TaskId taskId(String value, String field) {
        try {
            return TaskId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier(field);
        }
    }

    private static TaskExecutionId executionId(String value, String field) {
        try {
            return TaskExecutionId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier(field);
        }
    }

    private static WorkProjectId projectId(String value, String field) {
        try {
            return WorkProjectId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier(field);
        }
    }

    private static PrincipalId principalId(String value, String field) {
        try {
            return PrincipalId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier(field);
        }
    }

    private static ApiRequestException invalidIdentifier(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", field));
    }

    private record Route(OrganizationId organizationId, TeamId teamId) {}
}
