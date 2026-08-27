package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.crewscope.application.collaboration.CreateLarkConnectionRequest;
import io.crewscope.application.collaboration.LarkAdministrationCommandService;
import io.crewscope.application.collaboration.LarkCollaborationApplicationService;
import io.crewscope.application.collaboration.LarkConnectionApplicationService;
import io.crewscope.application.collaboration.LarkConnectionPreflightCommand;
import io.crewscope.application.collaboration.LarkConnectionView;
import io.crewscope.application.collaboration.LarkMemberMappingApplicationService;
import io.crewscope.application.collaboration.LarkMemberMappingCursor;
import io.crewscope.application.collaboration.LarkMemberMappingPage;
import io.crewscope.application.collaboration.ListLarkMemberMappingsQuery;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.notification.NotificationAdministrationService;
import io.crewscope.application.notification.NotificationDeliveryCursor;
import io.crewscope.application.notification.NotificationDeliveryFilter;
import io.crewscope.application.notification.NotificationDeliveryPage;
import io.crewscope.application.notification.NotificationDeliveryView;
import io.crewscope.application.notification.NotificationTemplateView;
import io.crewscope.application.notification.UpdateNotificationPreferenceCommand;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.collaboration.LarkMemberMappingId;
import io.crewscope.domain.collaboration.LarkMemberMapping;
import io.crewscope.domain.collaboration.LarkMemberMappingStatus;
import io.crewscope.domain.collaboration.LarkMemberMappingTerminalReason;
import io.crewscope.domain.collaboration.LarkMemberVerificationProofId;
import io.crewscope.domain.collaboration.LarkOpenId;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationDeliveryStatus;
import io.crewscope.domain.notification.NotificationPreference;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
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
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Team-scoped Lark administration API with explicit response DTO allowlists. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/lark")
public final class LarkAdministrationController {

    private final LarkConnectionApplicationService connections;
    private final LarkCollaborationApplicationService collaboration;
    private final LarkMemberMappingApplicationService mappings;
    private final LarkAdministrationCommandService commands;
    private final NotificationAdministrationService notifications;
    private final LarkMappingCursorCodec mappingCursors;
    private final NotificationDeliveryCursorCodec deliveryCursors;
    private final TeamRequestIdentityResolver identities;

    public LarkAdministrationController(
            LarkConnectionApplicationService connections,
            LarkCollaborationApplicationService collaboration,
            LarkMemberMappingApplicationService mappings,
            LarkAdministrationCommandService commands,
            NotificationAdministrationService notifications,
            LarkMappingCursorCodec mappingCursors,
            NotificationDeliveryCursorCodec deliveryCursors,
            TeamRequestIdentityResolver identities) {
        this.connections = connections;
        this.collaboration = collaboration;
        this.mappings = mappings;
        this.commands = commands;
        this.notifications = notifications;
        this.mappingCursors = mappingCursors;
        this.deliveryCursors = deliveryCursors;
        this.identities = identities;
    }

    @PostMapping("/connections")
    public Mono<ResponseEntity<CommandReceiptResponse>> createConnection(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CreateConnectionBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        long version = ApiHeaders.requireIfMatch(ifMatch);
        return command(authentication, scope, key, exchange, context -> connections.create(
                context, scope.organizationId(), new CreateLarkConnectionRequest(
                        scope.teamId(), body.tenantKey(), body.appId(), body.appSecret(),
                        optionalTimestamp(body.expiresAt())), version));
    }

    @GetMapping("/connections")
    public Mono<ResponseEntity<List<LarkConnectionView>>> listConnections(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        return query(authentication, scope, exchange,
                        access -> connections.list(access, scope.organizationId(), scope.teamId()))
                .map(LarkAdministrationController::ok);
    }

    @GetMapping("/connections/{connectionId}")
    public Mono<ResponseEntity<LarkConnectionView>> getConnection(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String connectionId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        return query(authentication, scope, exchange, access -> connections.get(
                        access, scope.organizationId(), scope.teamId(), connectionId(connectionId)))
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.version())).body(value));
    }

    @PostMapping("/connections/{connectionId}/rotate")
    public Mono<ResponseEntity<CommandReceiptResponse>> rotateConnection(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String connectionId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody RotateConnectionBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        long version = ApiHeaders.requireIfMatch(ifMatch);
        return command(authentication, scope, key, exchange, context -> connections.rotate(
                context, scope.organizationId(), scope.teamId(), connectionId(connectionId),
                body.appId(), body.appSecret(), version));
    }

    @PostMapping("/connections/{connectionId}/revoke")
    public Mono<ResponseEntity<CommandReceiptResponse>> revokeConnection(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String connectionId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody RevokeBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        return command(authentication, scope, key, exchange, context -> connections.revoke(
                context, scope.organizationId(), scope.teamId(), connectionId(connectionId),
                body.reason(), ApiHeaders.requireIfMatch(ifMatch)));
    }

    @PostMapping("/bindings/{bindingId}/preflight")
    public Mono<ResponseEntity<PreflightResponse>> preflight(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String bindingId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        long expected = ApiHeaders.requireIfMatch(ifMatch);
        return query(authentication, scope, exchange, access -> collaboration.preflight(
                        new LarkConnectionPreflightCommand(
                                scope.organizationId(), scope.teamId(), bindingId(bindingId),
                                LarkCollaborationCapabilities.COMPLETE, access.actor())))
                .map(value -> {
                    if (value.providerBindingVersion() != expected) {
                        throw new OptimisticLockConflictException(
                                "ProviderBinding",
                                value.providerBindingId(),
                                expected,
                                value.providerBindingVersion());
                    }
                    return ok(new PreflightResponse(
                            value.providerBindingId().toString(), value.providerBindingVersion(),
                            value.checkedAt().toString()));
                });
    }

    @GetMapping("/bindings/{bindingId}/health")
    public Mono<ResponseEntity<HealthResponse>> health(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String bindingId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        return query(authentication, scope, exchange, access -> collaboration.health(
                        new LarkConnectionPreflightCommand(
                                scope.organizationId(), scope.teamId(), bindingId(bindingId),
                                LarkCollaborationCapabilities.COMPLETE, access.actor())))
                .map(value -> ok(new HealthResponse(
                        value.status().name(), value.retryable(),
                        value.retryAfter().map(java.time.Duration::toSeconds),
                        value.evidenceCode(), value.checkedAt().toString())));
    }

    @PostMapping("/member-verifications")
    public Mono<ResponseEntity<CommandReceiptResponse>> verifyMember(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody VerifyMemberBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        return command(authentication, scope, key, exchange, context -> commands.verifyMember(
                context, scope.organizationId(), scope.teamId(), bindingId(body.providerBindingId()),
                ApiHeaders.requireIfMatch(ifMatch), new LarkOpenId(body.openId())));
    }

    @PostMapping("/member-mappings")
    public Mono<ResponseEntity<CommandReceiptResponse>> confirmMapping(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody ConfirmMappingBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        if (ApiHeaders.requireIfMatch(ifMatch) != 0) throw invalid("If-Match");
        return command(authentication, scope, key, exchange, context -> commands.confirmMapping(
                context, scope.organizationId(), scope.teamId(), memberId(body.memberId()),
                bindingId(body.providerBindingId()), proofId(body.proofId())));
    }

    @GetMapping("/member-mappings")
    public Mono<ResponseEntity<MappingPageResponse>> listMappings(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        Optional<LarkMemberMappingStatus> selectedStatus = optionalEnum(status, LarkMemberMappingStatus.class);
        int pageSize = limit == null ? 50 : limit;
        return query(authentication, scope, exchange, access -> {
                    Optional<LarkMemberMappingCursor> cursor = Optional.empty();
                    if (after != null && !after.isBlank()) {
                        // Current PROVIDER_MANAGE authority precedes Cursor decoding.
                        mappings.requireAdministrator(
                                scope.organizationId(), scope.teamId(), access.actor());
                        cursor = Optional.of(mappingCursors.decode(
                                after, scope.organizationId(), scope.teamId(), selectedStatus));
                    }
                    return mappings.listMappings(new ListLarkMemberMappingsQuery(
                            scope.organizationId(), scope.teamId(), selectedStatus,
                            cursor, pageSize, access.actor()));
                })
                .map(page -> ok(MappingPageResponse.from(
                        page, mappingCursors, scope, selectedStatus)));
    }

    @PostMapping("/member-mappings/{mappingId}/revoke")
    public Mono<ResponseEntity<CommandReceiptResponse>> revokeMapping(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String mappingId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody RevokeMappingBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        return command(authentication, scope, key, exchange, context -> commands.revokeMapping(
                context, scope.organizationId(), scope.teamId(), mappingId(mappingId),
                ApiHeaders.requireIfMatch(ifMatch), enumValue(
                        body.reason(), LarkMemberMappingTerminalReason.class, "reason")));
    }

    @GetMapping("/notification-templates")
    public Mono<ResponseEntity<List<NotificationTemplateView>>> templates(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        return query(authentication, scope, exchange, access -> notifications.templates(
                        access, scope.organizationId(), scope.teamId()))
                .map(LarkAdministrationController::ok);
    }

    @GetMapping("/notification-preferences/{memberId}")
    public Mono<ResponseEntity<PreferenceResponse>> preference(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String memberId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        return query(authentication, scope, exchange, access -> notifications.preference(
                        access, scope.organizationId(), scope.teamId(), memberId(memberId)))
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.version()))
                        .body(PreferenceResponse.from(value)));
    }

    @PutMapping("/notification-preferences/{memberId}")
    public Mono<ResponseEntity<CommandReceiptResponse>> updatePreference(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String memberId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody PreferenceBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        UpdateNotificationPreferenceCommand update = new UpdateNotificationPreferenceCommand(
                body.enabled(), enumSet(body.enabledItemTypes(), InboxItemType.class, "enabledItemTypes"),
                optionalTimestamp(body.mutedUntil()), ApiHeaders.requireIfMatch(ifMatch));
        return command(authentication, scope, key, exchange, context -> commands.updatePreference(
                context, scope.organizationId(), scope.teamId(), memberId(memberId), update));
    }

    @GetMapping("/notification-deliveries")
    public Mono<ResponseEntity<DeliveryPageResponse>> deliveries(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) Set<String> status,
            @RequestParam(required = false) Set<String> itemType,
            @RequestParam(required = false) String recipientMemberId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) @Min(1) @Max(200) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        NotificationDeliveryFilter filter = new NotificationDeliveryFilter(
                enumSet(status == null ? Set.of() : status, NotificationDeliveryStatus.class, "status"),
                enumSet(itemType == null ? Set.of() : itemType, InboxItemType.class, "itemType"),
                Optional.ofNullable(recipientMemberId).map(LarkAdministrationController::memberId));
        return query(authentication, scope, exchange, access -> {
                    Optional<NotificationDeliveryCursor> cursor = Optional.empty();
                    if (after != null && !after.isBlank()) {
                        // Current PROVIDER_MANAGE authority precedes Cursor decoding.
                        notifications.requireAdministrator(
                                access, scope.organizationId(), scope.teamId());
                        cursor = Optional.of(deliveryCursors.decode(
                                after, scope.organizationId(), scope.teamId(), filter));
                    }
                    return notifications.deliveries(
                            access, scope.organizationId(), scope.teamId(), filter, cursor,
                            limit == null ? 50 : limit);
                })
                .map(page -> ok(DeliveryPageResponse.from(
                        page, deliveryCursors, scope, filter)));
    }

    @GetMapping("/notification-deliveries/{deliveryId}")
    public Mono<ResponseEntity<NotificationDeliveryView>> delivery(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String deliveryId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        return query(authentication, scope, exchange, access -> notifications.delivery(
                        access, scope.organizationId(), scope.teamId(), deliveryId(deliveryId)))
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.version())).body(value));
    }

    @PostMapping("/notification-deliveries/{deliveryId}/redeliver")
    public Mono<ResponseEntity<CommandReceiptResponse>> redeliver(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String deliveryId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        return command(authentication, scope, key, exchange, context -> commands.redeliver(
                context, scope.organizationId(), scope.teamId(), deliveryId(deliveryId),
                ApiHeaders.requireIfMatch(ifMatch)));
    }

    private <T> Mono<ResponseEntity<CommandReceiptResponse>> command(
            Authentication authentication,
            Scope scope,
            String key,
            ServerWebExchange exchange,
            Function<TeamCommandContext, CommandExecution<T>> action) {
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identities.resolve(authentication, scope.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> action.apply(new TeamCommandContext(
                        access, idempotencyKey, correlationId, Optional.empty()))))
                .map(CommandReceiptResponse::accepted);
    }

    private <T> Mono<T> query(
            Authentication authentication,
            Scope scope,
            ServerWebExchange exchange,
            Function<TeamAccessContext, T> action) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identities.resolve(authentication, scope.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> action.apply(access)));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private static Scope scope(String organizationId, String teamId) {
        try {
            return new Scope(OrganizationId.from(organizationId), TeamId.from(teamId));
        } catch (RuntimeException failure) {
            throw invalid("scope");
        }
    }

    private static ConnectionId connectionId(String value) {
        try {
            return ConnectionId.from(value);
        } catch (RuntimeException failure) {
            throw invalid("connectionId");
        }
    }

    private static ProviderBindingId bindingId(String value) {
        try {
            return ProviderBindingId.from(value);
        } catch (RuntimeException failure) {
            throw invalid("providerBindingId");
        }
    }

    private static TeamMemberId memberId(String value) {
        try {
            return TeamMemberId.from(value);
        } catch (RuntimeException failure) {
            throw invalid("memberId");
        }
    }

    private static LarkMemberVerificationProofId proofId(String value) {
        try {
            return new LarkMemberVerificationProofId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw invalid("proofId");
        }
    }

    private static LarkMemberMappingId mappingId(String value) {
        try {
            return new LarkMemberMappingId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw invalid("mappingId");
        }
    }

    private static NotificationDeliveryId deliveryId(String value) {
        try {
            return new NotificationDeliveryId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw invalid("deliveryId");
        }
    }

    private static Optional<UtcTimestamp> optionalTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UtcTimestamp.parse(value));
        } catch (RuntimeException failure) {
            throw invalid("timestamp");
        }
    }

    private static <E extends Enum<E>> Optional<E> optionalEnum(String value, Class<E> type) {
        return value == null || value.isBlank()
                ? Optional.empty()
                : Optional.of(enumValue(value, type, "enum"));
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException failure) {
            throw invalid(field);
        }
    }

    private static <E extends Enum<E>> Set<E> enumSet(Set<String> values, Class<E> type, String field) {
        return values.stream()
                .map(value -> enumValue(value, type, field))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ApiRequestException invalid(String field) {
        return new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request", "Request contains an invalid Lark administration field",
                Map.of("field", field));
    }

    private record Scope(OrganizationId organizationId, TeamId teamId) {}

    public record CreateConnectionBody(
            @NotBlank @Size(max = 200) String tenantKey,
            @NotBlank @Size(max = 200) String appId,
            @NotBlank @Size(max = 1000) String appSecret,
            String expiresAt) {

        @JsonAnySetter
        void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
            rejectUnknownLarkProperty();
        }
    }

    public record RotateConnectionBody(
            @NotBlank @Size(max = 200) String appId,
            @NotBlank @Size(max = 1000) String appSecret) {

        @JsonAnySetter
        void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
            rejectUnknownLarkProperty();
        }
    }

    public record RevokeBody(@NotBlank @Size(max = 500) String reason) {
        @JsonAnySetter
        void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
            rejectUnknownLarkProperty();
        }
    }
    public record VerifyMemberBody(
            @NotBlank @Size(max = 100) String providerBindingId,
            @NotBlank @Size(max = 200) String openId) {
        @JsonAnySetter
        void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
            rejectUnknownLarkProperty();
        }
    }
    public record ConfirmMappingBody(
            @NotBlank @Size(max = 100) String memberId,
            @NotBlank @Size(max = 100) String providerBindingId,
            @NotBlank @Size(max = 100) String proofId) {
        @JsonAnySetter
        void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
            rejectUnknownLarkProperty();
        }
    }
    public record RevokeMappingBody(@NotBlank @Size(max = 100) String reason) {
        @JsonAnySetter
        void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
            rejectUnknownLarkProperty();
        }
    }
    public record PreferenceBody(
            boolean enabled, @NotEmpty @Size(max = 16) Set<String> enabledItemTypes,
            @Size(max = 100) String mutedUntil) {
        @JsonAnySetter
        void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
            rejectUnknownLarkProperty();
        }
    }

    private static void rejectUnknownLarkProperty() {
        throw new IllegalArgumentException("Unsupported Lark administration property");
    }
    public record PreflightResponse(String providerBindingId, long version, String checkedAt) {}
    public record HealthResponse(String status, boolean retryable, Optional<Long> retryAfterSeconds, String evidenceCode, String checkedAt) {}

    public record MappingResponse(
            String mappingId, String memberId, String providerBindingId, String status,
            Optional<String> terminalReason, String verifiedAt, String updatedAt, long version) {
        static MappingResponse from(LarkMemberMapping value) {
            return new MappingResponse(value.id().toString(), value.memberId().toString(),
                    value.providerBindingId().toString(), value.status().name(),
                    value.terminalReason().map(Enum::name), value.verifiedAt().toString(),
                    value.audit().updatedAt().toString(), value.version());
        }
    }

    public record MappingPageResponse(List<MappingResponse> items, Optional<String> nextCursor) {
        static MappingPageResponse from(
                LarkMemberMappingPage page, LarkMappingCursorCodec codec, Scope scope,
                Optional<LarkMemberMappingStatus> status) {
            return new MappingPageResponse(page.items().stream().map(MappingResponse::from).toList(),
                    page.nextCursor().map(value -> codec.encode(
                            value, scope.organizationId(), scope.teamId(), status)));
        }
    }

    public record PreferenceResponse(
            String memberId, boolean enabled, Set<String> enabledItemTypes,
            Optional<String> mutedUntil, long version) {
        static PreferenceResponse from(NotificationPreference value) {
            return new PreferenceResponse(value.memberId().toString(), value.enabled(),
                    value.enabledItemTypes().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                    value.mutedUntil().map(Object::toString), value.version());
        }
    }

    public record DeliveryPageResponse(
            List<NotificationDeliveryView> items, Optional<String> nextCursor) {
        static DeliveryPageResponse from(
                NotificationDeliveryPage page, NotificationDeliveryCursorCodec codec,
                Scope scope, NotificationDeliveryFilter filter) {
            return new DeliveryPageResponse(page.items(), page.nextCursor().map(value -> codec.encode(
                    value, scope.organizationId(), scope.teamId(), filter)));
        }
    }
}
