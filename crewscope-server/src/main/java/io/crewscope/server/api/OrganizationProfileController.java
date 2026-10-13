package io.crewscope.server.api;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.organization.OrganizationCommandContext;
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationRepository;
import io.crewscope.application.organization.OrganizationSettingApplicationService;
import io.crewscope.domain.organization.Organization;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HTTP boundary for the organization profile and settings (M12-A01). First controllers mounted at
 * the organization root — name/status stay seeder-written read-only facts (ADR-035 L22), while
 * settings live in the V68 typed row behind optimistic versioning.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
public final class OrganizationProfileController {

    private final OrganizationRepository organizations;
    private final OrganizationSettingApplicationService settings;
    private final OrganizationRequestIdentityResolver identityResolver;

    public OrganizationProfileController(
            OrganizationRepository organizations,
            OrganizationSettingApplicationService settings,
            OrganizationRequestIdentityResolver identityResolver) {
        this.organizations = organizations;
        this.settings = settings;
        this.identityResolver = identityResolver;
    }

    /** Read-only organization identity; the single source F01 consumes for the display name. */
    @GetMapping
    public Mono<ResponseEntity<OrganizationResponse>> getOrganization(
            @PathVariable String organizationId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> {
                            Organization found = organizations
                                    .findById(organization)
                                    .orElseThrow(() -> new AggregateNotFoundException(
                                            "ORGANIZATION", organization));
                            return new OrganizationResponse(
                                    found.id().toString(),
                                    found.name(),
                                    found.status().name(),
                                    found.version());
                        })
                .map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body));
    }

    @GetMapping("/settings")
    public Mono<ResponseEntity<OrganizationSettingResponse>> getSettings(
            @PathVariable String organizationId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> settings.findSettings(access, organization))
                .map(
                        view -> ResponseEntity.ok()
                                .cacheControl(CacheControl.noStore())
                                .eTag(ApiHeaders.versionEtag(view.version()))
                                .body(OrganizationSettingResponse.from(view)));
    }

    /** Creates or changes the settings row; audit retention null means forever (the default). */
    @PutMapping("/settings")
    public Mono<ResponseEntity<CommandReceiptResponse>> changeSettings(
            @PathVariable String organizationId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ChangeOrganizationSettingsRequest request,
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
                                                settings.changeSettings(
                                                        new OrganizationCommandContext(
                                                                access,
                                                                idempotencyKey,
                                                                correlationId,
                                                                Optional.empty()),
                                                        organization,
                                                        expectedVersion,
                                                        request.auditEventRetentionDays())))
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

    /** {@code auditEventRetentionDays} null (or absent) sets retention back to forever. */
    public record ChangeOrganizationSettingsRequest(Integer auditEventRetentionDays) {}

    public record OrganizationResponse(
            String organizationId, String name, String status, long version) {}

    public record OrganizationSettingResponse(Integer auditEventRetentionDays, long version) {

        static OrganizationSettingResponse from(
                OrganizationSettingApplicationService.SettingView view) {
            return new OrganizationSettingResponse(view.auditEventRetentionDays(), view.version());
        }
    }
}
