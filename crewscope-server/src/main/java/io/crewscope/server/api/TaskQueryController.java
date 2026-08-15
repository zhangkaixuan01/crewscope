package io.crewscope.server.api;

import io.crewscope.application.task.TaskDetails;
import io.crewscope.application.task.TaskListCursor;
import io.crewscope.application.task.TaskListItem;
import io.crewscope.application.task.TaskListPage;
import io.crewscope.application.task.TaskQueryService;
import io.crewscope.application.task.TaskRuntimeFacts;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunContinuityGap;
import io.crewscope.domain.task.AgentRunSegment;
import io.crewscope.domain.task.AgentRunTerminal;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.PlanStep;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.StepCheckpoint;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskCancellation;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionControlRequest;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionTerminal;
import io.crewscope.domain.task.TaskExecutionWaiting;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskInputReference;
import io.crewscope.domain.task.TaskResponsibilitySnapshotEntry;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.task.TodoSummaryItem;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Instant;
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
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        Optional<WorkProjectId> project = Optional.ofNullable(projectId)
                .map(value -> projectId(value, "projectId"));
        Optional<TaskStatus> selectedStatus = Optional.ofNullable(status);
        Optional<TaskListCursor> cursor = Optional.ofNullable(after).map(value -> cursorCodec.decode(
                value,
                route.organizationId(),
                route.teamId(),
                project,
                selectedStatus));
        return query(authentication, route.organizationId(), exchange, access -> service.list(
                        access,
                        route.organizationId(),
                        route.teamId(),
                        project,
                        selectedStatus,
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
                                selectedStatus)));
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

    private static ApiRequestException invalidIdentifier(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", field));
    }

    private record Route(OrganizationId organizationId, TeamId teamId) {}

    public record TaskPageResponse(List<TaskListItemResponse> items, String nextCursor) {
        static TaskPageResponse from(
                TaskListPage page,
                TaskListCursorCodec codec,
                OrganizationId organizationId,
                TeamId teamId,
                Optional<WorkProjectId> projectId,
                Optional<TaskStatus> status) {
            return new TaskPageResponse(
                    page.items().stream().map(TaskListItemResponse::from).toList(),
                    page.nextCursor()
                            .map(value -> codec.encode(
                                    value, organizationId, teamId, projectId, status))
                            .orElse(null));
        }
    }

    public record TaskListItemResponse(
            UUID id,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            String objective,
            List<String> acceptanceCriteria,
            String status,
            UUID currentExecutionId,
            Integer currentAttempt,
            String currentExecutionStatus,
            String currentWaitingReason,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static TaskListItemResponse from(TaskListItem value) {
            return new TaskListItemResponse(
                    value.id().value(),
                    value.scope().workspaceId().value(),
                    value.scope().projectId().value(),
                    value.workItemId().value(),
                    value.brief().objective(),
                    value.brief().acceptanceCriteria(),
                    value.status().name(),
                    value.currentExecutionId().map(TaskExecutionId::value).orElse(null),
                    value.currentAttempt().orElse(null),
                    value.currentExecutionStatus().map(Enum::name).orElse(null),
                    value.currentWaitingReason().map(Enum::name).orElse(null),
                    value.version(),
                    value.audit().createdAt().value(),
                    value.audit().updatedAt().value());
        }
    }

    public record TaskDetailsResponse(
            UUID id,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            String objective,
            List<String> acceptanceCriteria,
            TaskSourceResponse source,
            List<TaskResponsibilityResponse> responsibilitySnapshot,
            Instant responsibilityCapturedAt,
            String status,
            UUID currentExecutionId,
            TaskCancellationResponse cancellation,
            long version,
            AuditResponse audit,
            List<TaskExecutionResponse> attempts) {
        static TaskDetailsResponse from(TaskDetails details) {
            Task value = details.task();
            return new TaskDetailsResponse(
                    value.id().value(),
                    value.scope().teamId().value(),
                    value.scope().workspaceId().value(),
                    value.scope().projectId().value(),
                    value.workItemId().value(),
                    value.brief().objective(),
                    value.brief().acceptanceCriteria(),
                    TaskSourceResponse.from(value.source()),
                    value.responsibilitySnapshot().entries().stream()
                            .map(TaskResponsibilityResponse::from)
                            .toList(),
                    value.responsibilitySnapshot().capturedAt().value(),
                    value.status().name(),
                    value.currentExecutionId().map(TaskExecutionId::value).orElse(null),
                    value.cancellation().map(TaskCancellationResponse::from).orElse(null),
                    value.version(),
                    AuditResponse.from(value.audit()),
                    details.attempts().stream().map(TaskExecutionResponse::from).toList());
        }
    }

    public record TaskSourceResponse(
            String type,
            long workItemVersion,
            UUID conversationId,
            String inputType,
            UUID inputId,
            Long inputVersion) {
        static TaskSourceResponse from(TaskSource value) {
            TaskInputReference input = value.inputReference().orElse(null);
            return new TaskSourceResponse(
                    value.type().name(),
                    value.workItemVersion(),
                    value.conversationId().map(id -> id.value()).orElse(null),
                    input == null ? null : input.type().name(),
                    input == null ? null : input.referenceId(),
                    input == null ? null : input.referenceVersion());
        }
    }

    public record TaskResponsibilityResponse(
            UUID assignmentId,
            long assignmentVersion,
            String role,
            UUID principalId,
            String principalType,
            UUID memberId,
            Instant assignedAt,
            Instant acceptedAt) {
        static TaskResponsibilityResponse from(TaskResponsibilitySnapshotEntry value) {
            return new TaskResponsibilityResponse(
                    value.assignmentId().value(),
                    value.assignmentVersion(),
                    value.role().name(),
                    value.principalId().value(),
                    value.principalType().name(),
                    value.memberId().map(id -> id.value()).orElse(null),
                    value.assignedAt().value(),
                    value.acceptedAt().value());
        }
    }

    public record TaskCancellationResponse(UUID cancelledByPrincipalId, Instant cancelledAt, String reason) {
        static TaskCancellationResponse from(TaskCancellation value) {
            return new TaskCancellationResponse(
                    value.cancelledByPrincipalId().value(), value.cancelledAt().value(), value.reason());
        }
    }

    public record TaskExecutionResponse(
            UUID id,
            int attempt,
            int maxAttempts,
            UUID parentExecutionId,
            int priority,
            Instant notBefore,
            String status,
            WaitingResponse waiting,
            ControlRequestResponse controlRequest,
            TerminalResponse terminal,
            UUID executorPrincipalId,
            UUID currentPlanVersionId,
            long version,
            AuditResponse audit) {
        static TaskExecutionResponse from(TaskExecution value) {
            return new TaskExecutionResponse(
                    value.id().value(),
                    value.attempt(),
                    value.maxAttempts(),
                    value.parentExecutionId().map(TaskExecutionId::value).orElse(null),
                    value.priority().value(),
                    value.notBefore().value(),
                    value.status().name(),
                    value.waiting().map(WaitingResponse::from).orElse(null),
                    value.controlRequest().map(ControlRequestResponse::from).orElse(null),
                    value.terminal().map(TerminalResponse::from).orElse(null),
                    value.planningContext()
                            .map(context -> context.executionPrincipal().principalId().value())
                            .orElse(null),
                    value.planningContext()
                            .flatMap(context -> context.currentPlanVersionId())
                            .map(id -> id.value())
                            .orElse(null),
                    value.version(),
                    AuditResponse.from(value.audit()));
        }
    }

    public record WaitingResponse(String reason, Instant waitingSince) {
        static WaitingResponse from(TaskExecutionWaiting value) {
            return new WaitingResponse(value.reason().name(), value.waitingSince().value());
        }
    }

    public record ControlRequestResponse(
            String type, UUID requestedByPrincipalId, Instant requestedAt, String reason) {
        static ControlRequestResponse from(TaskExecutionControlRequest value) {
            return new ControlRequestResponse(
                    value.type().name(),
                    value.requestedByPrincipalId().value(),
                    value.requestedAt().value(),
                    value.reason());
        }
    }

    public record TerminalResponse(
            String status,
            UUID decidedByPrincipalId,
            Instant decidedAt,
            String failureClass,
            String failureCode) {
        static TerminalResponse from(TaskExecutionTerminal value) {
            TaskExecutionFailure failure = value.failure().orElse(null);
            return new TerminalResponse(
                    value.status().name(),
                    value.decidedByPrincipalId().value(),
                    value.decidedAt().value(),
                    failure == null ? null : failure.failureClass().name(),
                    failure == null ? null : failure.code());
        }
    }

    public record TaskRuntimeFactsResponse(
            TaskExecutionResponse execution,
            List<PlanVersionResponse> planVersions,
            List<StepExecutionResponse> steps,
            List<AgentSessionResponse> sessions,
            List<AgentRunResponse> agentRuns,
            List<AgentInterruptResponse> interrupts,
            List<SnapshotSummaryResponse> snapshots,
            List<ExecutionLeaseResponse> leases) {
        static TaskRuntimeFactsResponse from(TaskRuntimeFacts facts) {
            return new TaskRuntimeFactsResponse(
                    TaskExecutionResponse.from(facts.execution()),
                    facts.planVersions().stream().map(PlanVersionResponse::from).toList(),
                    facts.steps().stream().map(StepExecutionResponse::from).toList(),
                    facts.sessions().stream().map(AgentSessionResponse::from).toList(),
                    facts.agentRuns().stream().map(AgentRunResponse::from).toList(),
                    facts.interrupts().stream().map(AgentInterruptResponse::from).toList(),
                    facts.snapshots().stream().map(SnapshotSummaryResponse::from).toList(),
                    facts.leases().stream().map(ExecutionLeaseResponse::from).toList());
        }
    }

    public record PlanVersionResponse(
            UUID id,
            long revision,
            UUID parentVersionId,
            String changeReason,
            String markdown,
            List<PlanStepResponse> steps,
            List<TodoResponse> todoSummary,
            UUID publishedByPrincipalId,
            Instant publishedAt) {
        static PlanVersionResponse from(PlanVersion value) {
            return new PlanVersionResponse(
                    value.id().value(),
                    value.revision(),
                    value.parentVersionId().map(id -> id.value()).orElse(null),
                    value.changeReason().name(),
                    value.markdown(),
                    value.steps().stream().map(PlanStepResponse::from).toList(),
                    value.todoSummary().stream().map(TodoResponse::from).toList(),
                    value.publishedByPrincipalId().value(),
                    value.publishedAt().value());
        }
    }

    public record PlanStepResponse(
            String key,
            int sequence,
            String title,
            String type,
            List<String> dependencyKeys,
            List<String> requiredCapabilities,
            List<String> requiredTools,
            boolean critical) {
        static PlanStepResponse from(PlanStep value) {
            return new PlanStepResponse(
                    value.key(),
                    value.sequence(),
                    value.title(),
                    value.type().name(),
                    value.dependencyKeys().stream().sorted().toList(),
                    value.requiredCapabilities().stream().map(Enum::name).sorted().toList(),
                    value.requiredTools().stream().sorted().toList(),
                    value.critical());
        }
    }

    public record TodoResponse(
            String content, String status, String priority, String planStepKey) {
        static TodoResponse from(TodoSummaryItem value) {
            return new TodoResponse(
                    value.content(),
                    value.status().name(),
                    value.priority().orElse(null),
                    value.planStepKey().orElse(null));
        }
    }

    public record StepExecutionResponse(
            UUID id,
            UUID planVersionId,
            String planStepKey,
            int sequence,
            boolean critical,
            int runAttempt,
            int maxRunAttempts,
            String status,
            String waitReason,
            StepCheckpointResponse checkpoint,
            String failureClass,
            String failureCode,
            long version,
            AuditResponse audit) {
        static StepExecutionResponse from(StepExecution value) {
            TaskExecutionFailure failure = value.failure().orElse(null);
            return new StepExecutionResponse(
                    value.id().value(),
                    value.planVersionId().value(),
                    value.planStepKey(),
                    value.sequence(),
                    value.critical(),
                    value.runAttempt(),
                    value.maxRunAttempts(),
                    value.status().name(),
                    value.waitReason().map(Enum::name).orElse(null),
                    value.checkpoint().map(StepCheckpointResponse::from).orElse(null),
                    failure == null ? null : failure.failureClass().name(),
                    failure == null ? null : failure.code(),
                    value.version(),
                    AuditResponse.from(value.audit()));
        }
    }

    public record StepCheckpointResponse(
            long sequence, String code, UUID recordedByPrincipalId, Instant recordedAt) {
        static StepCheckpointResponse from(StepCheckpoint value) {
            return new StepCheckpointResponse(
                    value.sequence(),
                    value.code(),
                    value.recordedByPrincipalId().value(),
                    value.recordedAt().value());
        }
    }

    public record AgentSessionResponse(
            UUID id,
            UUID stepExecutionId,
            String purpose,
            UUID agentPrincipalId,
            UUID agentProfileId,
            long agentProfileVersion,
            String status,
            long version,
            AuditResponse audit) {
        static AgentSessionResponse from(TaskAgentRuntimeSession value) {
            return new AgentSessionResponse(
                    value.id().value(),
                    value.stepExecutionId().map(id -> id.value()).orElse(null),
                    value.purpose().name(),
                    value.agentPrincipalId().value(),
                    value.agentProfileId().value(),
                    value.agentProfileVersion(),
                    value.status().name(),
                    value.version(),
                    AuditResponse.from(value.audit()));
        }
    }

    public record AgentRunResponse(
            UUID id,
            UUID stepExecutionId,
            UUID runtimeSessionId,
            UUID agentPrincipalId,
            UUID agentProfileId,
            long agentProfileVersion,
            long runSequence,
            String status,
            List<AgentRunSegmentResponse> segments,
            ContinuityGapResponse continuityGap,
            AgentRunTerminalResponse terminal,
            long version,
            AuditResponse audit) {
        static AgentRunResponse from(AgentRun value) {
            return new AgentRunResponse(
                    value.id().value(),
                    value.stepExecutionId().map(id -> id.value()).orElse(null),
                    value.runtimeSessionId().value(),
                    value.agentPrincipalId().value(),
                    value.agentProfileId().value(),
                    value.agentProfileVersion(),
                    value.runSequence(),
                    value.status().name(),
                    value.segments().stream().map(AgentRunSegmentResponse::from).toList(),
                    value.continuityGap().map(ContinuityGapResponse::from).orElse(null),
                    value.terminal().map(AgentRunTerminalResponse::from).orElse(null),
                    value.version(),
                    AuditResponse.from(value.audit()));
        }
    }

    public record AgentRunSegmentResponse(
            long sequence,
            String kind,
            UUID resumedFromInterruptId,
            String status,
            Instant startedAt,
            Instant endedAt) {
        static AgentRunSegmentResponse from(AgentRunSegment value) {
            return new AgentRunSegmentResponse(
                    value.sequence(),
                    value.kind().name(),
                    value.resumedFromInterruptId().map(id -> id.value()).orElse(null),
                    value.status().name(),
                    value.startedAt().value(),
                    value.endedAt().map(time -> time.value()).orElse(null));
        }
    }

    public record ContinuityGapResponse(
            UUID previousRunId,
            UUID lastValidSnapshotId,
            long firstMissingCheckpoint,
            long lastMissingCheckpoint,
            String reason,
            Instant detectedAt) {
        static ContinuityGapResponse from(AgentRunContinuityGap value) {
            return new ContinuityGapResponse(
                    value.previousRunId().value(),
                    value.lastValidSnapshotId().map(id -> id.value()).orElse(null),
                    value.firstMissingCheckpoint(),
                    value.lastMissingCheckpoint(),
                    value.reason().name(),
                    value.detectedAt().value());
        }
    }

    public record AgentRunTerminalResponse(
            String status, String failureCode, UUID resultArtifactId, Instant occurredAt) {
        static AgentRunTerminalResponse from(AgentRunTerminal value) {
            return new AgentRunTerminalResponse(
                    value.status().name(),
                    value.failureCode().orElse(null),
                    value.resultArtifactId().map(id -> id.value()).orElse(null),
                    value.occurredAt().value());
        }
    }

    public record AgentInterruptResponse(
            UUID id,
            UUID agentRunId,
            long segmentSequence,
            String kind,
            String status,
            UUID resolvedByPrincipalId,
            Instant resolvedAt,
            long version,
            AuditResponse audit) {
        static AgentInterruptResponse from(AgentInterrupt value) {
            return new AgentInterruptResponse(
                    value.id().value(),
                    value.agentRunId().value(),
                    value.segmentSequence(),
                    value.kind().name(),
                    value.status().name(),
                    value.resolution().map(resolution -> resolution.resolvedBy().value()).orElse(null),
                    value.resolution().map(resolution -> resolution.resolvedAt().value()).orElse(null),
                    value.version(),
                    AuditResponse.from(value.audit()));
        }
    }

    public record SnapshotSummaryResponse(
            UUID id,
            UUID agentRunId,
            UUID runtimeSessionId,
            UUID agentProfileId,
            long agentProfileVersion,
            long snapshotSequence,
            long checkpointSequence,
            long sizeBytes,
            String status,
            String invalidReasonCode,
            long version,
            AuditResponse audit) {
        static SnapshotSummaryResponse from(AgentStateSnapshot value) {
            return new SnapshotSummaryResponse(
                    value.id().value(),
                    value.agentRunId().value(),
                    value.runtimeSessionId().value(),
                    value.agentProfileId().value(),
                    value.agentProfileVersion(),
                    value.snapshotSequence(),
                    value.checkpointSequence(),
                    value.size(),
                    value.status().name(),
                    value.invalidReasonCode().orElse(null),
                    value.version(),
                    AuditResponse.from(value.audit()));
        }
    }

    public record ExecutionLeaseResponse(
            UUID id,
            String environment,
            UUID runtimeId,
            UUID workerId,
            String phase,
            String status,
            Instant acquiredAt,
            Instant lastHeartbeatAt,
            Instant expiresAt,
            String releaseReason,
            Instant releasedAt,
            long version) {
        static ExecutionLeaseResponse from(ExecutionLease value) {
            return new ExecutionLeaseResponse(
                    value.id().value(),
                    value.environment().value(),
                    value.runtimeId().value(),
                    value.workerId().value(),
                    value.phase().name(),
                    value.release().isPresent() ? "RELEASED" : "ACTIVE",
                    value.acquiredAt().value(),
                    value.lastHeartbeatAt().value(),
                    value.expiresAt().value(),
                    value.release().map(release -> release.reason().name()).orElse(null),
                    value.release().map(release -> release.releasedAt().value()).orElse(null),
                    value.version());
        }
    }

    public record AuditResponse(
            UUID createdByPrincipalId,
            Instant createdAt,
            UUID updatedByPrincipalId,
            Instant updatedAt) {
        static AuditResponse from(AuditMetadata value) {
            return new AuditResponse(
                    value.createdBy().map(id -> id.value()).orElse(null),
                    value.createdAt().value(),
                    value.updatedBy().map(id -> id.value()).orElse(null),
                    value.updatedAt().value());
        }
    }
}
