package io.crewscope.server.api;

import io.crewscope.application.organization.OrganizationMemberDirectoryEntry;
import io.crewscope.application.organization.OrganizationMemberDirectoryFilterFingerprint;
import io.crewscope.application.organization.OrganizationMemberDirectoryPage;
import io.crewscope.application.organization.OrganizationMemberDirectoryQuery;
import io.crewscope.application.organization.OrganizationMemberDirectoryQueryService;
import io.crewscope.domain.shared.id.OrganizationId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
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
 * Organization member directory read (M13b-A01) — the picker source for governance forms and the
 * member-management page, gated by the {@code org:audit-read} capability.
 *
 * <p>Paging is dual-mode and the modes are mutually exclusive: {@code offset} keeps the legacy
 * contract while {@code after} carries the signed keyset tail. The response exposes account
 * identity and status, the principal's display name and the ACTIVE organization roles; the
 * account's email never leaves the server.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
public final class OrganizationMemberDirectoryController {
    private final OrganizationMemberDirectoryQueryService service;
    private final OrganizationMemberCursorCodec cursorCodec;
    private final OrganizationRequestIdentityResolver identityResolver;

    public OrganizationMemberDirectoryController(
            OrganizationMemberDirectoryQueryService service,
            OrganizationMemberCursorCodec cursorCodec,
            OrganizationRequestIdentityResolver identityResolver) {
        this.service = service;
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec");
        this.identityResolver = identityResolver;
    }

    @GetMapping("/members")
    public Mono<ResponseEntity<MemberDirectoryPageResponse>> search(
            @PathVariable String organizationId,
            @RequestParam(required = false) String namePrefix,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = parseOrganization(organizationId);
        Optional<String> prefix = cleanPrefix(namePrefix);
        rejectIncompatibleParameters(after, offset);
        int pageOffset = offset == null ? 0 : requireNonNegative(offset);
        int pageSize = ApiPagination.directoryLimit(limit);

        return identityResolver
                .resolve(authentication, organization, ApiCorrelationIds.resolve(exchange))
                .flatMap(
                        access -> {
                            OrganizationMemberCursorCodec.OrganizationMemberCursorScope scope =
                                    new OrganizationMemberCursorCodec.OrganizationMemberCursorScope(
                                            organization,
                                            access.actor().id(),
                                            fingerprint(prefix));
                            OrganizationMemberDirectoryQuery query = new OrganizationMemberDirectoryQuery(
                                    organization,
                                    prefix,
                                    Optional.ofNullable(after)
                                            .map(token -> cursorCodec.decode(token, scope)),
                                    pageOffset,
                                    pageSize);
                            return blocking(() -> service.search(access, query))
                                    .map(value -> ResponseEntity.ok()
                                            .cacheControl(CacheControl.noStore())
                                            .body(MemberDirectoryPageResponse.from(
                                                    value, scope, cursorCodec)));
                        });
    }

    private static Optional<String> cleanPrefix(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return Optional.empty();
        }
        if (stripped.length() > 100) {
            throw invalidParameter(
                    "namePrefix", "namePrefix must not exceed 100 characters");
        }
        return Optional.of(stripped);
    }

    private static void rejectIncompatibleParameters(String after, Integer offset) {
        if (after != null && offset != null) {
            throw invalidParameter("after", "offset and after are mutually exclusive");
        }
    }

    private static int requireNonNegative(int value) {
        if (value < 0) {
            throw invalidParameter("offset", "offset must be non-negative");
        }
        return value;
    }

    private static OrganizationMemberDirectoryFilterFingerprint fingerprint(Optional<String> prefix) {
        String canonical = "organization-member-directory-filter-v1\n"
                + "namePrefix=" + prefix.orElse("") + "\n";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new OrganizationMemberDirectoryFilterFingerprint(
                    HexFormat.of().formatHex(
                            digest.digest(canonical.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static OrganizationId parseOrganization(String value) {
        try {
            return OrganizationId.from(value);
        } catch (RuntimeException failure) {
            throw new ApiRequestException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid Organization scope",
                    Map.of("field", "organizationId"));
        }
    }

    private static ApiRequestException invalidParameter(String parameter, String message) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                message,
                Map.of("parameter", parameter));
    }

    /** One directory page plus exactly the continuation mode the request used. */
    public record MemberDirectoryPageResponse(
            List<MemberResponse> items, Integer nextOffset, String nextCursor) {
        static MemberDirectoryPageResponse from(
                OrganizationMemberDirectoryPage value,
                OrganizationMemberCursorCodec.OrganizationMemberCursorScope scope,
                OrganizationMemberCursorCodec codec) {
            return new MemberDirectoryPageResponse(
                    value.items().stream().map(MemberResponse::from).toList(),
                    value.nextOffset().isPresent() ? value.nextOffset().getAsInt() : null,
                    value.nextCursor()
                            .map(tail -> codec.encode(
                                    new OrganizationMemberCursorCodec.OrganizationMemberCursor(
                                            scope, tail)))
                            .orElse(null));
        }
    }

    public record MemberResponse(
            String accountId,
            String principalId,
            String displayName,
            String username,
            String status,
            List<String> orgRoles) {
        static MemberResponse from(OrganizationMemberDirectoryEntry value) {
            return new MemberResponse(
                    value.accountId().toString(),
                    value.principalId().toString(),
                    value.displayName(),
                    value.username(),
                    value.status().name(),
                    value.orgRoles());
        }
    }
}
