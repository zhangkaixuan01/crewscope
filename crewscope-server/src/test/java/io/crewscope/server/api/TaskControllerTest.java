package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.task.AgentTaskCreationResult;
import io.crewscope.application.task.AgentTaskCreationService;
import io.crewscope.application.task.CreateAgentTaskCommand;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** HTTP contract for M3-A01 WorkItem delegation to an Agent. */
class TaskControllerTest {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemId workItemId = WorkItemId.generate();
    private final AgentProfileId profileId = AgentProfileId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, teamId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.TEAM,
            UtcTimestamp.parse("2026-08-15T06:00:00Z"));

    private AgentTaskCreationService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(AgentTaskCreationService.class);
        TeamRequestIdentityResolver resolver =
                (authentication, organization, correlationId) ->
                        Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(new TaskController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void acceptsACompleteDelegationAndPropagatesTheStrongWorkItemVersion() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
        when(service.create(any(), any(), any(), any(), any())).thenReturn(
                CommandExecution.completed(mock(AgentTaskCreationResult.class), receipt));

        client.post()
                .uri(root())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "delegate-task-http-1")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "objective":"Create durable Task API",
                          "acceptanceCriteria":["Create READY execution","Publish audit event"],
                          "executorAgentProfileId":"%s",
                          "providerBindingIds":[]
                        }
                        """.formatted(profileId))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.domainEventId").isEqualTo(receipt.domainEventId().toString())
                .jsonPath("$.committedVersion").isEqualTo(1);

        ArgumentCaptor<CreateAgentTaskCommand> command =
                ArgumentCaptor.forClass(CreateAgentTaskCommand.class);
        verify(service).create(any(), any(), any(), any(), command.capture());
        assertEquals(7, command.getValue().expectedWorkItemVersion());
        assertEquals(profileId, command.getValue().executorAgentProfileId());
        assertEquals("Create durable Task API", command.getValue().brief().objective());
    }

    @Test
    void requiresIdempotencyAndStrongIfMatchHeaders() {
        String body = validBody();
        client.post()
                .uri(root())
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");

        client.post()
                .uri(root())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "delegate-without-version")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(428)
                .expectBody().jsonPath("$.code").isEqualTo("precondition_required");

        client.post()
                .uri(root())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "delegate-weak-version")
                .header(ApiHeaders.IF_MATCH, "W/\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_if_match");
    }

    @Test
    void rejectsIncompleteInputsAndInvalidRouteIdentifiers() {
        client.post()
                .uri(root())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "delegate-invalid-input")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "objective":" ",
                          "acceptanceCriteria":[],
                          "executorAgentProfileId":"%s",
                          "providerBindingIds":[]
                        }
                        """.formatted(profileId))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");

        client.post()
                .uri("/api/v1/organizations/%s/teams/%s/work-projects/not-a-uuid/"
                        .formatted(organizationId, teamId)
                        + "work-items/" + workItemId + "/tasks")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "delegate-invalid-route")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    }

    private String validBody() {
        return """
                {
                  "objective":"Create durable Task API",
                  "acceptanceCriteria":["Create READY execution"],
                  "executorAgentProfileId":"%s",
                  "providerBindingIds":[]
                }
                """.formatted(profileId);
    }

    private String root() {
        return "/api/v1/organizations/" + organizationId
                + "/teams/" + teamId
                + "/work-projects/" + projectId
                + "/work-items/" + workItemId
                + "/tasks";
    }
}
