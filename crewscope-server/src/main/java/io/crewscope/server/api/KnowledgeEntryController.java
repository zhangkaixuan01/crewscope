package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.knowledge.CreateKnowledgeEntryCommand;
import io.crewscope.application.knowledge.KnowledgeCommandService;
import io.crewscope.application.knowledge.KnowledgeEntryFilter;
import io.crewscope.application.knowledge.KnowledgeEntryPage;
import io.crewscope.application.knowledge.KnowledgeEntryPageRequest;
import io.crewscope.application.knowledge.KnowledgeEntryVersionPage;
import io.crewscope.application.knowledge.KnowledgeVersionPageRequest;
import io.crewscope.application.knowledge.UpdateKnowledgeDraftCommand;
import io.crewscope.application.retrieval.KnowledgeIndexStatus;
import io.crewscope.application.retrieval.KnowledgeIndexStatusCatalog;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryStatus;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

/**
 * A02a HTTP boundary for manually maintained Team knowledge: entry lifecycle commands
 * with strong ETag concurrency plus member-wide reads of heads and immutable versions.
 * {@code indexStatus} is the I01b derived projection: INDEXED when a vector row exists
 * for the effective revision, FAILED behind it, PENDING otherwise — and constantly
 * PENDING on deployments where the optional index catalog is not assembled.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/knowledge/entries")
public final class KnowledgeEntryController {

    /** Also the constant answer whenever the index catalog is not assembled. */
    private static final String INDEX_STATUS_PENDING = "PENDING";

    private final KnowledgeCommandService service;
    private final TeamRequestIdentityResolver identityResolver;
    private final ObjectProvider<KnowledgeIndexStatusCatalog> indexStatusCatalog;

    public KnowledgeEntryController(
            KnowledgeCommandService service,
            TeamRequestIdentityResolver identityResolver,
            ObjectProvider<KnowledgeIndexStatusCatalog> indexStatusCatalog) {
        this.service = service;
        this.identityResolver = identityResolver;
        this.indexStatusCatalog = indexStatusCatalog;
    }

    @PostMapping
    public Mono<ResponseEntity<CommandReceiptResponse>> create(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CreateEntryBody request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        CreateKnowledgeEntryCommand command = new CreateKnowledgeEntryCommand(
                entryKey(request.entryKey()),
                category(request.category()),
                request.title(),
                request.content());
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> service.create(context, team, command));
    }

    @GetMapping
    public Mono<ResponseEntity<EntryListResponse>> list(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        KnowledgeEntryFilter filter = filter(status, category);
        KnowledgeEntryPageRequest pageRequest =
                new KnowledgeEntryPageRequest(afterEntryKey(after), ApiPagination.limit(limit));
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> {
                            KnowledgeEntryPage page = service.teamListing(
                                    access, organization, team, filter, pageRequest);
                            return new ListingView(
                                    page, indexStatuses(organization, team, page));
                        })
                .map(view -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(EntryListResponse.from(view.page(), view.statuses())));
    }

    @GetMapping("/{entryId}")
    public Mono<ResponseEntity<EntryResponse>> get(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String entryId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        KnowledgeEntryId entry = entryId(entryId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> {
                            KnowledgeEntry value =
                                    service.entry(access, organization, team, entry);
                            return new EntryView(value, indexStatus(organization, team, value));
                        })
                .map(view -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(view.entry().version()))
                        .body(EntryResponse.from(view.entry(), view.status())));
    }

    @PatchMapping("/{entryId}")
    public Mono<ResponseEntity<CommandReceiptResponse>> updateDraft(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String entryId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateDraftBody request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        KnowledgeEntryId entry = entryId(entryId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        UpdateKnowledgeDraftCommand command = new UpdateKnowledgeDraftCommand(
                request.title(),
                request.content(),
                Optional.ofNullable(request.category())
                        .map(KnowledgeEntryController::category));
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> service.updateDraft(
                        context, team, entry, expectedVersion, command));
    }

    @PostMapping("/{entryId}/publish")
    public Mono<ResponseEntity<CommandReceiptResponse>> publish(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String entryId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        return mutatingCommand(
                organizationId,
                teamId,
                entryId,
                key,
                ifMatch,
                authentication,
                exchange,
                (context, team, entry, expectedVersion) ->
                        service.publish(context, team, entry, expectedVersion));
    }

    @PostMapping("/{entryId}/retire")
    public Mono<ResponseEntity<CommandReceiptResponse>> retire(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String entryId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        return mutatingCommand(
                organizationId,
                teamId,
                entryId,
                key,
                ifMatch,
                authentication,
                exchange,
                (context, team, entry, expectedVersion) ->
                        service.retire(context, team, entry, expectedVersion));
    }

    @DeleteMapping("/{entryId}")
    public Mono<ResponseEntity<CommandReceiptResponse>> delete(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String entryId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        return mutatingCommand(
                organizationId,
                teamId,
                entryId,
                key,
                ifMatch,
                authentication,
                exchange,
                (context, team, entry, expectedVersion) ->
                        service.delete(context, team, entry, expectedVersion));
    }

    @GetMapping("/{entryId}/versions")
    public Mono<ResponseEntity<VersionListResponse>> versions(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String entryId,
            @RequestParam(required = false) Long after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        KnowledgeEntryId entry = entryId(entryId);
        KnowledgeVersionPageRequest pageRequest = new KnowledgeVersionPageRequest(
                afterRevision(after), ApiPagination.limit(limit));
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> {
                            KnowledgeEntryVersionPage page = service.versionHistory(
                                    access, organization, team, entry, pageRequest);
                            return new VersionListView(
                                    page,
                                    indexProjectionEnabled()
                                            ? versionStatuses(organization, team, service.entry(
                                                    access, organization, team, entry))
                                            : revision -> INDEX_STATUS_PENDING);
                        })
                .map(view -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(VersionListResponse.from(view.page(), view.statuses())));
    }

    @GetMapping("/{entryId}/versions/{revision}")
    public Mono<ResponseEntity<VersionResponse>> version(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String entryId,
            @PathVariable String revision,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        KnowledgeEntryId entry = entryId(entryId);
        KnowledgeEntryRevision parsed = revision(revision);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> {
                            KnowledgeEntryVersion value =
                                    service.version(access, organization, team, entry, parsed);
                            return new VersionView(
                                    value,
                                    indexProjectionEnabled()
                                            ? versionIndexStatus(organization, team, service.entry(
                                                    access, organization, team, entry), value.revision())
                                            : INDEX_STATUS_PENDING);
                        })
                .map(view -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(contentHashEtag(view.version()))
                        .body(VersionResponse.from(view.version(), view.status())));
    }

    @GetMapping("/{entryId}/effective-version")
    public Mono<ResponseEntity<VersionResponse>> effectiveVersion(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String entryId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        KnowledgeEntryId entry = entryId(entryId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> {
                            KnowledgeEntryVersion value =
                                    service.effectiveVersion(access, organization, team, entry);
                            return new VersionView(
                                    value,
                                    indexProjectionEnabled()
                                            ? versionIndexStatus(organization, team, service.entry(
                                                    access, organization, team, entry), value.revision())
                                            : INDEX_STATUS_PENDING);
                        })
                .map(view -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(contentHashEtag(view.version()))
                        .body(VersionResponse.from(view.version(), view.status())));
    }

    // ---------------------------------------------------------------- internals

    /** Carries the derived projection out of the blocking query, never off the pool. */
    private record EntryView(KnowledgeEntry entry, String status) {}

    private record ListingView(
            KnowledgeEntryPage page, Map<KnowledgeEntryId, KnowledgeIndexStatus> statuses) {}

    private record VersionListView(
            KnowledgeEntryVersionPage page, Function<KnowledgeEntryRevision, String> statuses) {}

    private record VersionView(KnowledgeEntryVersion version, String status) {}

    private String indexStatus(OrganizationId organization, TeamId team, KnowledgeEntry entry) {
        KnowledgeIndexStatusCatalog catalog = indexStatusCatalog.getIfAvailable();
        if (catalog == null) {
            return INDEX_STATUS_PENDING;
        }
        return catalog.statusOf(organization, team, entry.id()).name();
    }

    /**
     * Deployments without the vector store report a constant PENDING, so the version
     * endpoints skip their entry-head query entirely instead of paying it for a value
     * that is decided before the row is read.
     */
    private boolean indexProjectionEnabled() {
        return indexStatusCatalog.getIfAvailable() != null;
    }

    private Map<KnowledgeEntryId, KnowledgeIndexStatus> indexStatuses(
            OrganizationId organization, TeamId team, KnowledgeEntryPage page) {
        KnowledgeIndexStatusCatalog catalog = indexStatusCatalog.getIfAvailable();
        if (catalog == null) {
            return Map.of();
        }
        return catalog.statusesOf(
                organization, team, page.items().stream().map(KnowledgeEntry::id).toList());
    }

    /**
     * One version's projection: only the effective revision of a PUBLISHED head can be
     * INDEXED — superseded or retired revisions answer PENDING by definition.
     */
    private String versionIndexStatus(
            OrganizationId organization,
            TeamId team,
            KnowledgeEntry head,
            KnowledgeEntryRevision revision) {
        boolean effective = head.status() == KnowledgeEntryStatus.PUBLISHED
                && revision != null
                && head.effectiveRevision().map(revision::equals).orElse(false);
        return effective ? indexStatus(organization, team, head) : INDEX_STATUS_PENDING;
    }

    private Function<KnowledgeEntryRevision, String> versionStatuses(
            OrganizationId organization, TeamId team, KnowledgeEntry head) {
        String effectiveStatus = versionIndexStatus(
                organization, team, head, head.effectiveRevision().orElse(null));
        return revision -> revision.equals(head.effectiveRevision().orElse(null))
                ? effectiveStatus
                : INDEX_STATUS_PENDING;
    }

    private interface MutatingAction {
        CommandExecution<KnowledgeEntry> apply(
                TeamCommandContext context,
                TeamId team,
                KnowledgeEntryId entry,
                long expectedVersion);
    }

    private Mono<ResponseEntity<CommandReceiptResponse>> mutatingCommand(
            String organizationValue,
            String teamValue,
            String entryValue,
            String key,
            String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange,
            MutatingAction action) {
        OrganizationId organization = organizationId(organizationValue);
        TeamId team = teamId(teamValue);
        KnowledgeEntryId entry = entryId(entryValue);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> action.apply(context, team, entry, expectedVersion));
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

    private static KnowledgeEntryFilter filter(String status, String category) {
        KnowledgeEntryStatus parsedStatus =
                status == null ? null : parseEnum(status, KnowledgeEntryStatus::valueOf, "status");
        KnowledgeCategory parsedCategory =
                category == null ? null : parseEnum(category, KnowledgeCategory::valueOf, "category");
        if (parsedStatus == null && parsedCategory == null) {
            return KnowledgeEntryFilter.all();
        }
        Set<KnowledgeEntryStatus> statuses =
                parsedStatus == null ? KnowledgeEntryFilter.all().statuses() : Set.of(parsedStatus);
        return parsedCategory == null
                ? new KnowledgeEntryFilter(statuses)
                : KnowledgeEntryFilter.byCategory(statuses, parsedCategory);
    }

    private static Optional<KnowledgeEntryKey> afterEntryKey(String after) {
        return Optional.ofNullable(after).map(value -> {
            try {
                return new KnowledgeEntryKey(value);
            } catch (RuntimeException failure) {
                throw invalidField("after");
            }
        });
    }

    private static Optional<KnowledgeEntryRevision> afterRevision(Long after) {
        if (after == null) {
            return Optional.empty();
        }
        if (after < 1) {
            throw invalidField("after");
        }
        return Optional.of(new KnowledgeEntryRevision(after));
    }

    private static KnowledgeEntryRevision revision(String value) {
        try {
            KnowledgeEntryRevision parsed =
                    new KnowledgeEntryRevision(Long.parseLong(value));
            if (parsed.value() < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            return parsed;
        } catch (RuntimeException failure) {
            throw invalidField("revision");
        }
    }

    private static KnowledgeEntryKey entryKey(String value) {
        try {
            return new KnowledgeEntryKey(value);
        } catch (RuntimeException failure) {
            throw invalidField("entryKey");
        }
    }

    private static KnowledgeCategory category(String value) {
        return parseEnum(value, KnowledgeCategory::valueOf, "category");
    }

    private static <E extends Enum<E>> E parseEnum(
            String value, Function<String, E> parser, String field) {
        try {
            return parser.apply(value);
        } catch (RuntimeException failure) {
            throw invalidField(field);
        }
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("organizationId");
        }
    }

    private static TeamId teamId(String value) {
        try {
            return TeamId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("teamId");
        }
    }

    private static KnowledgeEntryId entryId(String value) {
        try {
            return KnowledgeEntryId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("entryId");
        }
    }

    private static String contentHashEtag(KnowledgeEntryVersion value) {
        return "\"" + value.contentHash().value() + "\"";
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid knowledge entry field",
                Map.of("field", field));
    }

    public record CreateEntryBody(
            @NotBlank String entryKey,
            @NotBlank String category,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 65536) String content) {}

    public record UpdateDraftBody(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 65536) String content,
            String category) {}

    public record EntryListResponse(List<EntryResponse> items, String nextAfter) {
        static EntryListResponse from(
                KnowledgeEntryPage page, Map<KnowledgeEntryId, KnowledgeIndexStatus> statuses) {
            return new EntryListResponse(
                    page.items().stream()
                            .map(item -> EntryResponse.from(
                                    item,
                                    statuses.getOrDefault(item.id(), KnowledgeIndexStatus.PENDING)
                                            .name()))
                            .toList(),
                    page.nextEntryKey().map(key -> key.value()).orElse(null));
        }
    }

    /** Management head view; the head version is the strong ETag source. */
    public record EntryResponse(
            String id,
            String entryKey,
            String category,
            String status,
            String indexStatus,
            Long effectiveRevision,
            long latestRevision,
            DraftResponse draft,
            long version,
            String createdAt,
            String updatedAt,
            String createdBy,
            String updatedBy,
            OriginResponse origin) {
        static EntryResponse from(KnowledgeEntry value, String indexStatus) {
            return new EntryResponse(
                    value.id().value().toString(),
                    value.entryKey().value(),
                    value.category().name(),
                    value.status().name(),
                    indexStatus,
                    value.effectiveRevision().map(revision -> revision.value()).orElse(null),
                    value.latestRevision(),
                    value.draft().map(DraftResponse::from).orElse(null),
                    value.version(),
                    value.audit().createdAt().toString(),
                    value.audit().updatedAt().toString(),
                    value.audit().createdBy().map(id -> id.value().toString()).orElse(null),
                    value.audit().updatedBy().map(id -> id.value().toString()).orElse(null),
                    OriginResponse.from(value));
        }
    }

    /** A02b immutable distillation attribution; null for manually authored entries. */
    public record OriginResponse(String taskExecutionId, int attempt) {

        static OriginResponse from(KnowledgeEntry value) {
            return value.origin()
                    .map(origin -> new OriginResponse(
                            origin.taskExecutionId().toString(), origin.attempt()))
                    .orElse(null);
        }
    }

    public record DraftResponse(String title, String content) {
        static DraftResponse from(io.crewscope.domain.knowledge.KnowledgeDraft value) {
            return new DraftResponse(value.title(), value.content());
        }
    }

    public record VersionListResponse(List<VersionResponse> items, Long nextAfter) {
        static VersionListResponse from(
                KnowledgeEntryVersionPage page,
                Function<KnowledgeEntryRevision, String> indexStatus) {
            return new VersionListResponse(
                    page.items().stream()
                            .map(item -> VersionResponse.from(item, indexStatus.apply(item.revision())))
                            .toList(),
                    page.nextRevision().map(revision -> revision.value()).orElse(null));
        }
    }

    /** Immutable published revision; the content hash is the strong ETag source. */
    public record VersionResponse(
            String entryId,
            long revision,
            Long previousRevision,
            String title,
            String content,
            String contentHash,
            String indexStatus,
            String createdAt,
            String createdBy) {
        static VersionResponse from(KnowledgeEntryVersion value, String indexStatus) {
            return new VersionResponse(
                    value.entryId().value().toString(),
                    value.revision().value(),
                    value.previousRevision().map(revision -> revision.value()).orElse(null),
                    value.title(),
                    value.content(),
                    value.contentHash().value(),
                    indexStatus,
                    value.audit().createdAt().toString(),
                    value.audit().createdBy().map(id -> id.value().toString()).orElse(null));
        }
    }
}
