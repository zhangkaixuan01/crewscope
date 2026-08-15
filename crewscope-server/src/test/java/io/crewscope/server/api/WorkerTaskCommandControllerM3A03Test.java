package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.task.TaskTokenExecutionContext;
import io.crewscope.application.task.WorkerCommandContext;
import io.crewscope.application.task.WorkerCommandOperation;
import io.crewscope.application.task.WorkerProgressCommand;
import io.crewscope.application.task.WorkerTaskCommandResult;
import io.crewscope.application.task.WorkerTaskCommandService;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ClaimTokenHash;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionPrincipalSnapshot;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.TaskCredentialGrantId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskTokenGrantScope;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.server.security.TaskTokenWebFilter;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** HTTP identity and concurrency contract for the M3-A03 Worker command adapter. */
class WorkerTaskCommandControllerM3A03Test {

    private final TaskTokenExecutionContext authorization = authorization();
    private WorkerTaskCommandService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(WorkerTaskCommandService.class);
        client = WebTestClient.bindToController(new WorkerTaskCommandController(service))
                .controllerAdvice(new ApiExceptionHandler())
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(
                            TaskTokenWebFilter.CONTEXT_ATTRIBUTE, authorization);
                    return chain.filter(exchange);
                })
                .build();
    }

    @Test
    void acceptsProgressUsingOnlyTokenIdentityAndStrongExecutionVersion() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 8, UUID.randomUUID());
        when(service.progress(any(), any())).thenReturn(CommandExecution.completed(
                new WorkerTaskCommandResult(
                        WorkerCommandOperation.PROGRESS,
                        Optional.of(8L),
                        Optional.empty()),
                receipt));

        client.post()
                .uri(root() + "/progress")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "worker-progress-1")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"safeSummary":"已完成依赖检查","percent":35}
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.domainEventId").isEqualTo(receipt.domainEventId().toString())
                .jsonPath("$.committedVersion").isEqualTo(8);

        ArgumentCaptor<WorkerCommandContext> context =
                ArgumentCaptor.forClass(WorkerCommandContext.class);
        ArgumentCaptor<WorkerProgressCommand> command =
                ArgumentCaptor.forClass(WorkerProgressCommand.class);
        verify(service).progress(context.capture(), command.capture());
        assertEquals(authorization, context.getValue().authorization());
        assertEquals(7, command.getValue().expectedExecutionVersion());
        assertEquals("已完成依赖检查", command.getValue().safeSummary());
        assertEquals(Optional.of(35), command.getValue().percent());
    }

    @Test
    void rejectsBodyAndHeaderIdentityForgeryBeforeTheApplicationPort() {
        client.post()
                .uri(root() + "/progress")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "worker-forged-body")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"safeSummary":"伪造","percent":1,"workerId":"%s"}
                        """.formatted(RuntimeWorkerId.generate()))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("worker_ownership_invalid");

        client.post()
                .uri(root() + "/heartbeat")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "worker-forged-header")
                .header(WorkerTaskCommandController.LEASE_VERSION, "4")
                .header("X-CrewScope-Worker-Id", RuntimeWorkerId.generate().toString())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("worker_ownership_invalid");

        verify(service, never()).heartbeat(any(), any());
    }

    @Test
    void masksOwnershipFailuresAndRejectsWrongRouteOrExternalRoute() {
        when(service.heartbeat(any(), any())).thenThrow(new DomainValidationException(
                "executionLease.ownership", "contains internal ownership coordinates"));

        client.post()
                .uri(root() + "/heartbeat")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "worker-stale-owner")
                .header(WorkerTaskCommandController.LEASE_VERSION, "4")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("worker_ownership_invalid")
                .jsonPath("$.details").isEqualTo(java.util.Map.of());

        client.post()
                .uri("/api/internal/v1/worker/executions/"
                        + TaskExecutionId.generate() + "/heartbeat")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "worker-wrong-route")
                .header(WorkerTaskCommandController.LEASE_VERSION, "4")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("worker_ownership_invalid");

        client.post()
                .uri("/api/v1/worker/executions/"
                        + authorization.scope().taskExecutionId() + "/heartbeat")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void requiresIdempotencyAndBothKindsOfExpectedVersion() {
        client.post()
                .uri(root() + "/start")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .header(WorkerTaskCommandController.LEASE_VERSION, "4")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");

        client.post()
                .uri(root() + "/start")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "worker-no-execution-version")
                .header(WorkerTaskCommandController.LEASE_VERSION, "4")
                .exchange()
                .expectStatus().isEqualTo(428)
                .expectBody().jsonPath("$.code").isEqualTo("precondition_required");

        client.post()
                .uri(root() + "/start")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "worker-no-lease-version")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .exchange()
                .expectStatus().isEqualTo(428)
                .expectBody().jsonPath("$.code").isEqualTo("precondition_required");
    }

    @Test
    void replayReturnsTheOriginalReceiptAndTheSameDerivedNextLeaseVersion() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 5, UUID.randomUUID());
        when(service.heartbeat(any(), any())).thenReturn(CommandExecution.replayed(receipt));

        client.post()
                .uri(root() + "/heartbeat")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "worker-heartbeat-replay")
                .header(WorkerTaskCommandController.LEASE_VERSION, "4")
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
                .expectBody()
                .jsonPath("$.commandId").isEqualTo(receipt.commandId().toString())
                .jsonPath("$.operation").isEqualTo("HEARTBEAT")
                .jsonPath("$.leaseVersion").isEqualTo(5)
                .jsonPath("$.taskExecutionVersion").doesNotExist();
    }

    private String root() {
        return "/api/internal/v1/worker/executions/"
                + authorization.scope().taskExecutionId();
    }

    private static TaskTokenExecutionContext authorization() {
        OrganizationId organizationId = OrganizationId.generate();
        WorkItemScope workScope = new WorkItemScope(
                organizationId, TeamId.generate(), WorkspaceId.generate(), WorkProjectId.generate());
        ExecutionPrincipalSnapshot principal = new ExecutionPrincipalSnapshot(
                PrincipalId.generate(),
                ResponsibilityAssignmentId.generate(),
                1,
                TaskFactHash.sha256("responsibility"));
        TaskTokenGrantScope scope = new TaskTokenGrantScope(
                workScope,
                TaskId.generate(),
                TaskExecutionId.generate(),
                1,
                ExecutionLeaseId.generate(),
                new RuntimeEnvironment("test"),
                ExecutionRuntimeId.generate(),
                RuntimeWorkerId.generate(),
                new ClaimTokenHash("b".repeat(64)),
                FencingToken.initial(),
                principal,
                PolicySnapshotId.generate(),
                TaskFactHash.sha256("policy"),
                new SafetyEnforcementOverlayReference(
                        SafetyEnforcementOverlayId.generate(),
                        1,
                        TaskFactHash.sha256("overlay")),
                Set.of("repository.read"),
                Set.of());
        return new TaskTokenExecutionContext(
                TaskCredentialGrantId.generate(),
                0,
                scope,
                UtcTimestamp.parse("2026-08-15T09:00:00Z"));
    }
}
