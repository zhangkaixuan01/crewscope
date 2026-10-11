package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workgraph.CreateWorkItemRelationCommand;
import io.crewscope.application.workgraph.RemoveWorkItemRelationCommand;
import io.crewscope.application.workgraph.WorkItemRelationCommandService;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workgraph.WorkItemRelationConflictException;
import io.crewscope.domain.workgraph.WorkItemRelationId;
import io.crewscope.domain.workgraph.WorkItemRelationType;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves the WorkGraph relation commands: 202 receipt, idempotency, mapped failures, 422 guard. */
class WorkGraphControllerTest {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId team = TeamId.generate();
    private final WorkItemId from = WorkItemId.generate();
    private final WorkItemId to = WorkItemId.generate();
    private final UtcTimestamp now = UtcTimestamp.parse("2026-10-11T12:00:00Z");
    private final Principal actor =
            Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Relator",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    now);
    private final WorkItemRelation relation =
            WorkItemRelation.reconstitute(
                    WorkItemRelationId.generate(),
                    new WorkItemRelation.RelationEndpoint(
                            from,
                            new WorkItemScope(
                                    organizationId,
                                    team,
                                    WorkspaceId.generate(),
                                    WorkProjectId.generate())),
                    new WorkItemRelation.RelationEndpoint(
                            to,
                            new WorkItemScope(
                                    organizationId,
                                    team,
                                    WorkspaceId.generate(),
                                    WorkProjectId.generate())),
                    WorkItemRelationType.BLOCKS,
                    AuditMetadata.createdBy(PrincipalId.generate(), now));

    private WorkItemRelationCommandService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(WorkItemRelationCommandService.class);
        TeamRequestIdentityResolver resolver =
                (authentication, organization, correlationId) ->
                        Mono.just(new TeamAccessContext(actor, false));
        client =
                WebTestClient.bindToController(new WorkGraphController(service, resolver))
                        .controllerAdvice(new ApiExceptionHandler())
                        .build();
    }

    @Test
    void createsARelationAndAnswersTheAcceptedReceipt() {
        CommandReceipt receipt =
                new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
        AtomicReference<CreateWorkItemRelationCommand> submitted = new AtomicReference<>();
        when(service.create(any(), eq(team), any()))
                .thenAnswer(
                        invocation -> {
                            submitted.set(
                                    invocation.getArgument(
                                            2, CreateWorkItemRelationCommand.class));
                            return CommandExecution.completed(relation, receipt);
                        });

        client
                .post()
                .uri(root())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "create-relation-http-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        """
                        {"type":"DEPENDS_ON","fromWorkItemId":"%s","toWorkItemId":"%s"}
                        """
                                .formatted(from, to))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.domainEventId")
                .isEqualTo(receipt.domainEventId().toString())
                .jsonPath("$.committedVersion")
                .isEqualTo(0);

        assertEquals(WorkItemRelationType.DEPENDS_ON, submitted.get().type());
        assertEquals(from, submitted.get().from());
        assertEquals(to, submitted.get().to());
    }

    @Test
    void replaysReturnTheOriginalReceipt() {
        CommandReceipt receipt =
                new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
        when(service.create(any(), eq(team), any()))
                .thenReturn(CommandExecution.replayed(receipt));

        client
                .post()
                .uri(root())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "create-relation-replay-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(relationBody())
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.domainEventId")
                .isEqualTo(receipt.domainEventId().toString());
    }

    @Test
    void commandsWithoutAnIdempotencyKeyAreRejected() {
        client
                .post()
                .uri(root())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(relationBody())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_request");

        client
                .delete()
                .uri(root() + "/" + relation.id())
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void conflictCycleAndSplitGuardMapOntoConflict() {
        when(service.create(any(), eq(team), any()))
                .thenThrow(new WorkItemRelationConflictException(from, to));

        client
                .post()
                .uri(root())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "conflict-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(relationBody())
                .exchange()
                .expectStatus()
                .isEqualTo(409);
    }

    @Test
    void policyDenialsStayForbidden() {
        when(service.create(any(), eq(team), any()))
                .thenThrow(new PolicyDeniedException("relate WorkItems in the source WorkProject"));

        client
                .post()
                .uri(root())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "denied-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(relationBody())
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void archivedEndpointsAndSelfReferenceAreUnprocessable() {
        when(service.create(any(), eq(team), any()))
                .thenThrow(
                        new DomainValidationException(
                                "workItemRelation.endpoints",
                                "must not reference an archived WorkItem"));

        client
                .post()
                .uri(root())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "archived-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(relationBody())
                .exchange()
                .expectStatus()
                .isEqualTo(422)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_value")
                .jsonPath("$.details.field")
                .isEqualTo("workItemRelation.endpoints");

        when(service.create(any(), eq(team), any()))
                .thenThrow(
                        new DomainValidationException(
                                "workItemRelation.endpoints", "must differ"));
        client
                .post()
                .uri(root())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "self-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(relationBody())
                .exchange()
                .expectStatus()
                .isEqualTo(422);
    }

    @Test
    void malformedIdentifiersAreRejectedPerField() {
        client
                .post()
                .uri(root())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "bad-ids-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"type\":\"BLOCKS\",\"fromWorkItemId\":\"not-a-uuid\","
                                + "\"toWorkItemId\":\"" + to + "\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_request");

        client
                .delete()
                .uri(root() + "/not-a-uuid")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "bad-ids-2")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void removesOneRelationAndAnswersTheAcceptedReceipt() {
        CommandReceipt receipt =
                new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
        AtomicReference<RemoveWorkItemRelationCommand> submitted = new AtomicReference<>();
        when(service.remove(any(), eq(team), any()))
                .thenAnswer(
                        invocation -> {
                            submitted.set(
                                    invocation.getArgument(
                                            2, RemoveWorkItemRelationCommand.class));
                            return CommandExecution.completed(relation, receipt);
                        });

        client
                .delete()
                .uri(root() + "/" + relation.id())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "remove-relation-http-1")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.domainEventId")
                .isEqualTo(receipt.domainEventId().toString());

        assertEquals(relation.id(), submitted.get().relationId());
    }

    private String root() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + team + "/work-item-relations";
    }

    private String relationBody() {
        return "{\"type\":\"BLOCKS\",\"fromWorkItemId\":\"" + from
                + "\",\"toWorkItemId\":\"" + to + "\"}";
    }
}
