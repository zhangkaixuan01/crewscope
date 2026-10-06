package io.crewscope.server.api;

import io.crewscope.application.observability.ModelUsageMonthDetailRow;
import io.crewscope.application.observability.TaskQualityStatistics;
import io.crewscope.application.observability.TeamObservabilityQueryService;
import io.crewscope.application.observability.TeamObservabilityQueryService.CostSource;
import io.crewscope.application.observability.TeamObservabilityQueryService.ModelUsageCurrencySubtotal;
import io.crewscope.application.observability.TeamObservabilityQueryService.ModelUsageMonthSummary;
import io.crewscope.application.observability.TeamObservabilityQueryService.ModelUsageRoleSubtotal;
import io.crewscope.application.observability.TeamObservabilityQueryService.TeamCostMonthsPage;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Team usage-cost and quality reads (M10-F03). Reads are member-scoped with the platform
 * administrator bypass inside the service, so this controller only owns transport concerns:
 * month and page validation, no-store caching, and the API DTO shapes frozen in the
 * observability contract. Amounts serialize per currency via toPlainString — the contract
 * never converts or rounds across currencies.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/observability")
public final class TeamObservabilityController {

    private static final int DEFAULT_MONTH_LIMIT = 12;
    private static final int MAX_MONTH_LIMIT = 24;
    private static final int RATE_SCALE = 4;

    private final TeamObservabilityQueryService service;
    private final TeamRequestIdentityResolver identityResolver;

    public TeamObservabilityController(
            TeamObservabilityQueryService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = Objects.requireNonNull(service, "service");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
    }

    @GetMapping("/cost/months")
    public Mono<ResponseEntity<CostMonthsResponse>> costMonths(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        if (after != null) {
            try {
                YearMonth.parse(after);
            } catch (RuntimeException failure) {
                throw invalidField("after");
            }
        }
        int monthLimit = monthLimit(limit);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.costMonths(
                                access, organization, team,
                                Optional.ofNullable(after), monthLimit))
                .map(page -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(CostMonthsResponse.from(page)));
    }

    @GetMapping("/cost/months/{month}")
    public Mono<ResponseEntity<CostMonthDetailResponse>> costMonth(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String month,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        String usageMonth = month(month);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.costMonth(access, organization, team, usageMonth))
                .map(rows -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(CostMonthDetailResponse.from(usageMonth, rows)));
    }

    @GetMapping("/quality/months/{month}")
    public Mono<ResponseEntity<QualityMonthResponse>> qualityMonth(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String month,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        String usageMonth = month(month);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.qualityMonth(
                                access, organization, team, usageMonth))
                .map(statistics -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(QualityMonthResponse.from(usageMonth, statistics)));
    }

    // ------------------------------------------------------------------ helpers

    private <T> Mono<T> query(
            Authentication authentication,
            OrganizationId organization,
            ServerWebExchange exchange,
            Function<
                    TeamAccessContext, T> action) {
        return identityResolver
                .resolve(authentication, organization, ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> Mono.fromCallable(() -> action.apply(access))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Validates format and rejects future months through the service (which owns the
     * reporting zone and authoritative clock); both failures map to one 400 field error.
     */
    private String month(String value) {
        try {
            return service.requireRequestableMonth(value);
        } catch (RuntimeException failure) {
            throw invalidField("month");
        }
    }

    private static int monthLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_MONTH_LIMIT;
        }
        if (limit < 1 || limit > MAX_MONTH_LIMIT) {
            throw invalidField("limit");
        }
        return limit;
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("organizationId");
        }
    }

    private static TeamId teamId(String value) {
        try {
            return TeamId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("teamId");
        }
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid observability field",
                Map.of("field", field));
    }

    // ------------------------------------------------------------------ DTOs

    record CostMonthsResponse(List<CostMonthResponse> months, String nextAfter) {

        static CostMonthsResponse from(TeamCostMonthsPage page) {
            return new CostMonthsResponse(
                    page.months().stream().map(CostMonthResponse::from).toList(),
                    page.nextAfter().orElse(null));
        }
    }

    record CostMonthResponse(
            String month,
            Map<String, RoleSubtotalResponse> roles,
            List<CurrencyAmountResponse> currencies,
            long unpricedTokens,
            long totalFactCount) {

        static CostMonthResponse from(ModelUsageMonthSummary summary) {
            Map<String, RoleSubtotalResponse> roles = new LinkedHashMap<>();
            summary.roles().forEach((source, subtotal) ->
                    roles.put(source.name(), RoleSubtotalResponse.from(subtotal)));
            List<CurrencyAmountResponse> currencies = new ArrayList<>();
            summary.currencies().forEach((currency, amounts) -> currencies.add(
                    new CurrencyAmountResponse(
                            currency,
                            amount(amounts.inputCost()),
                            amount(amounts.outputCost()),
                            amount(amounts.cachedInputCost()))));
            return new CostMonthResponse(
                    summary.usageMonth(), roles, currencies,
                    summary.unpricedTokens(), summary.totalFactCount());
        }
    }

    record RoleSubtotalResponse(
            long inputTokens, long outputTokens, long cachedTokens, long factCount) {

        static RoleSubtotalResponse from(ModelUsageRoleSubtotal subtotal) {
            return new RoleSubtotalResponse(
                    subtotal.inputTokens(), subtotal.outputTokens(),
                    subtotal.cachedTokens(), subtotal.factCount());
        }
    }

    record CurrencyAmountResponse(
            String currency, String inputCost, String outputCost, String cachedInputCost) {
    }

    record CostMonthDetailResponse(String month, List<CostModelRowResponse> rows) {

        static CostMonthDetailResponse from(String month, List<ModelUsageMonthDetailRow> rows) {
            return new CostMonthDetailResponse(
                    month, rows.stream().map(CostModelRowResponse::from).toList());
        }
    }

    record CostModelRowResponse(
            String role,
            String providerKey,
            String modelId,
            String currencyCode,
            Long catalogRevision,
            Long priceRevision,
            int attempt,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            String inputCost,
            String outputCost,
            String cachedInputCost,
            long factCount,
            long unreportedFactCount,
            String costStatus) {

        static CostModelRowResponse from(ModelUsageMonthDetailRow row) {
            return new CostModelRowResponse(
                    row.role(),
                    row.providerKey(),
                    row.modelId(),
                    row.currencyCode(),
                    row.catalogRevision(),
                    row.priceRevision(),
                    row.attempt(),
                    row.inputTokens(),
                    row.outputTokens(),
                    row.cachedInputTokens(),
                    amount(row.inputCost()),
                    amount(row.outputCost()),
                    amount(row.cachedInputCost()),
                    row.factCount(),
                    row.unreportedFactCount(),
                    row.priced() ? "PRICED" : "UNPRICED");
        }
    }

    record QualityMonthResponse(
            String month,
            ExecutionAttemptsResponse executionAttempts,
            ReviewFirstPassResponse reviewFirstPass) {

        static QualityMonthResponse from(String month, TaskQualityStatistics statistics) {
            return new QualityMonthResponse(
                    month,
                    new ExecutionAttemptsResponse(
                            statistics.executionAttempts(),
                            statistics.completedAttempts(),
                            statistics.failedAttempts(),
                            statistics.cancelledAttempts(),
                            rate(statistics.completedAttempts(), statistics.executionAttempts())),
                    new ReviewFirstPassResponse(
                            statistics.enteredReview(),
                            statistics.firstPassApproved(),
                            rate(statistics.firstPassApproved(), statistics.enteredReview())));
        }
    }

    record ExecutionAttemptsResponse(
            long total,
            long completed,
            long failed,
            long cancelled,
            BigDecimal successRate) {
    }

    record ReviewFirstPassResponse(
            long enteredReview, long firstPassApproved, BigDecimal firstPassRate) {
    }

    /** Amounts serialize exactly as stored — toPlainString, never scientific notation. */
    private static String amount(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** Null (not zero) when the denominator is empty: no samples is not a zero rate. */
    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }
}
