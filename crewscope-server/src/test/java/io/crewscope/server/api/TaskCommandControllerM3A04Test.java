package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.task.MemberTaskCommandResult;
import io.crewscope.application.task.MemberTaskCommandService;
import io.crewscope.application.task.MemberTaskControlCommand;
import io.crewscope.application.task.RetryTaskCommand;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** HTTP contract for M3-A04 member Task controls. */
class TaskCommandControllerM3A04Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, teamId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.TEAM,
            UtcTimestamp.parse("2026-08-15T10:00:00Z"));
    private MemberTaskCommandService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(MemberTaskCommandService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(new TaskCommandController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void acceptsPauseCancelResumeAndRetryWithTheStrongAttemptVersion() {
        stubAllCommands();

        client.post().uri(root() + "/pause")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "task-pause-http")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"Pause for review\"}")
                .exchange().expectStatus().isAccepted()
                .expectHeader().valueEquals("Cache-Control", "no-store");
        client.post().uri(root() + "/cancel")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "task-cancel-http")
                .header(ApiHeaders.IF_MATCH, "\"8\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"Cancel safely\"}")
                .exchange().expectStatus().isAccepted();
        client.post().uri(root() + "/resume")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "task-resume-http")
                .header(ApiHeaders.IF_MATCH, "\"9\"")
                .exchange().expectStatus().isAccepted();
        client.post().uri(root() + "/retry")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "task-retry-http")
                .header(ApiHeaders.IF_MATCH, "\"10\"")
                .exchange().expectStatus().isAccepted();

        ArgumentCaptor<MemberTaskControlCommand> pause =
                ArgumentCaptor.forClass(MemberTaskControlCommand.class);
        ArgumentCaptor<MemberTaskControlCommand> cancel =
                ArgumentCaptor.forClass(MemberTaskControlCommand.class);
        ArgumentCaptor<RetryTaskCommand> resume =
                ArgumentCaptor.forClass(RetryTaskCommand.class);
        ArgumentCaptor<RetryTaskCommand> retry =
                ArgumentCaptor.forClass(RetryTaskCommand.class);
        verify(service).pause(any(), any(), any(), any(), pause.capture());
        verify(service).cancel(any(), any(), any(), any(), cancel.capture());
        verify(service).resume(any(), any(), any(), any(), resume.capture());
        verify(service).retry(any(), any(), any(), any(), retry.capture());
        assertEquals(7, pause.getValue().expectedExecutionVersion());
        assertEquals("Pause for review", pause.getValue().reason());
        assertEquals(8, cancel.getValue().expectedExecutionVersion());
        assertEquals(9, resume.getValue().expectedExecutionVersion());
        assertEquals(10, retry.getValue().expectedExecutionVersion());
    }

    @Test
    void requiresIdempotencyStrongIfMatchAndAValidReason() {
        client.post().uri(root() + "/pause")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"Pause\"}")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
        client.post().uri(root() + "/pause")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "task-pause-no-version")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"Pause\"}")
                .exchange().expectStatus().isEqualTo(428)
                .expectBody().jsonPath("$.code").isEqualTo("precondition_required");
        client.post().uri(root() + "/cancel")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "task-cancel-blank")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\" \"}")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
        verify(service, never()).pause(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsBodiesForResumeAndRetryInsteadOfAcceptingIdentityOrScheduleFields() {
        client.post().uri(root() + "/resume")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "task-resume-forged")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"workerId\":\"" + UUID.randomUUID() + "\"}")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
        client.post().uri(root() + "/retry")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "task-retry-forged")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"maxAttempts\":100}")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
        verify(service, never()).resume(any(), any(), any(), any(), any());
        verify(service, never()).retry(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidRouteIdentifiersBeforeTheApplicationPort() {
        client.post()
                .uri("/api/v1/organizations/" + organizationId + "/teams/" + teamId
                        + "/tasks/not-a-task/attempts/" + executionId + "/retry")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "task-invalid-route")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
        verify(service, never()).retry(any(), any(), any(), any(), any());
    }

    private void stubAllCommands() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 11, UUID.randomUUID());
        CommandExecution<MemberTaskCommandResult> completed = CommandExecution.completed(
                mock(MemberTaskCommandResult.class), receipt);
        when(service.pause(any(), any(), any(), any(), any())).thenReturn(completed);
        when(service.cancel(any(), any(), any(), any(), any())).thenReturn(completed);
        when(service.resume(any(), any(), any(), any(), any())).thenReturn(completed);
        when(service.retry(any(), any(), any(), any(), any())).thenReturn(completed);
    }

    private String root() {
        return "/api/v1/organizations/" + organizationId
                + "/teams/" + teamId
                + "/tasks/" + taskId
                + "/attempts/" + executionId;
    }
}
