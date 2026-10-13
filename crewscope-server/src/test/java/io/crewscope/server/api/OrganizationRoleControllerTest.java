package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationRoleApplicationService;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.organization.role.LastOrganizationOwnerProtectionException;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.organization.role.OrganizationRoleGrantId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Role-grant routes: accepted receipts, replay marker, strict headers and the 409 protections. */
class OrganizationRoleControllerTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final PrincipalId targetId = PrincipalId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);

    private OrganizationRoleApplicationService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(OrganizationRoleApplicationService.class);
        OrganizationRequestIdentityResolver resolver =
                (authentication, organization, correlationId) ->
                        Mono.just(new OrganizationAccessContext(actor));
        client = WebTestClient.bindToController(
                        new OrganizationRoleController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsGrantsWithRevokedHistoryBehindActiveOnes() {
        OrganizationRoleGrant active = OrganizationRoleGrant.grant(
                organizationId, targetId, OrganizationRole.ORG_AUDITOR, actor.id(), null, NOW);
        OrganizationRoleGrant revoked =
                OrganizationRoleGrant.grant(
                                organizationId, targetId, OrganizationRole.ORG_ADMIN,
                                actor.id(), null, NOW)
                        .revoke(0, actor.id(), "rotation", NOW);
        when(service.listGrants(any(), eq(organizationId))).thenReturn(List.of(active, revoked));

        client
                .get()
                .uri("/api/v1/organizations/{organizationId}/role-grants", organizationId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[0].status")
                .isEqualTo("ACTIVE")
                .jsonPath("$[0].role")
                .isEqualTo("ORG_AUDITOR")
                .jsonPath("$[1].status")
                .isEqualTo("REVOKED")
                .jsonPath("$[1].revokedAt")
                .isEqualTo(revoked.revokedAt().orElseThrow().toString());
    }

    @Test
    void grantsReturnTheAcceptedReceiptContract() {
        CommandReceipt receipt =
                new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
        when(service.grant(any(), eq(organizationId), eq(targetId),
                        eq(OrganizationRole.ORG_AUDITOR), eq("onboarding")))
                .thenReturn(CommandExecution.completed(
                        OrganizationRoleGrant.grant(
                                organizationId, targetId, OrganizationRole.ORG_AUDITOR,
                                actor.id(), "onboarding", NOW),
                        receipt));

        client
                .post()
                .uri("/api/v1/organizations/{organizationId}/role-grants", organizationId)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "grant-http-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"principalId\":\"" + targetId + "\",\"role\":\"ORG_AUDITOR\","
                                + "\"reason\":\"onboarding\"}")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.commandId")
                .isEqualTo(receipt.commandId().toString());

        verify(service)
                .grant(any(), eq(organizationId), eq(targetId),
                        eq(OrganizationRole.ORG_AUDITOR), eq("onboarding"));
    }

    @Test
    void aReplayedGrantIsMarkedWithTheReplayHeader() {
        CommandReceipt receipt =
                new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
        when(service.grant(any(), eq(organizationId), eq(targetId),
                        eq(OrganizationRole.ORG_AUDITOR), isNull()))
                .thenReturn(CommandExecution.replayed(receipt));

        client
                .post()
                .uri("/api/v1/organizations/{organizationId}/role-grants", organizationId)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "grant-http-2")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"principalId\":\"" + targetId + "\",\"role\":\"ORG_AUDITOR\"}")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectHeader()
                .valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
    }

    @Test
    void anUnknownRoleVocabularyIsRejected() {
        client
                .post()
                .uri("/api/v1/organizations/{organizationId}/role-grants", organizationId)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "grant-http-3")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"principalId\":\"" + targetId + "\",\"role\":\"PLATFORM_ADMIN\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_request");
    }

    @Test
    void grantsRequireTheIdempotencyKey() {
        client
                .post()
                .uri("/api/v1/organizations/{organizationId}/role-grants", organizationId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"principalId\":\"" + targetId + "\",\"role\":\"ORG_AUDITOR\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_request");
    }

    @Test
    void revokesReturnTheAcceptedReceiptContract() {
        OrganizationRoleGrantId grantId = OrganizationRoleGrantId.generate();
        CommandReceipt receipt =
                new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
        when(service.revoke(any(), eq(organizationId), eq(grantId), eq(0L), eq("departure")))
                .thenReturn(CommandExecution.completed(
                        OrganizationRoleGrant.grant(
                                        organizationId, targetId, OrganizationRole.ORG_AUDITOR,
                                        actor.id(), null, NOW)
                                .revoke(0, actor.id(), "departure", NOW),
                        receipt));

        client
                .post()
                .uri(
                        "/api/v1/organizations/{organizationId}/role-grants/{grantId}/revoke",
                        organizationId,
                        grantId)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "revoke-http-1")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"departure\"}")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.commandId")
                .isEqualTo(receipt.commandId().toString());
    }

    @Test
    void revokesRequireBothConcurrencyContracts() {
        OrganizationRoleGrantId grantId = OrganizationRoleGrantId.generate();

        client
                .post()
                .uri(
                        "/api/v1/organizations/{organizationId}/role-grants/{grantId}/revoke",
                        organizationId,
                        grantId)
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"departure\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_request");

        client
                .post()
                .uri(
                        "/api/v1/organizations/{organizationId}/role-grants/{grantId}/revoke",
                        organizationId,
                        grantId)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "revoke-http-2")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"departure\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(428)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("precondition_required");
    }

    @Test
    void theLastOwnerProtectionSurfacesAsAConflict() {
        OrganizationRoleGrantId grantId = OrganizationRoleGrantId.generate();
        when(service.revoke(any(), eq(organizationId), eq(grantId), eq(0L), any()))
                .thenThrow(new LastOrganizationOwnerProtectionException(
                        organizationId.toString(), targetId));

        client
                .post()
                .uri(
                        "/api/v1/organizations/{organizationId}/role-grants/{grantId}/revoke",
                        organizationId,
                        grantId)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "revoke-http-3")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"departure\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("last_owner_protection");
    }
}
