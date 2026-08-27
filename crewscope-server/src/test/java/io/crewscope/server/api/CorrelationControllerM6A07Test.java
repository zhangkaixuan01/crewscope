package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.correlation.CorrelationEvent;
import io.crewscope.application.correlation.CorrelationEventSource;
import io.crewscope.application.correlation.CorrelationObjectReference;
import io.crewscope.application.correlation.CorrelationObjectType;
import io.crewscope.application.correlation.CorrelationPage;
import io.crewscope.application.correlation.CorrelationQueryService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Public DTO, no-store and server-owned bidirectional link proof for M6-A07. */
class CorrelationControllerM6A07Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final UUID correlationId = UUID.randomUUID();
    private CorrelationQueryService service;
    private TeamAccessContext access;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(CorrelationQueryService.class);
        access = mock(TeamAccessContext.class);
        TeamRequestIdentityResolver identities = mock(TeamRequestIdentityResolver.class);
        when(identities.resolve(any(), eq(organizationId), any())).thenReturn(Mono.just(access));
        CorrelationCursorCodec codec = new CorrelationCursorCodec(
                new TeamActivityCursorKeyRing("k1", Map.of("k1", key())));
        @SuppressWarnings("unchecked")
        ObjectProvider<CorrelationCursorCodec> codecs = mock(ObjectProvider.class);
        when(codecs.getIfAvailable()).thenReturn(codec);
        client = WebTestClient.bindToController(
                        new CorrelationController(service, identities, codecs))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void exposesOnlySafeMetadataAndInternalObjectLinks() {
        UUID eventId = UUID.randomUUID();
        CorrelationObjectReference task = new CorrelationObjectReference(
                CorrelationObjectType.TASK, UUID.randomUUID());
        CorrelationEvent event = new CorrelationEvent(
                eventId, CorrelationEventSource.DOMAIN_EVENT, "TASK_DELEGATED_TO_AGENT",
                "USER", Optional.of(UUID.randomUUID()), Optional.of("SUCCEEDED"),
                UtcTimestamp.parse("2026-08-27T07:00:00Z"), List.of(task));
        CorrelationPage page = new CorrelationPage(
                correlationId,
                List.of(event),
                List.of(new CorrelationPage.CorrelationObjectLink(task, List.of(eventId))),
                false,
                Optional.empty());
        when(service.find(
                access, organizationId, teamId, correlationId, Optional.empty(),
                ApiPagination.DEFAULT_LIMIT)).thenReturn(page);

        client.get().uri(route()).exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.correlationId").isEqualTo(correlationId.toString())
                .jsonPath("$.events[0].eventType").isEqualTo("TASK_DELEGATED_TO_AGENT")
                .jsonPath("$.events[0].references[0].type").isEqualTo("TASK")
                .jsonPath("$.events[0].references[0].href").value(value -> {
                    String href = value.toString();
                    org.junit.jupiter.api.Assertions.assertTrue(href.startsWith("/activity?"));
                    org.junit.jupiter.api.Assertions.assertFalse(href.contains("http"));
                })
                .jsonPath("$.objects[0].relatedEventIds[0]").isEqualTo(eventId.toString())
                .jsonPath("$.events[0].payload").doesNotExist()
                .jsonPath("$.events[0].authorizationContext").doesNotExist()
                .jsonPath("$.events[0].providerBindingId").doesNotExist()
                .jsonPath("$.projectionGeneration").doesNotExist();
    }

    private String route() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
                + "/correlations/" + correlationId;
    }

    private static String key() {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) 7);
        return Base64.getEncoder().encodeToString(value);
    }
}
