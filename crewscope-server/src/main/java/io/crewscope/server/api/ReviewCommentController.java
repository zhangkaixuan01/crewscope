package io.crewscope.server.api;

import io.crewscope.application.review.AddReviewLineCommentCommand;
import io.crewscope.application.review.ResolveReviewLineCommentCommand;
import io.crewscope.application.review.ReviewLineCommentCommandService;
import io.crewscope.application.review.ReviewLineCommentPage;
import io.crewscope.application.review.ReviewLineCommentQuery;
import io.crewscope.application.review.ReviewLineCommentQueryService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.review.FindingLocation;
import io.crewscope.domain.review.ReviewCommentAnchor;
import io.crewscope.domain.review.ReviewCommentSide;
import io.crewscope.domain.review.ReviewLineComment;
import io.crewscope.domain.review.ReviewLineCommentId;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
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

/** HTTP boundary for line-level Review comments; Patch content never crosses this DTO boundary. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews/{reviewRequestId}/comments")
public final class ReviewCommentController {
    private final ReviewLineCommentCommandService commands;
    private final ReviewLineCommentQueryService queries;
    private final TeamRequestIdentityResolver identityResolver;

    public ReviewCommentController(ReviewLineCommentCommandService commands,
            ReviewLineCommentQueryService queries, TeamRequestIdentityResolver identityResolver) {
        this.commands = commands;
        this.queries = queries;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public Mono<ResponseEntity<CommentPageResponse>> list(
            @PathVariable String organizationId, @PathVariable String teamId, @PathVariable String taskId,
            @PathVariable String executionId, @PathVariable String reviewRequestId,
            @RequestParam(required = false) String after, @RequestParam(required = false) Integer limit,
            Authentication authentication, ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId, reviewRequestId);
        ReviewLineCommentQuery query = new ReviewLineCommentQuery(
                route.organizationId(), route.teamId(), route.taskId(), route.executionId(),
                route.reviewRequestId(), Optional.ofNullable(after), ApiPagination.limit(limit));
        return access(authentication, route.organizationId(), exchange).map(access -> queries.list(access, query))
                .map(page -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CommentPageResponse.from(page)));
    }

    @PostMapping
    public Mono<ResponseEntity<CommentResponse>> create(
            @PathVariable String organizationId, @PathVariable String teamId, @PathVariable String taskId,
            @PathVariable String executionId, @PathVariable String reviewRequestId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CreateCommentBody body, Authentication authentication, ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId, reviewRequestId);
        return access(authentication, route.organizationId(), exchange)
                .flatMap(access -> blocking(() -> commands.add(
                        access, route.organizationId(), route.teamId(), route.taskId(), route.executionId(),
                        route.reviewRequestId(), body.command(), ApiHeaders.requireIdempotencyKey(key).value())))
                .map(value -> ResponseEntity.status(201)
                        .cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.version()))
                        .body(CommentResponse.from(value)));
    }

    @PatchMapping("/{commentId}")
    public Mono<ResponseEntity<CommentResponse>> edit(
            @PathVariable String organizationId, @PathVariable String teamId, @PathVariable String taskId,
            @PathVariable String executionId, @PathVariable String reviewRequestId, @PathVariable String commentId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody EditCommentBody body, Authentication authentication, ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId, reviewRequestId);
        return access(authentication, route.organizationId(), exchange)
                .flatMap(access -> blocking(() -> commands.edit(
                        access, route.organizationId(), route.teamId(), route.taskId(), route.executionId(),
                        route.reviewRequestId(), id(commentId),
                        ResolveReviewLineCommentCommand.edit(body.content(), ApiHeaders.requireIfMatch(ifMatch)),
                        ApiHeaders.requireIdempotencyKey(key).value())))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.version()))
                        .body(CommentResponse.from(value)));
    }

    @DeleteMapping("/{commentId}")
    public Mono<ResponseEntity<CommentResponse>> delete(
            @PathVariable String organizationId, @PathVariable String teamId, @PathVariable String taskId,
            @PathVariable String executionId, @PathVariable String reviewRequestId, @PathVariable String commentId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication, ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId, reviewRequestId);
        return access(authentication, route.organizationId(), exchange)
                .flatMap(access -> blocking(() -> commands.edit(
                        access, route.organizationId(), route.teamId(), route.taskId(), route.executionId(),
                        route.reviewRequestId(), id(commentId),
                        ResolveReviewLineCommentCommand.delete(ApiHeaders.requireIfMatch(ifMatch)),
                        ApiHeaders.requireIdempotencyKey(key).value())))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.version()))
                        .body(CommentResponse.from(value)));
    }

    private Mono<TeamAccessContext> access(
            Authentication authentication, OrganizationId organization, ServerWebExchange exchange) {
        return identityResolver.resolve(authentication, organization, ApiCorrelationIds.resolve(exchange));
    }
    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }
    private static Route route(String organization, String team, String task, String execution, String request) {
        try {
            return new Route(OrganizationId.from(organization), TeamId.from(team), TaskId.from(task),
                    TaskExecutionId.from(execution), ReviewRequestId.from(request));
        } catch (RuntimeException e) {
            throw new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request", "Request contains an invalid Review comment route",
                    Map.of("field", "route"));
        }
    }
    private static ReviewLineCommentId id(String value) {
        try {
            return ReviewLineCommentId.from(value);
        } catch (RuntimeException e) {
            throw new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request", "Invalid comment id", Map.of("field", "commentId"));
        }
    }

    private record Route(OrganizationId organizationId, TeamId teamId, TaskId taskId,
            TaskExecutionId executionId, ReviewRequestId reviewRequestId) {}

    public record CreateCommentBody(
            @NotNull AnchorBody anchor,
            @NotBlank @Size(max = ReviewLineComment.MAX_CONTENT_LENGTH) String content) {
        AddReviewLineCommentCommand command() { return new AddReviewLineCommentCommand(anchor.anchor(), content); }
    }

    public record AnchorBody(
            @NotBlank String filePath,
            @NotNull ReviewCommentSide side,
            @Positive int lineNumber,
            @NotBlank @Size(max = 1000) String hunkHeader,
            @NotBlank @Size(min = 64, max = 64) String lineContentHash,
            @Positive long diffGeneration) {
        ReviewCommentAnchor anchor() {
            return new ReviewCommentAnchor(
                    new FindingLocation(new DiffPath(filePath), lineNumber, lineNumber), side,
                    hunkHeader, new RuntimeContentHash(lineContentHash), new DiffGeneration(diffGeneration));
        }
    }

    public record EditCommentBody(@NotBlank @Size(max = ReviewLineComment.MAX_CONTENT_LENGTH) String content) {}

    public record CommentPageResponse(List<CommentResponse> items, String nextCursor) {
        static CommentPageResponse from(ReviewLineCommentPage page) {
            return new CommentPageResponse(
                    page.items().stream().map(CommentResponse::from).toList(), page.next().orElse(null));
        }
    }

    public record CommentResponse(
            String id, String reviewRequestId, String taskExecutionId, String filePath, String side,
            int lineNumber, String hunkHeader, String lineContentHash, long diffGeneration, String content,
            String authorPrincipalId, String anchorState, boolean deleted, long version,
            String createdAt, String updatedAt) {
        static CommentResponse from(ReviewLineComment value) {
            var anchor = value.anchor();
            return new CommentResponse(
                    value.id().toString(), value.reviewRequestId().toString(),
                    value.taskExecutionId().toString(), anchor.location().path().value(),
                    anchor.side().name(), anchor.location().startLine(), anchor.hunkHeader(),
                    anchor.lineContentHash().toString(), anchor.diffGeneration().value(),
                    value.deleted() ? "" : value.content(), value.authorPrincipalId().toString(),
                    value.anchorState().name(), value.deleted(), value.version(),
                    value.audit().createdAt().toString(), value.audit().updatedAt().toString());
        }
    }
}
