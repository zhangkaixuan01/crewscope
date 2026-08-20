package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.query.CodingAttemptProjection;
import io.crewscope.application.coding.query.CodingEvidenceCursor;
import io.crewscope.application.coding.query.CodingEvidencePage;
import io.crewscope.application.coding.query.CommandEvidenceProjection;
import io.crewscope.application.coding.query.TaskCodingQueryService;
import io.crewscope.application.coding.query.TaskCodingQueryService.AttemptView;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** HTTP route, safe DTO and non-Coding semantics for M4-A04. */
class TaskCodingQueryControllerM4A04Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private TaskCodingQueryService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(TaskCodingQueryService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(mock(TeamAccessContext.class));
        client = WebTestClient.bindToController(new TaskCodingQueryController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void exposesExplicitNonCodingHistoryWithoutAnInternalDetailsObject() {
        AttemptView view = new AttemptView(executionId, 1, "READY", true, false, Optional.empty());
        when(service.attempts(any(), any(), any(), any())).thenReturn(List.of(view));

        client.get().uri(root() + "/coding-attempts").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$[0].executionId").isEqualTo(executionId.toString())
                .jsonPath("$[0].coding").isEqualTo(false)
                .jsonPath("$[0].details").doesNotExist();
    }

    @Test
    void serializesOnlyWhitelistedWorkspaceAndSandboxFacts() {
        CodingAttemptProjection details = projection();
        AttemptView view = new AttemptView(executionId, 1, "RUNNING", true, true, Optional.of(details));
        when(service.current(any(), any(), any(), any()))
                .thenReturn(new TaskCodingQueryService.CurrentCodingAttempt(taskId, Optional.of(view)));

        client.get().uri(root() + "/coding").exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"networkMode\":\"NONE\""));
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"repositoryKey\":\"crewscope-java\""));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("containerId"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("containerName"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("workspaceKey"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("archiveReference"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("runtimeId"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("workerId"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("lease"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("fencing"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("reasoning"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("canonical"));
                });
    }

    @Test
    void emitsACommandCursorThatCannotBeReusedForTestEvidence() {
        UUID commandId = UUID.randomUUID();
        CommandEvidenceProjection command = new CommandEvidenceProjection(
                commandId, 1, "TEST", "coding.maven.test", 60,
                Instant.parse("2026-08-20T01:00:00Z"), Instant.parse("2026-08-20T01:01:00Z"),
                "EXITED", 0, "passed", null, "a".repeat(64),
                new CodingAttemptProjection.ArtifactSummary(
                        UUID.randomUUID(), "COMMAND_LOG", "text/plain", 6, "b".repeat(64)));
        when(service.commands(any(), any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(new CodingEvidencePage<>(
                        List.of(command), Optional.of(new CodingEvidenceCursor(1, commandId))));

        byte[] response = client.get()
                .uri(root() + "/attempts/" + executionId + "/coding/commands?limit=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBodyContent();
        String body = new String(java.util.Objects.requireNonNull(response),
                java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("coding.maven.test"));
        String cursor = extractCursor(body);

        client.get().uri(root() + "/attempts/" + executionId
                        + "/coding/test-evidence?after=" + cursor)
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_cursor");
    }

    private CodingAttemptProjection projection() {
        CodingAttemptProjection.WorkspaceSummary workspace = new CodingAttemptProjection.WorkspaceSummary(
                UUID.randomUUID(), "crewscope-java", "a".repeat(40),
                "crewscope/tasks/00000000-0000-0000-0000-000000000001/attempt-1",
                "ACTIVE", 0, null, null, "b".repeat(64), 2,
                Instant.parse("2026-09-20T01:00:00Z"), Instant.parse("2026-08-20T01:00:00Z"),
                Instant.parse("2026-08-20T01:02:00Z"));
        CodingAttemptProjection.SandboxSummary sandbox = new CodingAttemptProjection.SandboxSummary(
                "NONE", 2, 2048, 256, 300, 1_000_000, true,
                20, 100, 1_000_000, 200, 5_000_000, 10_000_000, 3,
                "maven-java-17", 1);
        return new CodingAttemptProjection(executionId, 1, workspace, Optional.of(sandbox),
                Optional.empty(), Optional.empty(), 0, 0);
    }

    private String root() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId + "/tasks/" + taskId;
    }

    private static String extractCursor(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json)
                    .get("nextCursor").asText();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
