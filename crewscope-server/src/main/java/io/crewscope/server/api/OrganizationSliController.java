package io.crewscope.server.api;

import io.crewscope.application.observability.sli.OrganizationSliQueryService;
import io.crewscope.application.observability.sli.OrganizationSliView;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HTTP boundary for the M12-I03 SLI surface: the six ADR-037 §5 indicators with their
 * windows, availability, and error budgets. Read-only, no store, AUDIT_READ_ORG gated
 * (so ORG_AUDITOR reaches this but not the settings-flavoured quota surfaces).
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
public final class OrganizationSliController {

    private final OrganizationSliQueryService sli;
    private final OrganizationRequestIdentityResolver identityResolver;

    public OrganizationSliController(
            OrganizationSliQueryService sli, OrganizationRequestIdentityResolver identityResolver) {
        this.sli = sli;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/sli")
    public Mono<ResponseEntity<OrganizationSliView>> getSli(
            @PathVariable String organizationId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        return identityResolver
                .resolve(authentication, organization, ApiCorrelationIds.resolve(exchange))
                .flatMap(
                        access -> blocking(() -> sli.findSli(access, organization)))
                .map(view -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier();
        }
    }

    private static ApiRequestException invalidIdentifier() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", "organizationId"));
    }
}
