package io.crewscope.server.api;

import io.crewscope.application.coding.query.CodingAttemptProjection;
import io.crewscope.application.coding.query.CodingEvidenceCursor;
import io.crewscope.application.coding.query.CodingEvidencePage;
import io.crewscope.application.coding.query.CommandEvidenceProjection;
import io.crewscope.application.coding.query.TaskCodingQueryService;
import io.crewscope.application.coding.query.TaskCodingQueryService.AttemptView;
import io.crewscope.application.coding.query.TestEvidenceProjection;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

/** Conversation and management UI read boundary for durable Coding attempt facts. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}")
public final class TaskCodingQueryController {

    private final TaskCodingQueryService service;
    private final TeamRequestIdentityResolver identityResolver;
    private final CodingEvidenceCursorCodec cursorCodec = new CodingEvidenceCursorCodec();

    public TaskCodingQueryController(
            TaskCodingQueryService service, TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/coding")
    public Mono<ResponseEntity<CurrentAttemptResponse>> current(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId);
        return query(authentication, route.organizationId(), exchange,
                        access -> service.current(access, route.organizationId(), route.teamId(), route.taskId()))
                .map(value -> noStore(new CurrentAttemptResponse(
                        value.taskId().value(), value.currentAttempt().map(AttemptResponse::from).orElse(null))));
    }

    @GetMapping("/coding-attempts")
    public Mono<ResponseEntity<List<AttemptResponse>>> attempts(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId);
        return query(authentication, route.organizationId(), exchange,
                        access -> service.attempts(access, route.organizationId(), route.teamId(), route.taskId()))
                .map(values -> noStore(values.stream().map(AttemptResponse::from).toList()));
    }

    @GetMapping("/attempts/{executionId}/coding")
    public Mono<ResponseEntity<AttemptResponse>> attempt(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId);
        TaskExecutionId execution = executionId(executionId);
        return query(authentication, route.organizationId(), exchange,
                        access -> service.attempt(access, route.organizationId(), route.teamId(),
                                route.taskId(), execution))
                .map(value -> noStore(AttemptResponse.from(value)));
    }

    @GetMapping("/attempts/{executionId}/coding/commands")
    public Mono<ResponseEntity<CommandPageResponse>> commands(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId);
        TaskExecutionId execution = executionId(executionId);
        Optional<CodingEvidenceCursor> cursor = Optional.ofNullable(after).map(value -> cursorCodec.decode(
                value, route.organizationId(), route.teamId(), route.taskId(), execution,
                CodingEvidenceCursorCodec.Collection.COMMANDS));
        return query(authentication, route.organizationId(), exchange,
                        access -> service.commands(access, route.organizationId(), route.teamId(), route.taskId(),
                                execution, cursor, ApiPagination.limit(limit)))
                .map(page -> noStore(CommandPageResponse.from(page, cursorCodec, route, execution)));
    }

    @GetMapping("/attempts/{executionId}/coding/test-evidence")
    public Mono<ResponseEntity<TestEvidencePageResponse>> testEvidence(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId);
        TaskExecutionId execution = executionId(executionId);
        Optional<CodingEvidenceCursor> cursor = Optional.ofNullable(after).map(value -> cursorCodec.decode(
                value, route.organizationId(), route.teamId(), route.taskId(), execution,
                CodingEvidenceCursorCodec.Collection.TEST_EVIDENCE));
        return query(authentication, route.organizationId(), exchange,
                        access -> service.testEvidence(access, route.organizationId(), route.teamId(), route.taskId(),
                                execution, cursor, ApiPagination.limit(limit)))
                .map(page -> noStore(TestEvidencePageResponse.from(page, cursorCodec, route, execution)));
    }

    private <T> Mono<T> query(Authentication authentication, OrganizationId organizationId,
            ServerWebExchange exchange, Function<TeamAccessContext, T> action) {
        return identityResolver.resolve(authentication, organizationId, ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> action.apply(access)));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static Route route(String organization, String team, String task) {
        try {
            return new Route(OrganizationId.from(organization), TeamId.from(team), TaskId.from(task));
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("route");
        }
    }

    private static TaskExecutionId executionId(String value) {
        try {
            return TaskExecutionId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("executionId");
        }
    }

    private static ApiRequestException invalidIdentifier(String field) {
        return new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request", "Request contains an invalid identifier", Map.of("field", field));
    }

    private record Route(OrganizationId organizationId, TeamId teamId, TaskId taskId) {}

    public record CurrentAttemptResponse(UUID taskId, AttemptResponse currentAttempt) {}

    public record AttemptResponse(
            UUID executionId,
            int attempt,
            String executionStatus,
            boolean current,
            boolean coding,
            CodingDetailsResponse details) {
        static AttemptResponse from(AttemptView value) {
            return new AttemptResponse(value.executionId().value(), value.attempt(), value.executionStatus(),
                    value.current(), value.coding(), value.details().map(CodingDetailsResponse::from).orElse(null));
        }
    }

    /** Every nested component is an explicit public-safe read projection. */
    public record CodingDetailsResponse(
            UUID executionId,
            int attempt,
            CodingAttemptProjection.WorkspaceSummary workspace,
            CodingAttemptProjection.SandboxSummary sandbox,
            CodingAttemptProjection.DiffManifestSummary diffManifest,
            CodingAttemptProjection.CodingResultSummary codingResult,
            long commandEvidenceCount,
            long testEvidenceCount) {
        static CodingDetailsResponse from(CodingAttemptProjection value) {
            return new CodingDetailsResponse(value.executionId().value(), value.attempt(), value.workspace(),
                    value.sandbox().orElse(null), value.diffManifest().orElse(null),
                    value.codingResult().orElse(null), value.commandEvidenceCount(), value.testEvidenceCount());
        }
    }

    public record CommandPageResponse(List<CommandEvidenceProjection> items, String nextCursor) {
        static CommandPageResponse from(CodingEvidencePage<CommandEvidenceProjection> page,
                CodingEvidenceCursorCodec codec, Route route, TaskExecutionId executionId) {
            return new CommandPageResponse(page.items(), page.nextCursor()
                    .map(value -> codec.encode(value, route.organizationId(), route.teamId(), route.taskId(),
                            executionId, CodingEvidenceCursorCodec.Collection.COMMANDS))
                    .orElse(null));
        }
    }

    public record TestEvidencePageResponse(List<TestEvidenceProjection> items, String nextCursor) {
        static TestEvidencePageResponse from(CodingEvidencePage<TestEvidenceProjection> page,
                CodingEvidenceCursorCodec codec, Route route, TaskExecutionId executionId) {
            return new TestEvidencePageResponse(page.items(), page.nextCursor()
                    .map(value -> codec.encode(value, route.organizationId(), route.teamId(), route.taskId(),
                            executionId, CodingEvidenceCursorCodec.Collection.TEST_EVIDENCE))
                    .orElse(null));
        }
    }
}
