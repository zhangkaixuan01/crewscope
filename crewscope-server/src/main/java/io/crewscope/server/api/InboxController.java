package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.inbox.ChangeInboxDispositionCommand;
import io.crewscope.application.inbox.InboxApplicationService;
import io.crewscope.application.inbox.InboxCursor;
import io.crewscope.application.inbox.InboxDispositionCommandService;
import io.crewscope.application.inbox.InboxFilter;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.inbox.InboxDisposition;
import io.crewscope.domain.inbox.InboxDispositionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Member-only HTTP boundary for the five-view Inbox and strong-ETag dispositions. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/inbox")
public final class InboxController {

    private final InboxApplicationService queries;
    private final InboxDispositionCommandService commands;
    private final TeamRequestIdentityResolver identityResolver;
    private final InboxCursorCodec cursorCodec = new InboxCursorCodec();

    public InboxController(
            InboxApplicationService queries,
            InboxDispositionCommandService commands,
            TeamRequestIdentityResolver identityResolver) {
        this.queries = queries;
        this.commands = commands;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public Mono<ResponseEntity<InboxPageResponse>> list(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) List<String> itemTypes,
            @RequestParam(required = false) List<String> sourceStatuses,
            @RequestParam(required = false) List<String> dispositionStatuses,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        InboxApiSupport.Route route = InboxApiSupport.route(organizationId, teamId);
        InboxFilter filter = InboxApiSupport.filter(
                itemTypes, sourceStatuses, dispositionStatuses);
        return resolve(authentication, route, exchange).flatMap(access -> {
            if (after != null && !after.isBlank()) {
                // Membership precedes Cursor decoding so the token cannot become an oracle.
                queries.requireAccess(access, route.organizationId(), route.teamId());
            }
            Optional<InboxCursor> cursor = Optional.ofNullable(after)
                    .filter(value -> !value.isBlank())
                    .map(value -> cursorCodec.decode(
                            value, route.organizationId(), route.teamId(), filter));
            return blocking(() -> queries.list(
                    access,
                    route.organizationId(),
                    route.teamId(),
                    filter,
                    cursor,
                    ApiPagination.limit(limit)));
        }).map(page -> noStore(InboxPageResponse.from(
                page, cursorCodec, route.organizationId(), route.teamId(), filter)));
    }

    @GetMapping("/counts")
    public Mono<ResponseEntity<InboxCountsResponse>> counts(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            Authentication authentication,
            ServerWebExchange exchange) {
        InboxApiSupport.Route route = InboxApiSupport.route(organizationId, teamId);
        return resolve(authentication, route, exchange)
                .flatMap(access -> blocking(() -> queries.counts(
                        access, route.organizationId(), route.teamId())))
                .map(value -> noStore(InboxCountsResponse.from(value)));
    }

    @GetMapping("/{inboxItemId}")
    public Mono<ResponseEntity<InboxItemResponse>> detail(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String inboxItemId,
            Authentication authentication,
            ServerWebExchange exchange) {
        InboxApiSupport.Route route = InboxApiSupport.route(organizationId, teamId);
        var itemId = InboxApiSupport.itemId(inboxItemId);
        return resolve(authentication, route, exchange)
                .flatMap(access -> blocking(() -> queries.detail(
                        access, route.organizationId(), route.teamId(), itemId)))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.dispositionVersion()))
                        .body(InboxItemResponse.from(value)));
    }

    @GetMapping("/{inboxItemId}/target")
    public Mono<ResponseEntity<InboxTargetResponse>> target(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String inboxItemId,
            Authentication authentication,
            ServerWebExchange exchange) {
        InboxApiSupport.Route route = InboxApiSupport.route(organizationId, teamId);
        var itemId = InboxApiSupport.itemId(inboxItemId);
        return resolve(authentication, route, exchange)
                .flatMap(access -> blocking(() -> queries.target(
                        access, route.organizationId(), route.teamId(), itemId)))
                .map(value -> noStore(InboxTargetResponse.from(value)));
    }

    @PutMapping("/{inboxItemId}/disposition")
    public Mono<ResponseEntity<CommandReceiptResponse>> changeDisposition(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String inboxItemId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ChangeDispositionRequest body,
            Authentication authentication,
            ServerWebExchange exchange) {
        InboxApiSupport.Route route = InboxApiSupport.route(organizationId, teamId);
        var itemId = InboxApiSupport.itemId(inboxItemId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        InboxDispositionStatus status = InboxApiSupport.disposition(body.status());
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> commands.change(
                        new TeamCommandContext(
                                access, idempotencyKey, correlationId, Optional.empty()),
                        route.organizationId(),
                        route.teamId(),
                        itemId,
                        new ChangeInboxDispositionCommand(status, expectedVersion))))
                .map(InboxController::accepted);
    }

    private Mono<TeamAccessContext> resolve(
            Authentication authentication,
            InboxApiSupport.Route route,
            ServerWebExchange exchange) {
        return identityResolver.resolve(
                authentication, route.organizationId(), ApiCorrelationIds.resolve(exchange));
    }

    private static ResponseEntity<CommandReceiptResponse> accepted(
            CommandExecution<InboxDisposition> execution) {
        ResponseEntity.BodyBuilder response = ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .eTag(ApiHeaders.versionEtag(execution.receipt().committedVersion()));
        if (execution.replayed()) {
            response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
        }
        return response.body(CommandReceiptResponse.from(execution.receipt()));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    public record ChangeDispositionRequest(@NotBlank String status) {

        /** Keeps the disposition command body closed as new server fields are introduced. */
        @JsonAnySetter
        void rejectUnknownProperty(String ignoredName, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported Inbox disposition property");
        }
    }
}
