package io.crewscope.server.api;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.organization.OrganizationCommandContext;
import io.crewscope.application.organization.OrganizationRoleApplicationService;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.organization.role.OrganizationRoleGrantId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HTTP boundary for ORG_* role grants (M12-A01). Commands return accepted receipts only — grants
 * are re-read through GET, which keeps replayed receipts from promising an entity snapshot.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/role-grants")
public final class OrganizationRoleController {

    private static final int REASON_MAX_LENGTH = 500;

    private final OrganizationRoleApplicationService service;
    private final OrganizationRequestIdentityResolver identityResolver;

    public OrganizationRoleController(
            OrganizationRoleApplicationService service,
            OrganizationRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    /** The role-management audit face: active grants first, revoked history behind. */
    @GetMapping
    public Mono<ResponseEntity<List<RoleGrantResponse>>> listGrants(
            @PathVariable String organizationId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.listGrants(access, organization))
                .map(
                        grants ->
                                ResponseEntity.ok()
                                        .cacheControl(CacheControl.noStore())
                                        .body(
                                                grants.stream()
                                                        .map(RoleGrantResponse::from)
                                                        .toList()));
    }

    @PostMapping
    public Mono<ResponseEntity<CommandReceiptResponse>> grantRole(
            @PathVariable String organizationId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody GrantRoleRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        PrincipalId principal = principalId(request.principalId());
        OrganizationRole role = role(request.role());
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context ->
                        service.grant(context, organization, principal, role, request.reason()));
    }

    @PostMapping("/{grantId}/revoke")
    public Mono<ResponseEntity<CommandReceiptResponse>> revokeRole(
            @PathVariable String organizationId,
            @PathVariable String grantId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RevokeRoleRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        OrganizationRoleGrantId grant = grantId(grantId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context ->
                        service.revoke(
                                context, organization, grant, expectedVersion, request.reason()));
    }

    private <T> Mono<ResponseEntity<CommandReceiptResponse>> command(
            Authentication authentication,
            OrganizationId organizationId,
            IdempotencyKey idempotencyKey,
            ServerWebExchange exchange,
            Function<OrganizationCommandContext, io.crewscope.application.command.CommandExecution<T>>
                    action) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, organizationId, correlationId)
                .flatMap(
                        access ->
                                blocking(
                                        () ->
                                                action.apply(
                                                        new OrganizationCommandContext(
                                                                access,
                                                                idempotencyKey,
                                                                correlationId,
                                                                Optional.empty()))))
                .map(CommandReceiptResponse::accepted);
    }

    private <T> Mono<T> query(
            Authentication authentication,
            OrganizationId organizationId,
            ServerWebExchange exchange,
            Function<io.crewscope.application.organization.OrganizationAccessContext, T> action) {
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

    private static PrincipalId principalId(String value) {
        try {
            return PrincipalId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("principalId");
        }
    }

    private static OrganizationRoleGrantId grantId(String value) {
        try {
            return OrganizationRoleGrantId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("grantId");
        }
    }

    private static OrganizationRole role(String value) {
        try {
            return OrganizationRole.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an unknown organization role",
                    Map.of("field", "role"));
        }
    }

    private static ApiRequestException invalidIdentifier(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", field));
    }

    public record GrantRoleRequest(
            @NotBlank String principalId,
            @NotBlank String role,
            @Size(max = REASON_MAX_LENGTH) String reason) {}

    public record RevokeRoleRequest(@Size(max = REASON_MAX_LENGTH) String reason) {}

    public record RoleGrantResponse(
            String id,
            String organizationId,
            String principalId,
            String role,
            String status,
            String grantedByPrincipalId,
            String grantedAt,
            String revokedAt,
            String revokedByPrincipalId,
            String reason,
            long version) {

        static RoleGrantResponse from(OrganizationRoleGrant grant) {
            return new RoleGrantResponse(
                    grant.id().toString(),
                    grant.organizationId().toString(),
                    grant.principalId().toString(),
                    grant.role().name(),
                    grant.status().name(),
                    grant.grantedBy().toString(),
                    grant.grantedAt().toString(),
                    grant.revokedAt().map(Object::toString).orElse(null),
                    grant.revokedBy().map(Object::toString).orElse(null),
                    grant.reason().orElse(null),
                    grant.version());
        }
    }
}
