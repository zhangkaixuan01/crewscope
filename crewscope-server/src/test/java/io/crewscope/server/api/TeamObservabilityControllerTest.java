package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.observability.ModelUsageMonthDetailRow;
import io.crewscope.application.observability.TaskQualityStatistics;
import io.crewscope.application.observability.TeamObservabilityQueryService;
import io.crewscope.application.observability.TeamObservabilityQueryService.CostSource;
import io.crewscope.application.observability.TeamObservabilityQueryService.ModelUsageCurrencySubtotal;
import io.crewscope.application.observability.TeamObservabilityQueryService.ModelUsageMonthSummary;
import io.crewscope.application.observability.TeamObservabilityQueryService.ModelUsageRoleSubtotal;
import io.crewscope.application.observability.TeamObservabilityQueryService.TeamCostMonthsPage;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * M10-F03 transport contract: no-store caching, the frozen response shapes (amounts as
 * plain strings, null rates for empty denominators, PRICED/UNPRICED), and the one-field
 * 400 / 404 / 403 error mapping on top of the shared handler.
 */
class TeamObservabilityControllerTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID TEAM = UUID.randomUUID();

    private TeamObservabilityQueryService service;
    private TeamRequestIdentityResolver identityResolver;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(TeamObservabilityQueryService.class);
        Principal actor = mock(Principal.class);
        identityResolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(new TeamObservabilityController(
                        service, identityResolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void costMonthsServesTheFrozenShapeWithNoStore() {
        Map<CostSource, ModelUsageRoleSubtotal> roles = new LinkedHashMap<>();
        roles.put(CostSource.EXECUTION, new ModelUsageRoleSubtotal(100, 40, 50, 4));
        Map<String, ModelUsageCurrencySubtotal> currencies = new LinkedHashMap<>();
        currencies.put("USD", new ModelUsageCurrencySubtotal(
                new BigDecimal("0.5000"), new BigDecimal("0.056"), null));
        when(service.costMonths(any(), any(), any(), any(), eq(12))).thenReturn(
                new TeamCostMonthsPage(
                        List.of(new ModelUsageMonthSummary(
                                "2026-10", roles, currencies, 40, 1, 5)),
                        Optional.of("2026-09")));

        client.get().uri("/api/v1/organizations/{org}/teams/{team}/observability/cost/months",
                        ORGANIZATION, TEAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.months[0].month").isEqualTo("2026-10")
                .jsonPath("$.months[0].roles.EXECUTION.inputTokens").isEqualTo(100)
                .jsonPath("$.months[0].roles.EMBEDDING").doesNotExist()
                .jsonPath("$.months[0].currencies[0].currency").isEqualTo("USD")
                .jsonPath("$.months[0].currencies[0].inputCost").isEqualTo("0.5000")
                .jsonPath("$.months[0].currencies[0].cachedInputCost").value(value -> assertNull(value))
                .jsonPath("$.months[0].unpricedTokens").isEqualTo(40)
                .jsonPath("$.months[0].totalFactCount").isEqualTo(5)
                .jsonPath("$.nextAfter").isEqualTo("2026-09");
    }

    @Test
    void costMonthRowsCarryPricedAndUnpricedStatus() {
        when(service.requireRequestableMonth("2026-10")).thenReturn("2026-10");
        when(service.costMonth(any(), any(), any(), eq("2026-10"))).thenReturn(List.of(
                new ModelUsageMonthDetailRow(
                        "CHAT_PRIMARY", "deepseek", "deepseek-flash", "USD",
                        11L, 3L, 1, 100, 40, 50,
                        new BigDecimal("0.44"), new BigDecimal("0.05"), new BigDecimal("0.007"),
                        3, 0),
                new ModelUsageMonthDetailRow(
                        "EMBEDDING", "dashscope", "text-embedding-v4", "XXX",
                        null, null, 1, 200, 0, 0, null, null, null, 2, 1)));

        client.get().uri("/api/v1/organizations/{org}/teams/{team}/observability"
                        + "/cost/months/{month}", ORGANIZATION, TEAM, "2026-10")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.month").isEqualTo("2026-10")
                .jsonPath("$.rows[0].costStatus").isEqualTo("PRICED")
                .jsonPath("$.rows[0].inputCost").isEqualTo("0.44")
                .jsonPath("$.rows[0].priceRevision").isEqualTo(3)
                .jsonPath("$.rows[1].costStatus").isEqualTo("UNPRICED")
                .jsonPath("$.rows[1].inputCost").value(value -> assertNull(value))
                .jsonPath("$.rows[1].unreportedFactCount").isEqualTo(1)
                .jsonPath("$.rows[1].catalogRevision").value(value -> assertNull(value));
    }

    @Test
    void qualityMonthNullsRatesWhenDenominatorsAreEmpty() {
        when(service.requireRequestableMonth("2026-01")).thenReturn("2026-01");
        when(service.qualityMonth(any(), any(), any(), eq("2026-01"))).thenReturn(
                new TaskQualityStatistics(0, 0, 0, 0, 0, 0));

        client.get().uri("/api/v1/organizations/{org}/teams/{team}/observability"
                        + "/quality/months/{month}", ORGANIZATION, TEAM, "2026-01")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.month").isEqualTo("2026-01")
                .jsonPath("$.executionAttempts.total").isEqualTo(0)
                .jsonPath("$.executionAttempts.successRate").value(value -> assertNull(value))
                .jsonPath("$.reviewFirstPass.enteredReview").isEqualTo(0)
                .jsonPath("$.reviewFirstPass.firstPassRate").value(value -> assertNull(value));
    }

    @Test
    void qualityMonthScalesRatesToFourDecimals() {
        when(service.requireRequestableMonth("2026-10")).thenReturn("2026-10");
        when(service.qualityMonth(any(), any(), any(), eq("2026-10"))).thenReturn(
                new TaskQualityStatistics(3, 2, 1, 0, 3, 1));

        client.get().uri("/api/v1/organizations/{org}/teams/{team}/observability"
                        + "/quality/months/{month}", ORGANIZATION, TEAM, "2026-10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.executionAttempts.completed").isEqualTo(2)
                .jsonPath("$.executionAttempts.successRate").isEqualTo(0.6667)
                .jsonPath("$.reviewFirstPass.firstPassRate").isEqualTo(0.3333);
    }

    @Test
    void invalidMonthsCursorsAndLimitsAreOneField400s() {
        when(service.requireRequestableMonth("2026-13"))
                .thenThrow(new IllegalArgumentException("malformed"));

        client.get().uri("/api/v1/organizations/{org}/teams/{team}/observability"
                        + "/cost/months/{month}", ORGANIZATION, TEAM, "2026-13")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.details.field").isEqualTo("month");

        client.get().uri("/api/v1/organizations/{org}/teams/{team}/observability"
                        + "/cost/months?after=not-a-month", ORGANIZATION, TEAM)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.details.field").isEqualTo("after");

        client.get().uri("/api/v1/organizations/{org}/teams/{team}/observability"
                        + "/cost/months?limit=0", ORGANIZATION, TEAM)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.details.field").isEqualTo("limit");

        client.get().uri("/api/v1/organizations/{org}/teams/{team}/observability"
                        + "/cost/months?limit=25", ORGANIZATION, TEAM)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.details.field").isEqualTo("limit");
    }

    @Test
    void futureMonthIsRejectedBeforeTheServiceRuns() {
        when(service.requireRequestableMonth("2026-12"))
                .thenThrow(new IllegalArgumentException("future"));

        client.get().uri("/api/v1/organizations/{org}/teams/{team}/observability"
                        + "/quality/months/{month}", ORGANIZATION, TEAM, "2026-12")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.details.field").isEqualTo("month");
    }

    @Test
    void missingTeamKeepsOneNotFoundShapeAndForeignMemberIsDenied() {
        when(service.requireRequestableMonth("2026-10")).thenReturn("2026-10");
        when(service.costMonth(any(), any(), any(), eq("2026-10")))
                .thenThrow(new AggregateNotFoundException("Team",
                        new TeamId(TEAM)));

        client.get().uri("/api/v1/organizations/{org}/teams/{team}/observability"
                        + "/cost/months/{month}", ORGANIZATION, TEAM, "2026-10")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("aggregate_not_found");

        when(service.costMonth(any(), any(), any(), eq("2026-10")))
                .thenThrow(new PolicyDeniedException("access this Team's observability"));

        client.get().uri("/api/v1/organizations/{org}/teams/{team}/observability"
                        + "/cost/months/{month}", ORGANIZATION, TEAM, "2026-10")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("policy_denied");
    }

    @Test
    void monthsEndpointPassesOrganizationAndTeamCoordinates() {
        when(service.costMonths(any(), eq(new OrganizationId(ORGANIZATION)),
                eq(new TeamId(TEAM)), eq(Optional.empty()), eq(12)))
                .thenReturn(new TeamCostMonthsPage(List.of(), Optional.empty()));

        client.get().uri("/api/v1/organizations/{org}/teams/{team}/observability/cost/months",
                        ORGANIZATION, TEAM)
                .exchange()
                .expectStatus().isOk();

        verify(service).costMonths(
                any(), eq(new OrganizationId(ORGANIZATION)), eq(new TeamId(TEAM)),
                eq(Optional.empty()), eq(12));
    }
}
