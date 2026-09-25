package io.crewscope.server.api;

import io.crewscope.application.principal.PrincipalDirectoryEntry;
import io.crewscope.application.principal.PrincipalDirectoryFilterFingerprint;
import io.crewscope.application.principal.PrincipalDirectoryPage;
import io.crewscope.application.principal.PrincipalDirectoryPurpose;
import io.crewscope.application.principal.PrincipalDirectoryQuery;
import io.crewscope.application.principal.PrincipalDirectoryQueryService;
import io.crewscope.application.principal.PrincipalKind;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * Member-safe USER and AGENT directory for replacing UUID input fields (M9b-A06: full set,
 * stable ordering, signed continuations).
 *
 * <p>The legacy {@code q} parameter stays a synonym of {@code namePrefix}. Paging is dual-mode and
 * the modes are mutually exclusive: {@code offset} keeps the legacy contract while {@code after}
 * carries the signed keyset tail. A by-id lookup is a point query and accepts none of the above.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}")
public final class PrincipalDirectoryController {
    private final PrincipalDirectoryQueryService service;
    private final PrincipalDirectoryCursorCodec cursorCodec;
    private final TeamRequestIdentityResolver identityResolver;

    public PrincipalDirectoryController(
            PrincipalDirectoryQueryService service,
            PrincipalDirectoryCursorCodec cursorCodec,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec");
        this.identityResolver = identityResolver;
    }

    @GetMapping("/principals")
    public Mono<ResponseEntity<PrincipalDirectoryPageResponse>> search(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String namePrefix,
            @RequestParam(required = false) List<String> types,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) List<String> ids,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = parseOrganization(organizationId);
        TeamId team = parseTeam(teamId);
        Optional<String> prefix = namePrefix(q, namePrefix);
        Set<PrincipalKind> kinds = enumSet(types, PrincipalKind.class, "types");
        Set<PrincipalId> pointIds = identifierSet(ids);
        PrincipalDirectoryPurpose purposeValue = purpose(purpose);
        int pageOffset = offset == null ? 0 : requireNonNegative(offset);
        rejectIncompatibleParameters(pointIds, prefix, kinds, after, offset);
        int pageSize = ApiPagination.directoryLimit(limit);

        return identityResolver
                .resolve(authentication, organization, ApiCorrelationIds.resolve(exchange))
                .flatMap(
                        access -> {
                            PrincipalDirectoryCursorCodec.PrincipalDirectoryCursorScope scope =
                                    new PrincipalDirectoryCursorCodec.PrincipalDirectoryCursorScope(
                                    organization,
                                    team,
                                    access.actor().id(),
                                    purposeValue,
                                    fingerprint(prefix, kinds));
                            PrincipalDirectoryQuery query = new PrincipalDirectoryQuery(
                                    organization,
                                    team,
                                    prefix,
                                    kinds,
                                    pointIds,
                                    purposeValue,
                                    Optional.ofNullable(after)
                                            .map(token -> cursorCodec.decode(token, scope)),
                                    pageOffset,
                                    pageSize);
                            return blocking(() -> service.search(access, query))
                                    .map(value -> ResponseEntity.ok()
                                            .cacheControl(CacheControl.noStore())
                                            .body(PrincipalDirectoryPageResponse.from(
                                                    value, scope, cursorCodec)));
                        });
    }

    /** Both parameters name the same prefix filter, so they may not disagree. */
    private static Optional<String> namePrefix(String q, String namePrefix) {
        Optional<String> alias = cleanPrefix(q, "q");
        Optional<String> canonical = cleanPrefix(namePrefix, "namePrefix");
        if (alias.isPresent() && canonical.isPresent()
                && !alias.equals(canonical)) {
            throw invalidParameter("namePrefix", "q and namePrefix must name the same prefix");
        }
        return canonical.isPresent() ? canonical : alias;
    }

    private static Optional<String> cleanPrefix(String value, String parameter) {
        if (value == null) {
            return Optional.empty();
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return Optional.empty();
        }
        if (stripped.length() > 100) {
            throw invalidParameter(parameter, parameter + " must not exceed 100 characters");
        }
        return Optional.of(stripped);
    }

    private static PrincipalDirectoryPurpose purpose(String value) {
        if (value == null || value.isBlank()) {
            return PrincipalDirectoryPurpose.ASSIGNMENT;
        }
        try {
            return PrincipalDirectoryPurpose.valueOf(value.strip());
        } catch (IllegalArgumentException unknownPurpose) {
            throw invalidParameter(
                    "purpose", "Request contains an unsupported purpose: " + value);
        }
    }

    /** A by-id lookup is a point query; nothing may narrow or page it. */
    private static void rejectIncompatibleParameters(
            Set<PrincipalId> pointIds,
            Optional<String> prefix,
            Set<PrincipalKind> kinds,
            String after,
            Integer offset) {
        if (!pointIds.isEmpty()) {
            if (prefix.isPresent() || !kinds.isEmpty() || after != null || offset != null) {
                throw invalidParameter(
                        "ids", "A by-id lookup accepts no filter, no offset and no continuation");
            }
            return;
        }
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

    /** Parses at most fifty canonical identifiers; every malformed one is rejected by value. */
    private static Set<PrincipalId> identifierSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<PrincipalId> parsed = new LinkedHashSet<>();
        for (String value : values) {
            for (String token : value.split(",")) {
                String identifier = token.strip();
                if (identifier.isEmpty()) {
                    continue;
                }
                try {
                    parsed.add(PrincipalId.from(identifier));
                } catch (IllegalArgumentException malformedIdentifier) {
                    throw invalidParameter(
                            "ids", "Request contains an invalid identifier: " + identifier);
                }
            }
        }
        if (parsed.size() > 50) {
            throw invalidParameter("ids", "ids must name at most 50 principals");
        }
        return parsed;
    }

    /** Accepts repeated parameters and comma-joined values alike; unknown names are rejected. */
    private static <E extends Enum<E>> Set<E> enumSet(
            List<String> values, Class<E> type, String parameter) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<E> parsed = new LinkedHashSet<>();
        for (String value : values) {
            for (String name : value.split(",")) {
                String token = name.strip();
                if (token.isEmpty()) {
                    continue;
                }
                try {
                    parsed.add(Enum.valueOf(type, token));
                } catch (IllegalArgumentException unknownName) {
                    throw invalidParameter(
                            parameter, "Request contains an unsupported value: " + token);
                }
            }
        }
        return parsed;
    }

    private static PrincipalDirectoryFilterFingerprint fingerprint(
            Optional<String> prefix, Set<PrincipalKind> kinds) {
        String canonical = "principal-directory-filter-v1\n"
                + "namePrefix=" + prefix.orElse("") + "\n"
                + "types=" + String.join(",", kinds.stream()
                        .map(Enum::name).sorted().toList()) + "\n";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new PrincipalDirectoryFilterFingerprint(
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
                    "Request contains an invalid Team scope",
                    Map.of("field", "organizationId"));
        }
    }

    private static TeamId parseTeam(String value) {
        try {
            return TeamId.from(value);
        } catch (RuntimeException failure) {
            throw new ApiRequestException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid Team scope",
                    Map.of("field", "teamId"));
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
    public record PrincipalDirectoryPageResponse(
            List<PrincipalResponse> items, Integer nextOffset, String nextCursor) {
        static PrincipalDirectoryPageResponse from(
                PrincipalDirectoryPage value,
                PrincipalDirectoryCursorCodec.PrincipalDirectoryCursorScope scope,
                PrincipalDirectoryCursorCodec codec) {
            return new PrincipalDirectoryPageResponse(
                    value.items().stream().map(PrincipalResponse::from).toList(),
                    value.nextOffset().isPresent() ? value.nextOffset().getAsInt() : null,
                    value.nextCursor()
                            .map(tail -> codec.encode(scope, tail)).orElse(null));
        }
    }

    public record PrincipalResponse(
            String principalId, String kind, String displayName, String status, List<String> roles) {
        static PrincipalResponse from(PrincipalDirectoryEntry value) {
            return new PrincipalResponse(
                    value.principalId().toString(),
                    value.kind().name(),
                    value.displayName(),
                    value.status().name(),
                    value.roles());
        }
    }
}
