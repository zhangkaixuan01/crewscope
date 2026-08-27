package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.audit.AuditExportBatch;
import io.crewscope.application.audit.AuditExportRequest;
import io.crewscope.application.audit.AuditPage;
import io.crewscope.application.audit.AuditQuery;
import io.crewscope.application.audit.AuditQueryApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.audit.AuditCorrelationReference;
import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditEventId;
import io.crewscope.domain.audit.AuditIdentityChain;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.audit.AuditProviderReference;
import io.crewscope.domain.audit.AuditQueryEvent;
import io.crewscope.domain.audit.AuditRetentionLevel;
import io.crewscope.domain.audit.AuditSummarySchema;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Combination filters, safe DTO, export bounds and safe errors for M6-A03. */
class AuditControllerM6A03Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final PrincipalId ACTOR_ID = PrincipalId.generate();
    private static final ProviderBindingId PROVIDER_BINDING_ID = ProviderBindingId.generate();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-27T03:00:00Z");

    private AuditQueryApplicationService service;
    private TeamAccessContext access;
    private WebTestClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(AuditQueryApplicationService.class);
        access = mock(TeamAccessContext.class);
        TeamRequestIdentityResolver resolver = mock(TeamRequestIdentityResolver.class);
        when(resolver.resolve(any(), eq(ORGANIZATION_ID), any())).thenReturn(Mono.just(access));
        AuditCursorCodec codec = new AuditCursorCodec(new TeamActivityCursorKeyRing(
                "k1",
                Map.of("k1", Base64.getEncoder().encodeToString(new byte[32]))));
        ObjectProvider<AuditCursorCodec> codecs = mock(ObjectProvider.class);
        when(codecs.getIfAvailable()).thenReturn(codec);
        client = WebTestClient.bindToController(new AuditController(service, resolver, codecs))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void normalizesCombinationFiltersAndUsesStableKeysetContinuation() {
        when(service.query(eq(access), any(UUID.class), any(AuditQuery.class)))
                .thenAnswer(invocation -> {
                    AuditQuery query = invocation.getArgument(2);
                    return new AuditPage(query, List.of(), false);
                });

        client.get()
                .uri(route("")
                        + "?occurredFrom=2026-08-01T00:00:00Z"
                        + "&occurredBefore=2026-08-27T00:00:00Z"
                        + "&categories=security,work"
                        + "&outcomes=succeeded"
                        + "&actorIds=" + ACTOR_ID
                        + "&subjectType=work_item"
                        + "&subjectId=" + UUID.randomUUID()
                        + "&providerBindingId=" + PROVIDER_BINDING_ID
                        + "&correlationId=" + CORRELATION_ID
                        + "&limit=75")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(0)
                .jsonPath("$.nextCursor").doesNotExist();

        ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass(AuditQuery.class);
        verify(service).query(eq(access), any(UUID.class), captor.capture());
        AuditQuery query = captor.getValue();
        assertEquals(75, query.limit());
        assertEquals(
                Set.of(AuditEventCategory.SECURITY, AuditEventCategory.WORK),
                query.filter().categories());
        assertEquals(Set.of(AuditOutcome.SUCCEEDED), query.filter().outcomes());
        assertEquals(Set.of(ACTOR_ID), query.filter().actorIds());
        assertEquals(Optional.of(PROVIDER_BINDING_ID), query.filter().providerBindingId());
        assertEquals(Optional.of(CORRELATION_ID), query.filter().correlationId());
    }

    @Test
    void responseUsesAnExplicitSafeWhitelistAndReturnsSignedNextCursor() {
        AuditQueryEvent event = event();
        when(service.query(eq(access), any(UUID.class), any(AuditQuery.class)))
                .thenAnswer(invocation -> new AuditPage(
                        invocation.getArgument(2), List.of(event), true));

        String body = client.get()
                .uri(route(""))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        org.junit.jupiter.api.Assertions.assertNotNull(body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("AUDIT_EXPLORER_QUERIED"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains(PROVIDER_BINDING_ID.toString()));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("nextCursor"));
        assertFalse(body.contains("organizationId"));
        assertFalse(body.contains("teamId"));
        assertFalse(body.contains("payload"));
        assertFalse(body.contains("authorizationContext"));
        assertFalse(body.contains("credential"));
        assertFalse(body.contains("endpoint"));
        assertFalse(body.contains("traceId"));
    }

    @Test
    void exportUsesTheReviewedMediaTypeAndAttachmentName() {
        when(service.export(eq(access), any(UUID.class), any(AuditExportRequest.class)))
                .thenAnswer(invocation -> new AuditExportBatch(
                        invocation.getArgument(2), NOW, List.of()));

        client.post()
                .uri(route("/export"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "occurredFrom": "2026-08-01T00:00:00Z",
                          "occurredBefore": "2026-08-27T00:00:00Z",
                          "categories": ["SECURITY"],
                          "maximumRows": 1000
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectHeader().contentType(AuditController.EXPORT_MEDIA_TYPE)
                .expectHeader().valueEquals(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"crewscope-audit-export.json\"")
                .expectBody()
                .jsonPath("$.rowCount").isEqualTo(0)
                .jsonPath("$.maximumRows").isEqualTo(1000);
    }

    @Test
    void rejectsUnboundedOrMalformedRequestsBeforeServiceExecution() {
        client.post()
                .uri(route("/export"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"maximumRows\":10001}")
                .exchange()
                .expectStatus().isEqualTo(422);

        client.post()
                .uri(route("/export"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "occurredFrom": "2026-07-01T00:00:00Z",
                          "occurredBefore": "2026-08-02T00:00:00Z"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(422);

        client.get()
                .uri(route("") + "?subjectType=TASK")
                .exchange()
                .expectStatus().isBadRequest();

        client.get()
                .uri(route("") + "?occurredFrom=private-value")
                .exchange()
                .expectStatus().isBadRequest();

        client.post()
                .uri(route("/export"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"maximumRows\":100,\"rawPayload\":true}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void mapsAuthorizationAndUnknownFailuresToSafeErrors() {
        doThrow(new PolicyDeniedException("read Audit events"))
                .when(service)
                .query(eq(access), any(UUID.class), any(AuditQuery.class));

        client.get()
                .uri(route(""))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("policy_denied");

        when(service.query(eq(access), any(UUID.class), any(AuditQuery.class)))
                .thenThrow(new IllegalStateException("jdbc:postgresql://secret/internal"));
        client.get()
                .uri(route(""))
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.code").isEqualTo("internal_error")
                .jsonPath("$.message").isEqualTo("The request could not be completed")
                .jsonPath("$.details").isEmpty();
    }

    private static AuditQueryEvent event() {
        AuditSummarySchema schema = new AuditSummarySchema(
                EventType.from("AUDIT_EXPLORER_QUERIED"),
                SchemaVersion.V1,
                AuditEventCategory.SECURITY,
                Set.of("operation", "result", "rowCount"),
                Set.of());
        return new AuditQueryEvent(
                AuditEventId.generate(),
                ORGANIZATION_ID,
                TEAM_ID,
                AuditEventCategory.SECURITY,
                AuditOutcome.SUCCEEDED,
                AuditIdentityChain.from(
                        Optional.of(ACTOR_ID),
                        EventActor.principal(EventActorType.USER, ACTOR_ID)),
                new AggregateReference("TEAM", TEAM_ID.value()),
                Optional.of(new AuditProviderReference(
                        PROVIDER_BINDING_ID, ConnectionId.generate(), Optional.empty())),
                new AuditCorrelationReference(
                        CORRELATION_ID, Optional.of(UUID.randomUUID()), Optional.of(UUID.randomUUID())),
                AuditRetentionLevel.EXTENDED,
                NOW,
                schema.project(Map.of(
                        "operation", "QUERY", "result", "SUCCEEDED", "rowCount", "1")));
    }

    private static String route(String suffix) {
        return "/api/v1/organizations/"
                + ORGANIZATION_ID
                + "/teams/"
                + TEAM_ID
                + "/audit-events"
                + suffix;
    }
}
