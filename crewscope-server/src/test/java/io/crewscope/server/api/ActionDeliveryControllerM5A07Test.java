package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.action.ActionBundleView;
import io.crewscope.application.action.ActionDeliveryApplicationService;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.Confirmation;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** M5-A07 routes, strong preconditions and sensitive Action DTO whitelist tests. */
class ActionDeliveryControllerM5A07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T14:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final String bundleId = UUID.randomUUID().toString();
    private final String confirmationId = UUID.randomUUID().toString();
    private final String dispatchId = UUID.randomUUID().toString();
    private final String digest = "a".repeat(64);
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, teamId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.TEAM,
            NOW);
    private ActionDeliveryApplicationService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(ActionDeliveryApplicationService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(new ActionDeliveryController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void plansAndConfirmsOnlyThroughReceiptCommandsWithStrongBundleCoordinates() {
        CommandReceipt receipt = receipt();
        when(service.plan(any(), any(), any(), any(), any(), any()))
                .thenReturn(CommandExecution.completed(mock(ActionBundle.class), receipt));
        when(service.confirm(any(), any(), any(), any(), any(), any(), anyLong(), anyString()))
                .thenReturn(CommandExecution.completed(mock(Confirmation.class), receipt));

        client.post()
                .uri(base() + "/bundles")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a07-plan")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"reviewDecisionId":"%s","providerBindingId":"%s",
                         "repositoryId":"101","title":"Reviewed delivery",
                         "body":"Create the reviewed Draft PR"}
                        """.formatted(UUID.randomUUID(), UUID.randomUUID()))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.domainEventId").isEqualTo(receipt.domainEventId().toString())
                .jsonPath("$.title").doesNotExist()
                .jsonPath("$.body").doesNotExist();

        client.post()
                .uri(base() + "/bundles/" + bundleId + "/confirmations")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a07-confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"bundleDigest\":\"" + digest + "\"}")
                .exchange()
                .expectStatus().isEqualTo(428);

        client.post()
                .uri(base() + "/bundles/" + bundleId + "/confirmations")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a07-confirm")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"bundleDigest\":\"" + digest + "\"}")
                .exchange()
                .expectStatus().isAccepted();
    }

    @Test
    void exposesRiskParametersAndResultsWithoutInternalExecutionOrProviderSecrets() {
        when(service.get(any(), any(), any(), any(), any(), any())).thenReturn(view());

        client.get()
                .uri(base() + "/bundles/" + bundleId)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.digest").isEqualTo(digest)
                .jsonPath("$.actions[0].risk").isEqualTo("HIGH_RISK_WRITE")
                .jsonPath("$.actions[0].parameters.branch")
                    .isEqualTo("refs/heads/crewscope/tasks/example/attempt-1")
                .jsonPath("$.actions[0].dispatch.status").isEqualTo("UNKNOWN")
                .jsonPath("$.actions[0].receipt.externalIdentityHash").isEqualTo("safe-hash")
                .jsonPath("$.actions[0].connectionId").doesNotExist()
                .jsonPath("$.actions[0].idempotencyKey").doesNotExist()
                .jsonPath("$.actions[0].dispatch.workerId").doesNotExist()
                .jsonPath("$.actions[0].dispatch.fencingToken").doesNotExist()
                .jsonPath("$.actions[0].receipt.externalId").doesNotExist()
                .jsonPath("$.actions[0].receipt.businessKey").doesNotExist()
                .jsonPath("$.credentialId").doesNotExist();
    }

    @Test
    void cancellationAndManualReconciliationUseReceiptsButNoDirectDispatchRouteExists() {
        CommandReceipt receipt = receipt();
        when(service.cancel(any(), any(), any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(CommandExecution.completed(mock(Confirmation.class), receipt));
        when(service.resolveManually(
                        any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any(),
                        any(), anyString()))
                .thenReturn(CommandExecution.completed(mock(ActionDispatch.class), receipt));

        client.post()
                .uri(base() + "/confirmations/" + confirmationId + "/cancel")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a07-cancel")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"MEMBER_CANCELLED\"}")
                .exchange()
                .expectStatus().isAccepted();

        client.post()
                .uri(base() + "/dispatches/" + dispatchId + "/manual-resolution")
                .header(ApiHeaders.IF_MATCH, "\"3\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"result":"MANUALLY_FAILED","reason":"NO_EXTERNAL_OBJECT_VERIFIED",
                         "explanation":"Provider audit proves no external object exists"}
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        client.post()
                .uri(base() + "/dispatches/" + dispatchId + "/manual-resolution")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a07-manual")
                .header(ApiHeaders.IF_MATCH, "\"3\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"result":"MANUALLY_FAILED","reason":"NO_EXTERNAL_OBJECT_VERIFIED",
                         "explanation":"Provider audit proves no external object exists"}
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.domainEventId").isEqualTo(receipt.domainEventId().toString());

        client.post()
                .uri(base() + "/dispatches/" + dispatchId)
                .header(ApiHeaders.IF_MATCH, "\"3\"")
                .exchange()
                .expectStatus().isNotFound();
    }

    private ActionBundleView view() {
        var receipt = new ActionBundleView.ActionReceiptView(
                UUID.randomUUID().toString(),
                "SUCCEEDED",
                "ACTIVE_QUERY",
                "BRANCH",
                "safe-hash",
                "b".repeat(40),
                "REMOTE_HEAD_MATCHED",
                null,
                NOW.toString());
        var dispatch = new ActionBundleView.DispatchView(
                dispatchId,
                3,
                "UNKNOWN",
                1,
                1,
                NOW.toString(),
                null,
                "NOT_REQUIRED");
        var parameters = new ActionBundleView.ActionParameterView(
                "101",
                "refs/heads/crewscope/tasks/example/attempt-1",
                "b".repeat(40),
                null,
                null, null, null, null, null, null);
        var action = new ActionBundleView.PlannedActionView(
                UUID.randomUUID().toString(),
                1,
                "PUSH_BRANCH",
                "HIGH_RISK_WRITE",
                "c".repeat(64),
                NOW.toString(),
                List.of(),
                parameters,
                dispatch,
                receipt,
                null);
        return new ActionBundleView(
                bundleId,
                0,
                digest,
                "CURRENT",
                null,
                taskId.toString(),
                executionId.toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "crewscope-java",
                "d".repeat(40),
                "b".repeat(40),
                null,
                List.of(action));
    }

    private CommandReceipt receipt() {
        return new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    }

    private String base() {
        return "/api/v1/organizations/" + organizationId
                + "/teams/" + teamId
                + "/tasks/" + taskId
                + "/attempts/" + executionId
                + "/actions";
    }
}
