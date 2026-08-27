package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.crewscope.application.audit.AuditCursor;
import io.crewscope.application.audit.AuditExportRequest;
import io.crewscope.application.audit.AuditPage;
import io.crewscope.application.audit.AuditQuery;
import io.crewscope.application.audit.AuditQueryApplicationService;
import io.crewscope.application.audit.AuditQueryFilter;
import io.crewscope.application.team.TeamAccessContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Team-governance HTTP boundary for safe Audit search and bounded JSON export. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/audit-events")
public final class AuditController {

    static final MediaType EXPORT_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.crewscope.audit-export+json");

    private final AuditQueryApplicationService service;
    private final TeamRequestIdentityResolver identityResolver;
    private final ObjectProvider<AuditCursorCodec> cursorCodecs;

    public AuditController(
            AuditQueryApplicationService service,
            TeamRequestIdentityResolver identityResolver,
            ObjectProvider<AuditCursorCodec> cursorCodecs) {
        this.service = service;
        this.identityResolver = identityResolver;
        this.cursorCodecs = cursorCodecs;
    }

    @GetMapping
    public Mono<ResponseEntity<AuditPageResponse>> history(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String occurredFrom,
            @RequestParam(required = false) String occurredBefore,
            @RequestParam(required = false) List<String> categories,
            @RequestParam(required = false) List<String> outcomes,
            @RequestParam(required = false) List<String> initiatorIds,
            @RequestParam(required = false) List<String> actorIds,
            @RequestParam(required = false) List<String> agentPrincipalIds,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) String providerBindingId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        AuditApiSupport.Route route = AuditApiSupport.route(organizationId, teamId);
        AuditQueryFilter filter = AuditApiSupport.filter(
                occurredFrom,
                occurredBefore,
                categories,
                outcomes,
                initiatorIds,
                actorIds,
                agentPrincipalIds,
                subjectType,
                subjectId,
                providerBindingId,
                correlationId);
        AuditCursorCodec codec = cursorCodec();
        int pageSize = AuditApiSupport.limit(limit);
        UUID requestCorrelation = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, route.organizationId(), requestCorrelation)
                .flatMap(access -> blocking(() -> history(
                        access,
                        requestCorrelation,
                        route,
                        filter,
                        after,
                        pageSize,
                        codec)))
                .map(page -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(AuditPageResponse.from(page, codec)));
    }

    @PostMapping(path = "/export", produces = "application/vnd.crewscope.audit-export+json")
    public Mono<ResponseEntity<AuditExportResponse>> export(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @Valid @RequestBody ExportRequest body,
            Authentication authentication,
            ServerWebExchange exchange) {
        AuditApiSupport.Route route = AuditApiSupport.route(organizationId, teamId);
        ExportRequest requested = body == null ? new ExportRequest() : body;
        AuditQueryFilter filter = AuditApiSupport.filter(
                requested.occurredFrom,
                requested.occurredBefore,
                requested.categories,
                requested.outcomes,
                requested.initiatorIds,
                requested.actorIds,
                requested.agentPrincipalIds,
                requested.subjectType,
                requested.subjectId,
                requested.providerBindingId,
                requested.correlationId);
        int maximumRows = requested.maximumRows == null
                ? AuditExportRequest.MAXIMUM_ROWS
                : requested.maximumRows;
        AuditExportRequest export = AuditExportRequest.create(
                route.organizationId(), route.teamId(), filter, maximumRows);
        UUID requestCorrelation = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, route.organizationId(), requestCorrelation)
                .flatMap(access -> blocking(() -> service.export(
                        access, requestCorrelation, export)))
                .map(batch -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .contentType(EXPORT_MEDIA_TYPE)
                        .header(
                                HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"crewscope-audit-export.json\"")
                        .body(AuditExportResponse.from(batch)));
    }

    private AuditPage history(
            TeamAccessContext access,
            UUID correlationId,
            AuditApiSupport.Route route,
            AuditQueryFilter filter,
            String after,
            int limit,
            AuditCursorCodec codec) {
        Optional<AuditCursor> cursor = Optional.empty();
        if (after != null && !after.isBlank()) {
            // Reauthorize before decoding a signed, scope-bearing token to avoid a Cursor oracle.
            service.requireRead(
                    access,
                    correlationId,
                    route.organizationId(),
                    route.teamId());
            cursor = Optional.of(codec.decode(
                    after, route.organizationId(), route.teamId(), filter));
        }
        return service.query(
                access,
                correlationId,
                AuditQuery.create(
                        route.organizationId(), route.teamId(), filter, cursor, limit));
    }

    private AuditCursorCodec cursorCodec() {
        AuditCursorCodec codec = cursorCodecs.getIfAvailable();
        if (codec == null) {
            throw AuditApiSupport.unavailable();
        }
        return codec;
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    /** Closed transport shape; all values are normalized again by AuditApiSupport. */
    public static final class ExportRequest {
        public String occurredFrom;
        public String occurredBefore;
        public List<String> categories;
        public List<String> outcomes;
        public List<String> initiatorIds;
        public List<String> actorIds;
        public List<String> agentPrincipalIds;
        public String subjectType;
        public String subjectId;
        public String providerBindingId;
        public String correlationId;
        public Integer maximumRows;

        /** Prevents future or misspelled governance filters from being silently ignored. */
        @JsonAnySetter
        void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported Audit export property");
        }
    }
}
