package io.crewscope.server.api;

import io.crewscope.application.task.TaskTokenExecutionContext;
import io.crewscope.application.task.WorkerCommandContext;
import io.crewscope.application.task.WorkerFailCommand;
import io.crewscope.application.task.WorkerCommandOperation;
import io.crewscope.application.task.WorkerHeartbeatCommand;
import io.crewscope.application.task.WorkerPrepareCommand;
import io.crewscope.application.task.WorkerProgressCommand;
import io.crewscope.application.task.WorkerTaskCommandService;
import io.crewscope.application.task.WorkerTransitionCommand;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionFailureClass;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.server.config.runtime.WorkerCapableProfileCondition;
import io.crewscope.server.security.TaskTokenWebFilter;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Task Token-only HTTP adapter for fenced Worker mutations. */
@RestController
@Conditional(WorkerCapableProfileCondition.class)
@RequestMapping("/api/internal/v1/worker/executions/{executionId}")
public final class WorkerTaskCommandController {

    static final String LEASE_VERSION = "X-CrewScope-Lease-Version";
    private static final String CAUSATION_ID = "X-CrewScope-Causation-Id";
    private static final Pattern NON_NEGATIVE_VERSION = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern SAFE_FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Set<String> FORBIDDEN_IDENTITY_HEADERS = Set.of(
            "x-crewscope-organization-id",
            "x-crewscope-task-execution-id",
            "x-crewscope-attempt",
            "x-crewscope-execution-lease-id",
            "x-crewscope-runtime-id",
            "x-crewscope-worker-id",
            "x-crewscope-claim-token",
            "x-crewscope-fencing-token",
            "x-worker-id",
            "x-runtime-id",
            "x-claim-token",
            "x-fencing-token");
    private static final Set<String> FORBIDDEN_IDENTITY_FIELDS = Set.of(
            "organizationid",
            "teamid",
            "taskid",
            "taskexecutionid",
            "executionid",
            "attempt",
            "executionleaseid",
            "leaseid",
            "runtimeid",
            "workerid",
            "claimtoken",
            "claimtokenhash",
            "fencingtoken",
            "executionprincipalid");

    private final WorkerTaskCommandService service;

    public WorkerTaskCommandController(WorkerTaskCommandService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @PostMapping("/prepare")
    public Mono<ResponseEntity<WorkerCommandReceiptResponse>> prepare(
            @PathVariable String executionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) Map<String, Object> body,
            ServerWebExchange exchange) {
        requireEmptyBody(body);
        WorkerCommandContext context = context(executionId, key, exchange);
        long expectedExecutionVersion = requireAdvanceable(
                ApiHeaders.requireIfMatch(ifMatch), ApiHeaders.IF_MATCH);
        return blocking(() -> service.prepare(
                        context, new WorkerPrepareCommand(expectedExecutionVersion)))
                .map(result -> WorkerCommandReceiptResponse.accepted(
                        result,
                        WorkerCommandOperation.PREPARE,
                        Optional.of(nextVersion(expectedExecutionVersion, ApiHeaders.IF_MATCH)),
                        Optional.empty()));
    }

    @PostMapping("/start")
    public Mono<ResponseEntity<WorkerCommandReceiptResponse>> start(
            @PathVariable String executionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = LEASE_VERSION, required = false) String leaseVersion,
            @RequestBody(required = false) Map<String, Object> body,
            ServerWebExchange exchange) {
        requireEmptyBody(body);
        WorkerCommandContext context = context(executionId, key, exchange);
        long expectedExecutionVersion = requireAdvanceable(
                ApiHeaders.requireIfMatch(ifMatch), ApiHeaders.IF_MATCH);
        long expectedLeaseVersion = requireLeaseVersion(leaseVersion);
        return blocking(() -> service.start(context, new WorkerTransitionCommand(
                        expectedExecutionVersion, expectedLeaseVersion)))
                .map(result -> WorkerCommandReceiptResponse.accepted(
                        result,
                        WorkerCommandOperation.START,
                        Optional.of(nextVersion(expectedExecutionVersion, ApiHeaders.IF_MATCH)),
                        Optional.of(nextVersion(expectedLeaseVersion, LEASE_VERSION))));
    }

    @PostMapping("/heartbeat")
    public Mono<ResponseEntity<WorkerCommandReceiptResponse>> heartbeat(
            @PathVariable String executionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = LEASE_VERSION, required = false) String leaseVersion,
            @RequestBody(required = false) Map<String, Object> body,
            ServerWebExchange exchange) {
        requireEmptyBody(body);
        WorkerCommandContext context = context(executionId, key, exchange);
        long expectedLeaseVersion = requireLeaseVersion(leaseVersion);
        return blocking(() -> service.heartbeat(
                        context, new WorkerHeartbeatCommand(expectedLeaseVersion)))
                .map(result -> WorkerCommandReceiptResponse.accepted(
                        result,
                        WorkerCommandOperation.HEARTBEAT,
                        Optional.empty(),
                        Optional.of(nextVersion(expectedLeaseVersion, LEASE_VERSION))));
    }

    @PostMapping("/progress")
    public Mono<ResponseEntity<WorkerCommandReceiptResponse>> progress(
            @PathVariable String executionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody Map<String, Object> body,
            ServerWebExchange exchange) {
        Map<String, Object> request = requireFields(body, Set.of("safeSummary", "percent"));
        String summary = requireString(request, "safeSummary");
        Optional<Integer> percent = optionalInteger(request, "percent", 0, 100);
        WorkerCommandContext context = context(executionId, key, exchange);
        long expectedExecutionVersion = requireAdvanceable(
                ApiHeaders.requireIfMatch(ifMatch), ApiHeaders.IF_MATCH);
        return blocking(() -> service.progress(context, new WorkerProgressCommand(
                        expectedExecutionVersion, summary, percent)))
                .map(result -> WorkerCommandReceiptResponse.accepted(
                        result,
                        WorkerCommandOperation.PROGRESS,
                        Optional.of(nextVersion(expectedExecutionVersion, ApiHeaders.IF_MATCH)),
                        Optional.empty()));
    }

    @PostMapping("/complete")
    public Mono<ResponseEntity<WorkerCommandReceiptResponse>> complete(
            @PathVariable String executionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = LEASE_VERSION, required = false) String leaseVersion,
            @RequestBody(required = false) Map<String, Object> body,
            ServerWebExchange exchange) {
        requireEmptyBody(body);
        WorkerCommandContext context = context(executionId, key, exchange);
        long expectedExecutionVersion = requireAdvanceable(
                ApiHeaders.requireIfMatch(ifMatch), ApiHeaders.IF_MATCH);
        long expectedLeaseVersion = requireLeaseVersion(leaseVersion);
        return blocking(() -> service.complete(context, new WorkerTransitionCommand(
                        expectedExecutionVersion, expectedLeaseVersion)))
                .map(result -> WorkerCommandReceiptResponse.accepted(
                        result,
                        WorkerCommandOperation.COMPLETE,
                        Optional.of(nextVersion(expectedExecutionVersion, ApiHeaders.IF_MATCH)),
                        Optional.of(nextVersion(expectedLeaseVersion, LEASE_VERSION))));
    }

    @PostMapping("/fail")
    public Mono<ResponseEntity<WorkerCommandReceiptResponse>> fail(
            @PathVariable String executionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = LEASE_VERSION, required = false) String leaseVersion,
            @RequestBody Map<String, Object> body,
            ServerWebExchange exchange) {
        Map<String, Object> request = requireFields(body, Set.of("failureClass", "failureCode"));
        TaskExecutionFailure failure = failure(request);
        WorkerCommandContext context = context(executionId, key, exchange);
        long expectedExecutionVersion = requireAdvanceable(
                ApiHeaders.requireIfMatch(ifMatch), ApiHeaders.IF_MATCH);
        long expectedLeaseVersion = requireLeaseVersion(leaseVersion);
        return blocking(() -> service.fail(context, new WorkerFailCommand(
                        expectedExecutionVersion,
                        expectedLeaseVersion,
                        failure)))
                .map(result -> WorkerCommandReceiptResponse.accepted(
                        result,
                        WorkerCommandOperation.FAIL,
                        Optional.of(nextVersion(expectedExecutionVersion, ApiHeaders.IF_MATCH)),
                        Optional.of(nextVersion(expectedLeaseVersion, LEASE_VERSION))));
    }

    private static WorkerCommandContext context(
            String executionId, String key, ServerWebExchange exchange) {
        rejectIdentityHeaders(exchange.getRequest().getHeaders());
        TaskTokenExecutionContext authorization = exchange.getAttribute(
                TaskTokenWebFilter.CONTEXT_ATTRIBUTE);
        if (authorization == null) {
            throw new ApiRequestException(
                    HttpStatus.UNAUTHORIZED,
                    "task_token_invalid",
                    "Task Token authentication required",
                    Map.of());
        }
        TaskExecutionId routeId;
        try {
            routeId = TaskExecutionId.from(executionId);
        } catch (IllegalArgumentException failure) {
            throw ownershipRejected();
        }
        if (!routeId.equals(authorization.scope().taskExecutionId())) {
            throw ownershipRejected();
        }
        return new WorkerCommandContext(
                authorization,
                ApiHeaders.requireIdempotencyKey(key),
                ApiCorrelationIds.resolve(exchange),
                optionalUuid(exchange.getRequest().getHeaders().getFirst(CAUSATION_ID)));
    }

    private static void rejectIdentityHeaders(HttpHeaders headers) {
        boolean forged = headers.headerNames().stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(FORBIDDEN_IDENTITY_HEADERS::contains);
        if (forged) {
            throw ownershipRejected();
        }
    }

    private static long requireLeaseVersion(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiRequestException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "precondition_required",
                    "ExecutionLease version is required",
                    Map.of("header", LEASE_VERSION));
        }
        if (!NON_NEGATIVE_VERSION.matcher(value).matches()) {
            throw new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_lease_version",
                    "ExecutionLease version must be one non-negative integer",
                    Map.of("header", LEASE_VERSION));
        }
        try {
            return requireAdvanceable(Long.parseLong(value), LEASE_VERSION);
        } catch (NumberFormatException failure) {
            throw new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_lease_version",
                    "ExecutionLease version must be one non-negative integer",
                    Map.of("header", LEASE_VERSION));
        }
    }

    private static long nextVersion(long expected, String header) {
        return requireAdvanceable(expected, header) + 1;
    }

    private static long requireAdvanceable(long expected, String header) {
        if (expected == Long.MAX_VALUE) {
            throw new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Expected version cannot be advanced",
                    Map.of("header", header));
        }
        return expected;
    }

    private static TaskExecutionFailure failure(Map<String, Object> request) {
        String failureClass = requireString(request, "failureClass");
        String failureCode = requireString(request, "failureCode");
        if (!SAFE_FAILURE_CODE.matcher(failureCode).matches()) {
            throw invalidBody("failureCode");
        }
        try {
            return new TaskExecutionFailure(
                    TaskExecutionFailureClass.valueOf(failureClass), failureCode);
        } catch (IllegalArgumentException failure) {
            throw invalidBody("failureClass");
        }
    }

    private static Map<String, Object> requireFields(
            Map<String, Object> body, Set<String> allowed) {
        if (body == null) {
            throw invalidBody("body");
        }
        boolean identityForgery = body.keySet().stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(FORBIDDEN_IDENTITY_FIELDS::contains);
        if (identityForgery) {
            throw ownershipRejected();
        }
        if (!allowed.containsAll(body.keySet())) {
            throw invalidBody("body");
        }
        return body;
    }

    private static void requireEmptyBody(Map<String, Object> body) {
        if (body != null && !body.isEmpty()) {
            throw ownershipRejected();
        }
    }

    private static String requireString(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalidBody(field);
        }
        return text;
    }

    private static Optional<Integer> optionalInteger(
            Map<String, Object> body, String field, int minimum, int maximum) {
        Object value = body.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof Number number)) {
            throw invalidBody(field);
        }
        long parsed = number.longValue();
        if (number.doubleValue() != parsed || parsed < minimum || parsed > maximum) {
            throw invalidBody(field);
        }
        return Optional.of((int) parsed);
    }

    private static Optional<UUID> optionalUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.equals(new UUID(0, 0))) {
                throw new IllegalArgumentException("nil UUID");
            }
            return Optional.of(parsed);
        } catch (IllegalArgumentException failure) {
            throw new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Causation identifier is invalid",
                    Map.of("header", CAUSATION_ID));
        }
    }

    private static ApiRequestException ownershipRejected() {
        return new ApiRequestException(
                HttpStatus.CONFLICT,
                "worker_ownership_invalid",
                "Worker command does not match the current execution owner",
                Map.of());
    }

    private static ApiRequestException invalidBody(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Worker command body is invalid",
                Map.of("field", field));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }
}
