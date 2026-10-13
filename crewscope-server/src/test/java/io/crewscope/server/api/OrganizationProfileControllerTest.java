package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationRepository;
import io.crewscope.application.organization.OrganizationSettingApplicationService;
import io.crewscope.application.organization.OrganizationSettingApplicationService.SettingView;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.organization.Organization;
import io.crewscope.domain.organization.OrganizationStatus;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Organization root and settings routes: identity facts, default projection, command contracts. */
class OrganizationProfileControllerTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Admin",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);

    private OrganizationRepository organizations;
    private OrganizationSettingApplicationService settings;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        organizations = mock(OrganizationRepository.class);
        settings = mock(OrganizationSettingApplicationService.class);
        OrganizationRequestIdentityResolver resolver =
                (authentication, organization, correlationId) ->
                        Mono.just(new OrganizationAccessContext(actor));
        client = WebTestClient.bindToController(
                        new OrganizationProfileController(organizations, settings, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsTheReadOnlyOrganizationIdentity() {
        when(organizations.findById(organizationId))
                .thenReturn(Optional.of(Organization.reconstitute(
                        organizationId, "CrewScope", OrganizationStatus.ACTIVE, 4, NOW, NOW)));

        client
                .get()
                .uri("/api/v1/organizations/{organizationId}", organizationId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody()
                .jsonPath("$.organizationId")
                .isEqualTo(organizationId.toString())
                .jsonPath("$.name")
                .isEqualTo("CrewScope")
                .jsonPath("$.status")
                .isEqualTo("ACTIVE")
                .jsonPath("$.version")
                .isEqualTo(4);
    }

    @Test
    void anUnknownOrganizationIsANotFound() {
        when(organizations.findById(organizationId)).thenReturn(Optional.empty());

        client
                .get()
                .uri("/api/v1/organizations/{organizationId}", organizationId)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("aggregate_not_found");
    }

    @Test
    void settingsDefaultStateProjectsForeverAtVersionZeroWithAnEtag() {
        when(settings.findSettings(any(), eq(organizationId)))
                .thenReturn(new SettingView(null, 0, false));

        client
                .get()
                .uri("/api/v1/organizations/{organizationId}/settings", organizationId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(ApiHeaders.ETAG, "\"0\"")
                .expectBody()
                .jsonPath("$.auditEventRetentionDays")
                .doesNotExist()
                .jsonPath("$.version")
                .isEqualTo(0);
    }

    @Test
    void settingsChangeReturnsTheAcceptedReceiptContract() {
        CommandReceipt receipt =
                new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
        when(settings.changeSettings(any(), eq(organizationId), eq(0L), eq(90)))
                .thenReturn(CommandExecution.completed(new SettingView(90, 1, true), receipt));

        client
                .put()
                .uri("/api/v1/organizations/{organizationId}/settings", organizationId)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "settings-http-1")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"auditEventRetentionDays\":90}")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.commandId")
                .isEqualTo(receipt.commandId().toString());

        verify(settings).changeSettings(any(), eq(organizationId), eq(0L), eq(90));
    }

    @Test
    void settingsChangeRequiresBothConcurrencyHeaders() {
        client
                .put()
                .uri("/api/v1/organizations/{organizationId}/settings", organizationId)
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"auditEventRetentionDays\":90}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_request");

        client
                .put()
                .uri("/api/v1/organizations/{organizationId}/settings", organizationId)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "settings-http-2")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"auditEventRetentionDays\":90}")
                .exchange()
                .expectStatus()
                .isEqualTo(428)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("precondition_required");
    }

    @Test
    void anInvalidOrganizationIdentifierIsRejectedBeforeResolution() {
        client
                .get()
                .uri("/api/v1/organizations/not-a-uuid")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_request");
    }
}
