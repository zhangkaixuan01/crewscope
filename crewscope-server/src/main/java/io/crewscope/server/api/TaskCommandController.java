package io.crewscope.server.api;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.task.MemberTaskCommandService;
import io.crewscope.application.task.MemberTaskControlCommand;
import io.crewscope.application.task.RetryTaskCommand;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
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

/** Member-facing durable Pause, Resume, Cancel and Retry Task commands. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}"
        + "/attempts/{executionId}")
public final class TaskCommandController {

    private final MemberTaskCommandService service;
    private final TeamRequestIdentityResolver identityResolver;

    public TaskCommandController(
            MemberTaskCommandService service, TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/pause")
    public Mono<ResponseEntity<CommandReceiptResponse>> pause(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ControlRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        return command(authentication, route, key, exchange, context -> service.pause(
                context,
                route.teamId(),
                route.taskId(),
                route.executionId(),
                new MemberTaskControlCommand(expectedVersion, request.reason())));
    }

    @PostMapping("/resume")
    public Mono<ResponseEntity<CommandReceiptResponse>> resume(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) String body,
            Authentication authentication,
            ServerWebExchange exchange) {
        requireEmptyBody(body);
        Route route = route(organizationId, teamId, taskId, executionId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        return command(authentication, route, key, exchange, context -> service.resume(
                context,
                route.teamId(),
                route.taskId(),
                route.executionId(),
                new RetryTaskCommand(expectedVersion)));
    }

    @PostMapping("/cancel")
    public Mono<ResponseEntity<CommandReceiptResponse>> cancel(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ControlRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        return command(authentication, route, key, exchange, context -> service.cancel(
                context,
                route.teamId(),
                route.taskId(),
                route.executionId(),
                new MemberTaskControlCommand(expectedVersion, request.reason())));
    }

    @PostMapping("/retry")
    public Mono<ResponseEntity<CommandReceiptResponse>> retry(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) String body,
            Authentication authentication,
            ServerWebExchange exchange) {
        requireEmptyBody(body);
        Route route = route(organizationId, teamId, taskId, executionId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        return command(authentication, route, key, exchange, context -> service.retry(
                context,
                route.teamId(),
                route.taskId(),
                route.executionId(),
                new RetryTaskCommand(expectedVersion)));
    }

    private Mono<ResponseEntity<CommandReceiptResponse>> command(
            Authentication authentication,
            Route route,
            String key,
            ServerWebExchange exchange,
            Function<TeamCommandContext, io.crewscope.application.command.CommandExecution<
                            io.crewscope.application.task.MemberTaskCommandResult>> action) {
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> action.apply(new TeamCommandContext(
                        access, idempotencyKey, correlationId, Optional.empty()))))
                .map(CommandReceiptResponse::accepted);
    }

    private static Route route(
            String organizationId, String teamId, String taskId, String executionId) {
        try {
            return new Route(
                    OrganizationId.from(organizationId),
                    TeamId.from(teamId),
                    TaskId.from(taskId),
                    TaskExecutionId.from(executionId));
        } catch (IllegalArgumentException exception) {
            throw new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid identifier",
                    Map.of("route", "task-command"));
        }
    }

    private static void requireEmptyBody(String body) {
        if (body != null && !body.isBlank()) {
            throw new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "This Task command does not accept a request body",
                    Map.of("field", "body"));
        }
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record ControlRequest(
            @NotBlank @Size(max = MemberTaskControlCommand.MAX_REASON_LENGTH) String reason) {}

    private record Route(
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {}
}
