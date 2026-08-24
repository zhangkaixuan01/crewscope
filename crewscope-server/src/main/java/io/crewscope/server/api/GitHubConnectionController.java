package io.crewscope.server.api;

import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialSubjectType;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.github.CreateGitHubConnectionRequest;
import io.crewscope.application.github.GitHubAuthenticationType;
import io.crewscope.application.github.GitHubAuthorizationHealthView;
import io.crewscope.application.github.GitHubConnectionApplicationService;
import io.crewscope.application.github.GitHubConnectionView;
import io.crewscope.application.github.GitHubRemotePreflightView;
import io.crewscope.application.github.GitHubProviderBindingView;
import io.crewscope.application.github.GitHubRepositoryView;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

/** GitHub Connection and Repository Catalog HTTP boundary with an explicit response whitelist. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/github-connections")
public final class GitHubConnectionController {

    private final GitHubConnectionApplicationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public GitHubConnectionController(
            GitHubConnectionApplicationService service,
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
        CreateGitHubConnectionRequest command = createRequest(request);
        return command(authentication, organization, idempotencyKey, exchange,
                context -> service.create(
                        context, organization, command, CredentialSecret.utf8(request.accessToken())));
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
        ProviderOwnerType owner = ownerType(ownerType);
        Optional<TeamId> team = optionalTeamId(teamId);
        return query(authentication, organization, exchange, access -> service.list(
                        access, organization, owner, team, offset, ApiPagination.limit(limit)))
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
        ConnectionId connection = connectionId(connectionId);
        return query(authentication, organization, exchange,
                        access -> service.get(access, organization, connection))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(Long.toString(value.version()))
                        .body(ConnectionResponse.from(value)));
    }

    @PostMapping("/{connectionId}/verify")
    public Mono<ResponseEntity<ConnectionResponse>> verify(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ConnectionId connection = connectionId(connectionId);
        long version = ApiHeaders.requireIfMatch(ifMatch);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return query(authentication, organization, correlationId,
                        access -> service.verify(
                                access, organization, connection, version, correlationId))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(Long.toString(value.version()))
                        .body(ConnectionResponse.from(value)));
    }

    @PostMapping("/{connectionId}/bindings")
    public Mono<ResponseEntity<CommandReceiptResponse>> bind(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CreateBindingRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ConnectionId connection = connectionId(connectionId);
        TeamId team = requiredTeamId(request.teamId());
        long version = ApiHeaders.requireIfMatch(ifMatch);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        return command(authentication, organization, idempotencyKey, exchange,
                context -> service.bind(
                        context,
                        organization,
                        connection,
                        version,
                        team,
                        request.defaultUsage()));
    }

    @GetMapping("/{connectionId}/bindings")
    public Mono<ResponseEntity<BindingListResponse>> listBindings(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            @RequestParam String teamId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ConnectionId connection = connectionId(connectionId);
        TeamId team = requiredTeamId(teamId);
        return query(authentication, organization, exchange,
                        access -> service.listBindings(access, organization, connection, team))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(BindingListResponse.from(values)));
    }

    @PostMapping("/{connectionId}/repositories/synchronize")
    public Mono<ResponseEntity<RepositoryListResponse>> synchronizeCatalog(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ConnectionId connection = connectionId(connectionId);
        long version = ApiHeaders.requireIfMatch(ifMatch);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return query(authentication, organization, correlationId,
                        access -> service.synchronizeCatalog(
                                access, organization, connection, version, correlationId))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(RepositoryListResponse.from(values)));
    }

    @GetMapping("/{connectionId}/repositories")
    public Mono<ResponseEntity<RepositoryListResponse>> listCatalog(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ConnectionId connection = connectionId(connectionId);
        return query(authentication, organization, exchange,
                        access -> service.listCatalog(access, organization, connection))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(RepositoryListResponse.from(values)));
    }

    @PostMapping("/{connectionId}/repositories/{externalRepositoryId}/preflight")
    public Mono<ResponseEntity<RemotePreflightResponse>> preflightRepository(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            @PathVariable @Size(max = 100) String externalRepositoryId,
            @RequestParam String bindingId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ConnectionId connection = connectionId(connectionId);
        long version = ApiHeaders.requireIfMatch(ifMatch);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return query(authentication, organization, correlationId,
                        access -> service.preflightRepository(
                                access,
                                organization,
                                connection,
                                version,
                                bindingId(bindingId),
                                externalRepositoryId,
                                correlationId))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(RemotePreflightResponse.from(value)));
    }

    @GetMapping("/{connectionId}/health")
    public Mono<ResponseEntity<GitHubAuthorizationHealthView>> health(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ConnectionId connection = connectionId(connectionId);
        return query(authentication, organization, exchange,
                        access -> service.health(access, organization, connection))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(value));
    }

    @PostMapping("/{connectionId}/revoke")
    public Mono<ResponseEntity<CommandReceiptResponse>> revoke(
            @PathVariable String organizationId,
            @PathVariable String connectionId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody RevokeConnectionRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ConnectionId connection = connectionId(connectionId);
        long version = ApiHeaders.requireIfMatch(ifMatch);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        return command(authentication, organization, idempotencyKey, exchange,
                context -> service.revoke(
                        context, organization, connection, version, request.reason()));
    }

    private <T> Mono<ResponseEntity<CommandReceiptResponse>> command(
            Authentication authentication,
            OrganizationId organizationId,
            IdempotencyKey idempotencyKey,
            ServerWebExchange exchange,
            Function<TeamCommandContext, CommandExecution<T>> action) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, organizationId, correlationId)
                .flatMap(access -> blocking(() -> action.apply(new TeamCommandContext(
                        access, idempotencyKey, correlationId, Optional.empty()))))
                .map(CommandReceiptResponse::accepted);
    }

    private <T> Mono<T> query(
            Authentication authentication,
            OrganizationId organizationId,
            ServerWebExchange exchange,
            Function<TeamAccessContext, T> action) {
        return query(
                authentication,
                organizationId,
                ApiCorrelationIds.resolve(exchange),
                action);
    }

    private <T> Mono<T> query(
            Authentication authentication,
            OrganizationId organizationId,
            UUID correlationId,
            Function<TeamAccessContext, T> action) {
        return identityResolver.resolve(authentication, organizationId, correlationId)
                .flatMap(access -> blocking(() -> action.apply(access)));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static CreateGitHubConnectionRequest createRequest(CreateConnectionRequest request) {
        try {
            return new CreateGitHubConnectionRequest(
                    GitHubAuthenticationType.valueOf(request.authenticationType()),
                    optionalTeamId(request.teamId()),
                    CredentialSubjectType.valueOf(request.credentialSubjectType()),
                    request.externalAccountId(),
                    request.repositoryAllowlist(),
                    Optional.ofNullable(request.expiresAt()).map(UtcTimestamp::parse));
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

    private static ConnectionId connectionId(String value) {
        try {
            return ConnectionId.from(value);
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

    private static TeamId requiredTeamId(String value) {
        return optionalTeamId(value).orElseThrow(() -> invalidField("teamId"));
    }

    private static ProviderBindingId bindingId(String value) {
        try {
            return ProviderBindingId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("bindingId");
        }
    }

    private static ProviderOwnerType ownerType(String value) {
        try {
            return ProviderOwnerType.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalidField("ownerType");
        }
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid GitHub Connection field",
                Map.of("field", field));
    }

    public record CreateConnectionRequest(
            @NotBlank String authenticationType,
            String teamId,
            @NotBlank String credentialSubjectType,
            @NotBlank @Size(max = 100) String externalAccountId,
            @NotEmpty @Size(max = 500) Set<@NotBlank @Size(max = 511) String> repositoryAllowlist,
            @NotBlank @Size(max = 1_048_576) String accessToken,
            String expiresAt) {}

    public record RevokeConnectionRequest(@NotBlank @Size(max = 500) String reason) {}

    public record CreateBindingRequest(
            @NotBlank String teamId, boolean defaultUsage) {}

    public record ConnectionListResponse(List<ConnectionResponse> items) {
        static ConnectionListResponse from(List<GitHubConnectionView> values) {
            return new ConnectionListResponse(values.stream().map(ConnectionResponse::from).toList());
        }
    }

    /** Credential ID, token, external numeric account ID and internal Grant never cross this DTO. */
    public record ConnectionResponse(
            String id,
            String ownerType,
            String teamId,
            String authenticationType,
            String executionIdentity,
            String externalAccountLogin,
            String status,
            long version,
            List<String> repositoryAllowlist,
            String credentialStatus,
            String expiresAt,
            String verifiedAt,
            String createdAt,
            String updatedAt) {

        static ConnectionResponse from(GitHubConnectionView value) {
            return new ConnectionResponse(
                    value.connectionId(),
                    value.ownerType().name(),
                    value.teamId().map(Object::toString).orElse(null),
                    value.authenticationType().name(),
                    value.executionIdentity().map(Enum::name).orElse(null),
                    value.externalAccountLogin().orElse(null),
                    value.status().name(),
                    value.version(),
                    value.repositoryAllowlist(),
                    value.credentialStatus().map(Enum::name).orElse(null),
                    value.expiresAt().map(Object::toString).orElse(null),
                    value.verifiedAt().map(Object::toString).orElse(null),
                    value.createdAt().toString(),
                    value.updatedAt().toString());
        }
    }

    public record RepositoryListResponse(List<RepositoryResponse> items) {
        static RepositoryListResponse from(List<GitHubRepositoryView> values) {
            return new RepositoryListResponse(values.stream().map(RepositoryResponse::from).toList());
        }
    }

    public record BindingListResponse(List<BindingResponse> items) {
        static BindingListResponse from(List<GitHubProviderBindingView> values) {
            return new BindingListResponse(values.stream().map(BindingResponse::from).toList());
        }
    }

    public record BindingResponse(
            String id,
            String teamId,
            String workspaceId,
            String connectionId,
            long connectionVersion,
            String executionIdentity,
            List<String> repositoryAllowlist,
            String status,
            boolean defaultUsage,
            long version) {

        static BindingResponse from(GitHubProviderBindingView value) {
            return new BindingResponse(
                    value.bindingId(),
                    value.teamId().toString(),
                    value.workspaceId().toString(),
                    value.connectionId(),
                    value.connectionVersion(),
                    value.executionIdentity().name(),
                    value.repositoryAllowlist(),
                    value.status().name(),
                    value.defaultUsage(),
                    value.version());
        }
    }

    public record RepositoryResponse(
            String externalRepositoryId,
            String fullName,
            String defaultBranch,
            String visibility,
            String discoveredAt,
            String cacheExpiresAt) {

        static RepositoryResponse from(GitHubRepositoryView value) {
            return new RepositoryResponse(
                    value.externalRepositoryId(),
                    value.fullName(),
                    value.defaultBranch().value(),
                    value.visibility().name(),
                    value.discoveredAt().toString(),
                    value.cacheExpiresAt().toString());
        }
    }

    public record RemotePreflightResponse(
            long connectionVersion,
            String externalRepositoryId,
            String fullName,
            String defaultBranch,
            String permissionsHash) {

        static RemotePreflightResponse from(GitHubRemotePreflightView value) {
            return new RemotePreflightResponse(
                    value.connectionVersion(),
                    value.externalRepositoryId(),
                    value.fullName(),
                    value.defaultBranch().value(),
                    value.permissionsHash());
        }
    }
}
