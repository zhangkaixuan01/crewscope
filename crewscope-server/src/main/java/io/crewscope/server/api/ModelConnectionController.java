package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.model.CreateModelConnectionRequest;
import io.crewscope.application.model.ModelConnectionApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwnerType;
import io.crewscope.domain.model.ModelConnectionRevocationReason;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Owner-scoped HTTP boundary for model connection and one-way API-key lifecycle commands. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/model-connections")
public final class ModelConnectionController {

    private final ModelConnectionApplicationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public ModelConnectionController(
            ModelConnectionApplicationService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @PostMapping
    public Mono<ResponseEntity<CommandReceiptResponse>> create(
            @PathVariable String organizationId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CreateConnectionRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        CreateModelConnectionRequest command = createRequest(request);
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> service.create(context, command, CredentialSecret.utf8(request.apiKey())));
    }

    @GetMapping
    public Mono<ResponseEntity<ConnectionListResponse>> list(
            @PathVariable String organizationId,
            @RequestParam String ownerType,
            @RequestParam(required = false) String teamId,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ModelConnectionOwnerType owner = ownerType(ownerType);
        Optional<TeamId> team = optionalTeamId(teamId);
        int pageSize = ApiPagination.limit(limit);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.listConnections(
                                access, organization, owner, team, offset, pageSize))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(ConnectionListResponse.from(values)));
    }

    @GetMapping("/{connectionId}")
    public Mono<ResponseEntity<ConnectionResponse>> get(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ModelConnectionId connection = connectionId(connectionId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.getConnection(access, organization, connection))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(Long.toString(value.version()))
                        .body(ConnectionResponse.from(value)));
    }

    @PostMapping("/{connectionId}/verify")
    public Mono<ResponseEntity<CommandReceiptResponse>> verify(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CredentialVersionRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        return mutate(
                organizationId,
                connectionId,
                key,
                ifMatch,
                authentication,
                exchange,
                context -> service.verify(
                        context,
                        connectionId(connectionId),
                        ApiHeaders.requireIfMatch(ifMatch),
                        credentialVersion(request.credentialVersion())));
    }

    @PostMapping("/{connectionId}/rotate")
    public Mono<ResponseEntity<CommandReceiptResponse>> rotate(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RotateCredentialRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        return mutate(
                organizationId,
                connectionId,
                key,
                ifMatch,
                authentication,
                exchange,
                context -> service.rotate(
                        context,
                        connectionId(connectionId),
                        ApiHeaders.requireIfMatch(ifMatch),
                        credentialVersion(request.credentialVersion()),
                        CredentialSecret.utf8(request.apiKey())));
    }

    @PostMapping("/{connectionId}/suspend")
    public Mono<ResponseEntity<CommandReceiptResponse>> suspend(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CredentialVersionRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        return mutate(
                organizationId,
                connectionId,
                key,
                ifMatch,
                authentication,
                exchange,
                context -> service.suspend(
                        context,
                        connectionId(connectionId),
                        ApiHeaders.requireIfMatch(ifMatch),
                        credentialVersion(request.credentialVersion())));
    }

    @PostMapping("/{connectionId}/revoke")
    public Mono<ResponseEntity<CommandReceiptResponse>> revoke(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RevokeConnectionRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        return mutate(
                organizationId,
                connectionId,
                key,
                ifMatch,
                authentication,
                exchange,
                context -> service.revoke(
                        context,
                        connectionId(connectionId),
                        ApiHeaders.requireIfMatch(ifMatch),
                        credentialVersion(request.credentialVersion()),
                        revocationReason(request.reason())));
    }

    private Mono<ResponseEntity<CommandReceiptResponse>> mutate(
            String organizationValue,
            String connectionValue,
            String key,
            String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange,
            Function<TeamCommandContext, CommandExecution<ModelConnection>> action) {
        OrganizationId organization = organizationId(organizationValue);
        connectionId(connectionValue);
        ApiHeaders.requireIfMatch(ifMatch);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        return command(authentication, organization, idempotencyKey, exchange, action);
    }

    private <T> Mono<ResponseEntity<CommandReceiptResponse>> command(
            Authentication authentication,
            OrganizationId organizationId,
            IdempotencyKey idempotencyKey,
            ServerWebExchange exchange,
            Function<TeamCommandContext, CommandExecution<T>> action) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, organizationId, correlationId)
                .flatMap(access -> blocking(() -> action.apply(new TeamCommandContext(
                        access, idempotencyKey, correlationId, Optional.empty()))))
                .map(CommandReceiptResponse::accepted);
    }

    private <T> Mono<T> query(
            Authentication authentication,
            OrganizationId organizationId,
            ServerWebExchange exchange,
            Function<TeamAccessContext, T> action) {
        return identityResolver
                .resolve(authentication, organizationId, ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> action.apply(access)));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static CreateModelConnectionRequest createRequest(CreateConnectionRequest request) {
        try {
            return new CreateModelConnectionRequest(
                    new ModelProviderKey(request.providerKey()),
                    ownerType(request.ownerType()),
                    optionalTeamId(request.teamId()),
                    new ModelRegion(request.region()),
                    Optional.ofNullable(request.credentialExpiresAt()).map(UtcTimestamp::parse));
        } catch (RuntimeException failure) {
            throw invalidField("connection");
        }
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("organizationId");
        }
    }

    private static ModelConnectionId connectionId(String value) {
        try {
            return ModelConnectionId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("connectionId");
        }
    }

    private static Optional<TeamId> optionalTeamId(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(TeamId.from(value));
        } catch (RuntimeException failure) {
            throw invalidField("teamId");
        }
    }

    private static ModelConnectionOwnerType ownerType(String value) {
        try {
            return ModelConnectionOwnerType.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalidField("ownerType");
        }
    }

    private static ModelCredentialVersion credentialVersion(long value) {
        try {
            return new ModelCredentialVersion(value);
        } catch (RuntimeException failure) {
            throw invalidField("credentialVersion");
        }
    }

    private static ModelConnectionRevocationReason revocationReason(String value) {
        try {
            return ModelConnectionRevocationReason.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalidField("reason");
        }
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid Model Connection field",
                Map.of("field", field));
    }

    public record CreateConnectionRequest(
            @NotBlank String providerKey,
            @NotBlank String ownerType,
            String teamId,
            @NotBlank String region,
            @NotBlank @Size(max = 1_048_576) String apiKey,
            String credentialExpiresAt) {}

    public record CredentialVersionRequest(@Min(0) long credentialVersion) {}

    public record RotateCredentialRequest(
            @Min(0) long credentialVersion,
            @NotBlank @Size(max = 1_048_576) String apiKey) {}

    public record RevokeConnectionRequest(
            @Min(0) long credentialVersion, @NotBlank String reason) {}

    public record ConnectionListResponse(List<ConnectionResponse> items) {
        static ConnectionListResponse from(List<ModelConnection> values) {
            return new ConnectionListResponse(values.stream()
                    .map(ConnectionResponse::from)
                    .toList());
        }
    }

    /** Credential identifiers, endpoint paths, metadata and plaintext never cross this DTO. */
    public record ConnectionResponse(
            String id,
            String organizationId,
            String providerKey,
            String ownerType,
            String ownerId,
            String region,
            String billingSubjectType,
            String billingSubjectId,
            long credentialVersion,
            String status,
            String healthStatus,
            String healthFailureCode,
            String checkedAt,
            String lastHealthyAt,
            int consecutiveFailures,
            String revocationReason,
            String createdAt,
            String updatedAt,
            long version) {
        static ConnectionResponse from(ModelConnection connection) {
            return new ConnectionResponse(
                    connection.id().toString(),
                    connection.organizationId().toString(),
                    connection.providerKey().toString(),
                    connection.owner().type().name(),
                    connection.owner().ownerId().toString(),
                    connection.region().toString(),
                    connection.billingSubject().type().name(),
                    connection.billingSubject().subjectId().toString(),
                    connection.credentialBinding().credentialVersion().value(),
                    connection.status().name(),
                    connection.health().status().name(),
                    connection.health().failureCode().map(Enum::name).orElse(null),
                    connection.health().checkedAt().map(Object::toString).orElse(null),
                    connection.health().lastHealthyAt().map(Object::toString).orElse(null),
                    connection.health().consecutiveFailures(),
                    connection.revocationReason().map(Enum::name).orElse(null),
                    connection.audit().createdAt().toString(),
                    connection.audit().updatedAt().toString(),
                    connection.version());
        }
    }
}
