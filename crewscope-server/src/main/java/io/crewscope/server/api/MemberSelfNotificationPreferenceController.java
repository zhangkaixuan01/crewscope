package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.crewscope.application.notification.NotificationAdministrationService;
import io.crewscope.application.notification.UpdateNotificationPreferenceCommand;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.notification.NotificationPreference;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * Current-member notification preference under ADR-038: the caller's own active membership is
 * the whole authorization — no provider administration grant is implied or required here.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/members/me")
public final class MemberSelfNotificationPreferenceController {

    private final NotificationAdministrationService notifications;
    private final TeamRequestIdentityResolver identities;

    public MemberSelfNotificationPreferenceController(
            NotificationAdministrationService notifications,
            TeamRequestIdentityResolver identities) {
        this.notifications = notifications;
        this.identities = identities;
    }

    @GetMapping("/notification-preference")
    public Mono<ResponseEntity<PreferenceResponse>> preference(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        return query(authentication, scope, exchange, access -> notifications.selfPreference(
                        access, scope.organizationId(), scope.teamId()))
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.version()))
                        .body(PreferenceResponse.from(value)));
    }

    @PutMapping("/notification-preference")
    public Mono<ResponseEntity<PreferenceResponse>> updatePreference(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody PreferenceBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Scope scope = scope(organizationId, teamId);
        ApiHeaders.requireIdempotencyKey(key);
        UpdateNotificationPreferenceCommand update = new UpdateNotificationPreferenceCommand(
                body.enabled(),
                enumSet(body.enabledItemTypes(), InboxItemType.class, "enabledItemTypes"),
                optionalTimestamp(body.mutedUntil()),
                ApiHeaders.requireIfMatch(ifMatch));
        return query(authentication, scope, exchange, access -> notifications.updateSelfPreference(
                        access, scope.organizationId(), scope.teamId(), update))
                .map(updated -> ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(updated.version()))
                        .body(PreferenceResponse.from(updated)));
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

    private static Scope scope(String organizationId, String teamId) {
        try {
            return new Scope(
                    OrganizationId.from(organizationId),
                    TeamId.from(teamId));
        } catch (RuntimeException invalid) {
            throw invalidIdentifier();
        }
    }

    private static Optional<UtcTimestamp> optionalTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UtcTimestamp.parse(value));
        } catch (RuntimeException failure) {
            throw invalidField("mutedUntil");
        }
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException failure) {
            throw invalidField(field);
        }
    }

    private static <E extends Enum<E>> Set<E> enumSet(
            Set<String> values, Class<E> type, String field) {
        return values.stream()
                .map(value -> enumValue(value, type, field))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid preference field",
                Map.of("field", field));
    }

    private static ApiRequestException invalidIdentifier() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of());
    }

    private record Scope(OrganizationId organizationId, TeamId teamId) {}

    public record PreferenceResponse(
            String memberId, boolean enabled, Set<String> enabledItemTypes,
            Optional<String> mutedUntil, long version) {
        static PreferenceResponse from(NotificationPreference value) {
            return new PreferenceResponse(value.memberId().toString(), value.enabled(),
                    value.enabledItemTypes().stream().map(Enum::name)
                            .collect(Collectors.toUnmodifiableSet()),
                    value.mutedUntil().map(Object::toString), value.version());
        }
    }

    public record PreferenceBody(
            boolean enabled, @NotEmpty @Size(max = 16) Set<String> enabledItemTypes,
            @Size(max = 100) String mutedUntil) {
        @JsonAnySetter
        void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported preference property");
        }
    }
}
