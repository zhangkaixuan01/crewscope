package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.collaboration.LarkAdministrationCommandService;
import io.crewscope.application.collaboration.LarkCollaborationApplicationService;
import io.crewscope.application.collaboration.LarkConnectionApplicationService;
import io.crewscope.application.collaboration.LarkConnectionPreflightResult;
import io.crewscope.application.collaboration.LarkConnectionView;
import io.crewscope.application.collaboration.LarkMemberMappingApplicationService;
import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.application.notification.NotificationAdministrationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Authorization order, closed bodies and strong-version errors for the M6-A04 boundary. */
class LarkAdministrationControllerM6A04Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();

    private LarkConnectionApplicationService connections;
    private LarkCollaborationApplicationService collaboration;
    private LarkMemberMappingApplicationService mappings;
    private NotificationAdministrationService notifications;
    private LarkMappingCursorCodec mappingCursors;
    private NotificationDeliveryCursorCodec deliveryCursors;
    private TeamAccessContext access;
    private Principal actor;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        connections = mock(LarkConnectionApplicationService.class);
        collaboration = mock(LarkCollaborationApplicationService.class);
        mappings = mock(LarkMemberMappingApplicationService.class);
        notifications = mock(NotificationAdministrationService.class);
        mappingCursors = mock(LarkMappingCursorCodec.class);
        deliveryCursors = mock(NotificationDeliveryCursorCodec.class);
        access = mock(TeamAccessContext.class);
        actor = mock(Principal.class);
        when(access.actor()).thenReturn(actor);
        TeamRequestIdentityResolver identities = mock(TeamRequestIdentityResolver.class);
        when(identities.resolve(any(), eq(ORGANIZATION_ID), any()))
                .thenReturn(Mono.just(access));
        LarkAdministrationController controller = new LarkAdministrationController(
                connections,
                collaboration,
                mappings,
                mock(LarkAdministrationCommandService.class),
                notifications,
                mappingCursors,
                deliveryCursors,
                identities);
        client = WebTestClient.bindToController(controller)
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void mappingAndDeliveryAuthorizationPrecedesCursorDecoding() {
        doThrow(new PolicyDeniedException("manage Lark mappings"))
                .when(mappings)
                .requireAdministrator(ORGANIZATION_ID, TEAM_ID, actor);

        client.get()
                .uri(route("/member-mappings?after=invalid-token"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("policy_denied");
        verify(mappingCursors, never()).decode(any(), any(), any(), any());

        doThrow(new PolicyDeniedException("manage notification deliveries"))
                .when(notifications)
                .requireAdministrator(access, ORGANIZATION_ID, TEAM_ID);
        client.get()
                .uri(route("/notification-deliveries?after=invalid-token"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("policy_denied");
        verify(deliveryCursors, never()).decode(any(), any(), any(), any());
    }

    @Test
    void preflightVersionMismatchUsesTheOptimisticConflictEnvelope() {
        ProviderBindingId bindingId = ProviderBindingId.generate();
        when(collaboration.preflight(any())).thenReturn(new LarkConnectionPreflightResult(
                bindingId,
                2,
                ConnectionId.generate(),
                3,
                ConnectionGrantId.generate(),
                4,
                UtcTimestamp.parse("2026-08-27T06:00:00Z")));

        client.post()
                .uri(route("/bindings/" + bindingId + "/preflight"))
                .header(ApiHeaders.IF_MATCH, "\"1\"")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("optimistic_lock_conflict")
                .jsonPath("$.currentVersion").isEqualTo(2)
                .jsonPath("$.details.expectedVersion").isEqualTo("1")
                .jsonPath("$.details.actualVersion").isEqualTo("2");
    }

    @Test
    void connectionResponseExposesTheProviderBindingStrongVersionSeparatelyFromCredentialVersion() {
        ConnectionId connectionId = ConnectionId.generate();
        ProviderBindingId bindingId = ProviderBindingId.generate();
        UtcTimestamp now = UtcTimestamp.parse("2026-08-27T06:00:00Z");
        when(connections.get(access, ORGANIZATION_ID, TEAM_ID, connectionId))
                .thenReturn(new LarkConnectionView(
                        connectionId,
                        TEAM_ID,
                        Optional.of(bindingId),
                        Optional.of(7L),
                        "****1234",
                        ConnectionStatus.ACTIVE,
                        CredentialStatus.ACTIVE,
                        Optional.empty(),
                        now,
                        now,
                        11));

        client.get()
                .uri(route("/connections/" + connectionId))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(ApiHeaders.ETAG, "\"11\"")
                .expectBody()
                .jsonPath("$.providerBindingId.value").isEqualTo(bindingId.toString())
                .jsonPath("$.providerBindingVersion").isEqualTo(7)
                .jsonPath("$.version").isEqualTo(11);
    }

    @Test
    void connectionCommandRejectsUnknownPropertiesBeforeSecretHandling() {
        client.post()
                .uri(route("/connections"))
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m6-a04-closed-body")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "tenantKey":"tenant-a",
                          "appId":"cli-a",
                          "appSecret":"secret-a",
                          "providerEndpoint":"https://attacker.invalid"
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        verify(connections, never()).create(any(), any(), any(), anyLong());
    }

    private static String route(String suffix) {
        return "/api/v1/organizations/" + ORGANIZATION_ID + "/teams/" + TEAM_ID
                + "/lark" + suffix;
    }
}
