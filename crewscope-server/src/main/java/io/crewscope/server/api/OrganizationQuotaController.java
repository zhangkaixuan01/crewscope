package io.crewscope.server.api;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationCommandContext;
import io.crewscope.application.quota.OrganizationQuotaApplicationService;
import io.crewscope.application.quota.OrganizationQuotaUsageQueryService;
import io.crewscope.domain.shared.id.OrganizationId;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HTTP boundary for the organization quota configuration (M12-A02). Either dimension null
 * means unconfigured; the whole row absent is the default state (version 0, admission
 * untouched) until the first write materializes it.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
public final class OrganizationQuotaController {

    private final OrganizationQuotaApplicationService quotas;
    private final OrganizationQuotaUsageQueryService usage;
    private final OrganizationRequestIdentityResolver identityResolver;

    public OrganizationQuotaController(
            OrganizationQuotaApplicationService quotas,
            OrganizationQuotaUsageQueryService usage,
            OrganizationRequestIdentityResolver identityResolver) {
        this.quotas = quotas;
        this.usage = usage;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/quota")
    public Mono<ResponseEntity<OrganizationQuotaResponse>> getQuota(
            @PathVariable String organizationId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> quotas.findQuota(access, organization))
                .map(
                        view -> ResponseEntity.ok()
                                .cacheControl(CacheControl.noStore())
                                .eTag(ApiHeaders.versionEtag(view.version()))
                                .body(OrganizationQuotaResponse.from(view)));
    }

    /**
     * One reporting month's limits beside their consumed facts; {@code month} null resolves to
     * the current reporting month. Reserved tokens are admission-time upper bounds, actual
     * tokens fold the org+month rollup — no per-task actual exists to reconcile them.
     */
    @GetMapping("/quota/usage")
    public Mono<ResponseEntity<OrganizationQuotaUsageQueryService.QuotaUsageView>> getQuotaUsage(
            @PathVariable String organizationId,
            @RequestParam(name = "month", required = false) String month,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        String requestableMonth = month == null ? null : requireMonth(month);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> usage.findUsage(access, organization, requestableMonth))
                .map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body));
    }

    /** Creates or changes the quota row; null dimensions clear them back to unconfigured. */
    @PutMapping("/quota")
    public Mono<ResponseEntity<CommandReceiptResponse>> changeQuota(
            @PathVariable String organizationId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ChangeOrganizationQuotaRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, organization, correlationId)
                .flatMap(
                        access ->
                                blocking(
                                        () ->
                                                quotas.changeQuota(
                                                        new OrganizationCommandContext(
                                                                access,
                                                                idempotencyKey,
                                                                correlationId,
                                                                Optional.empty()),
                                                        organization,
                                                        expectedVersion,
                                                        request.monthlyTaskIntakeLimit(),
                                                        request.monthlyTokenBudget())))
                .map(CommandReceiptResponse::accepted);
    }

    private <T> Mono<T> query(
            Authentication authentication,
            OrganizationId organizationId,
            ServerWebExchange exchange,
            Function<OrganizationAccessContext, T> action) {
        return identityResolver
                .resolve(authentication, organizationId, ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> action.apply(access)));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("organizationId");
        }
    }

    private static ApiRequestException invalidIdentifier(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", field));
    }

    /**
     * Validates format and rejects future months through the service (which owns the
     * reporting zone and authoritative clock); both failures map to one 400 field error.
     */
    private String requireMonth(String value) {
        try {
            return usage.requireRequestableMonth(value);
        } catch (RuntimeException failure) {
            throw new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid usage month",
                    Map.of("field", "month"));
        }
    }

    /** Either value null (or absent) clears that dimension back to unconfigured. */
    public record ChangeOrganizationQuotaRequest(
            Integer monthlyTaskIntakeLimit, Long monthlyTokenBudget) {}

    public record OrganizationQuotaResponse(
            Integer monthlyTaskIntakeLimit, Long monthlyTokenBudget, long version) {

        static OrganizationQuotaResponse from(
                OrganizationQuotaApplicationService.QuotaConfigurationView view) {
            return new OrganizationQuotaResponse(
                    view.monthlyTaskIntakeLimit(), view.monthlyTokenBudget(), view.version());
        }
    }
}
