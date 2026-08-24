package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.review.CreateReviewRequestCommand;
import io.crewscope.application.review.RecordReviewDecisionCommand;
import io.crewscope.application.review.ReviewGateApplicationService;
import io.crewscope.application.review.ReviewRequestApplicationService;
import io.crewscope.application.review.ReviewRequestProjection;
import io.crewscope.application.review.ReviewWorkbenchView;
import io.crewscope.application.review.ReviewerExecutionApplicationService;
import io.crewscope.application.review.ReviewerExecutionResult;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.review.FindingEvidence;
import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewFinding;
import io.crewscope.domain.review.ReviewModificationRound;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Member-authorized Review workbench, Reviewer execution and human Gate HTTP boundary. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews")
public final class ReviewController {

    private final ReviewRequestApplicationService requests;
    private final ReviewerExecutionApplicationService reviewer;
    private final ReviewGateApplicationService gate;
    private final TeamRequestIdentityResolver identityResolver;

    public ReviewController(
            ReviewRequestApplicationService requests,
            ReviewerExecutionApplicationService reviewer,
            ReviewGateApplicationService gate,
            TeamRequestIdentityResolver identityResolver) {
        this.requests = requests;
        this.reviewer = reviewer;
        this.gate = gate;
        this.identityResolver = identityResolver;
    }

    @PostMapping
    public Mono<ResponseEntity<CommandReceiptResponse>> create(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CreateReviewBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        CreateReviewRequestCommand command = new CreateReviewRequestCommand(
                policySnapshotId(body.reviewerPolicySnapshotId()));
        return command(authentication, route.organizationId(), key, exchange, context ->
                requests.create(
                        context, route.teamId(), route.taskId(), route.executionId(), command));
    }

    @PostMapping("/{reviewRequestId}/re-review")
    public Mono<ResponseEntity<CommandReceiptResponse>> reReview(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @PathVariable String reviewRequestId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CreateReviewBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        ReviewRequestId predecessor = reviewRequestId(reviewRequestId);
        CreateReviewRequestCommand command = new CreateReviewRequestCommand(
                policySnapshotId(body.reviewerPolicySnapshotId()));
        return command(authentication, route.organizationId(), key, exchange, context ->
                requests.reReview(
                        context, route.teamId(), route.taskId(), route.executionId(),
                        predecessor, command));
    }

    @GetMapping
    public Mono<ResponseEntity<ReviewListResponse>> list(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        return query(authentication, route.organizationId(), exchange, access ->
                requests.list(
                        access, route.organizationId(), route.teamId(),
                        route.taskId(), route.executionId()))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(ReviewListResponse.from(values)));
    }

    @GetMapping("/{reviewRequestId}")
    public Mono<ResponseEntity<ReviewResponse>> get(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @PathVariable String reviewRequestId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        ReviewRequestId requestId = reviewRequestId(reviewRequestId);
        return query(authentication, route.organizationId(), exchange, access -> requests.get(
                        access, route.organizationId(), route.teamId(), route.taskId(),
                        route.executionId(), requestId))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.request().version()))
                        .body(ReviewResponse.from(value)));
    }

    @PostMapping("/{reviewRequestId}/execute")
    public Mono<ResponseEntity<ReviewerExecutionResponse>> execute(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @PathVariable String reviewRequestId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        ReviewRequestId requestId = reviewRequestId(reviewRequestId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> reviewer.execute(
                                new TeamCommandContext(
                                        access, idempotencyKey, correlationId, Optional.empty()),
                                route.teamId(), route.taskId(), route.executionId(), requestId,
                                expectedVersion))
                        .flatMap(Mono::fromCompletionStage))
                .map(ReviewController::executionResponse);
    }

    @PostMapping("/{reviewRequestId}/decisions")
    public Mono<ResponseEntity<CommandReceiptResponse>> decision(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @PathVariable String reviewRequestId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody DecisionBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        ReviewRequestId requestId = reviewRequestId(reviewRequestId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        RecordReviewDecisionCommand decision = new RecordReviewDecisionCommand(
                decisionType(body.type()), body.rationale());
        return command(authentication, route.organizationId(), key, exchange, context ->
                gate.record(
                        context, route.teamId(), route.taskId(), route.executionId(), requestId,
                        expectedVersion, decision));
    }

    @PostMapping("/{reviewRequestId}/modifications")
    public Mono<ResponseEntity<CommandReceiptResponse>> modifications(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @PathVariable String reviewRequestId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ModificationBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        ReviewRequestId requestId = reviewRequestId(reviewRequestId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        return command(authentication, route.organizationId(), key, exchange, context ->
                gate.record(
                        context, route.teamId(), route.taskId(), route.executionId(), requestId,
                        expectedVersion,
                        new RecordReviewDecisionCommand(
                                ReviewDecisionType.CHANGES_REQUESTED, body.rationale())));
    }

    private <T> Mono<ResponseEntity<CommandReceiptResponse>> command(
            Authentication authentication,
            OrganizationId organizationId,
            String key,
            ServerWebExchange exchange,
            Function<TeamCommandContext, CommandExecution<T>> action) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
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
        return identityResolver.resolve(
                        authentication, organizationId, ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> action.apply(access)));
    }

    private static ResponseEntity<ReviewerExecutionResponse> executionResponse(
            ReviewerExecutionResult result) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(ApiHeaders.versionEtag(result.request().version()));
        if (result.replayed()) {
            response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
        }
        return response.body(ReviewerExecutionResponse.from(result));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static Route route(String organization, String team, String task, String execution) {
        try {
            return new Route(
                    OrganizationId.from(organization), TeamId.from(team),
                    TaskId.from(task), TaskExecutionId.from(execution));
        } catch (RuntimeException failure) {
            throw invalidField("route");
        }
    }

    private static ReviewRequestId reviewRequestId(String value) {
        try {
            return ReviewRequestId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("reviewRequestId");
        }
    }

    private static PolicySnapshotId policySnapshotId(String value) {
        try {
            return PolicySnapshotId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("reviewerPolicySnapshotId");
        }
    }

    private static ReviewDecisionType decisionType(String value) {
        try {
            return ReviewDecisionType.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalidField("type");
        }
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid Review field",
                Map.of("field", field));
    }

    public record CreateReviewBody(@NotBlank String reviewerPolicySnapshotId) {}

    public record DecisionBody(
            @NotBlank String type,
            @NotBlank @Size(max = 4_000) String rationale) {}

    public record ModificationBody(@NotBlank @Size(max = 4_000) String rationale) {}

    private record Route(
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {}

    public record ReviewListResponse(List<ReviewSummaryResponse> items) {
        static ReviewListResponse from(List<ReviewRequestProjection> values) {
            return new ReviewListResponse(
                    values.stream().map(ReviewSummaryResponse::from).toList());
        }
    }

    public record ReviewSummaryResponse(
            String id,
            long revision,
            long version,
            String status,
            String invalidationReason,
            String contextHash,
            int findingCount,
            int blockerCount,
            int highCount,
            String latestDecisionType,
            long modificationRound) {
        static ReviewSummaryResponse from(ReviewRequestProjection value) {
            return new ReviewSummaryResponse(
                    value.reviewRequestId().toString(), value.requestRevision(),
                    value.requestVersion(), value.status().name(),
                    value.invalidationReason().map(Enum::name).orElse(null),
                    value.contextHash().toString(), value.findingCount(), value.blockerCount(),
                    value.highCount(), value.latestDecisionType().map(Enum::name).orElse(null),
                    value.modificationRound());
        }
    }

    /** Public Review DTO excludes Patch text, Prompt, credentials and raw model/tool output. */
    public record ReviewResponse(
            String id,
            long revision,
            long version,
            String status,
            String invalidationReason,
            String reviewerRelationship,
            String reviewerAgentProfileId,
            String contextPackageId,
            String contextHash,
            String diffArtifactId,
            String diffArtifactHash,
            String baselineCommit,
            String deliveryCommit,
            List<String> changedPaths,
            String testEvidenceId,
            String testEvidenceHash,
            List<FindingResponse> findings,
            List<DecisionResponse> decisions,
            List<ModificationRoundResponse> modificationRounds) {
        static ReviewResponse from(ReviewWorkbenchView view) {
            var value = view.request();
            return new ReviewResponse(
                    value.id().toString(), value.revision(), value.version(),
                    value.status().name(),
                    value.invalidationReason().map(Enum::name).orElse(null),
                    value.reviewer().relationship().name(),
                    value.reviewer().agentProfileId().toString(),
                    value.contextPackage().id().toString(),
                    value.contextPackage().contextHash().toString(),
                    value.diff().artifact().id().toString(),
                    value.diff().artifact().finalHash().toString(),
                    value.diff().baselineCommit().value(),
                    value.diff().deliveryCommit().value(),
                    value.diff().changedPaths().stream().map(path -> path.value()).toList(),
                    value.testEvidence().id().toString(),
                    value.testEvidence().evidenceHash().toString(),
                    view.findings().stream().map(FindingResponse::from).toList(),
                    view.decisions().stream().map(DecisionResponse::from).toList(),
                    view.modificationRounds().stream()
                            .map(ModificationRoundResponse::from).toList());
        }
    }

    public record FindingResponse(
            String id,
            String severity,
            String category,
            String title,
            String claim,
            String suggestedFix,
            String relationship,
            String fingerprint,
            List<EvidenceResponse> evidence) {
        static FindingResponse from(ReviewFinding value) {
            return new FindingResponse(
                    value.id().toString(), value.severity().name(), value.category().name(),
                    value.title(), value.claim(), value.suggestedFix(),
                    value.reviewerRelationship().name(), value.fingerprint().toString(),
                    value.evidence().stream().map(EvidenceResponse::from).toList());
        }
    }

    public record EvidenceResponse(
            String path,
            int startLine,
            int endLine,
            int acceptanceCriterionIndex) {
        static EvidenceResponse from(FindingEvidence value) {
            return new EvidenceResponse(
                    value.location().path().value(), value.location().startLine(),
                    value.location().endLine(), value.acceptanceCriterionIndex());
        }
    }

    public record DecisionResponse(
            String id,
            long revision,
            String type,
            String rationale,
            String reviewerMemberId,
            String eligibilityMode,
            String decidedAt) {
        static DecisionResponse from(ReviewDecision value) {
            return new DecisionResponse(
                    value.id().toString(), value.revision(), value.type().name(),
                    value.rationale(), value.reviewerMemberId().toString(),
                    value.eligibility().mode().name(), value.audit().createdAt().toString());
        }
    }

    public record ModificationRoundResponse(
            String id,
            long roundNumber,
            String sourceReviewRequestId,
            String triggerDecisionId,
            String createdAt) {
        static ModificationRoundResponse from(ReviewModificationRound value) {
            return new ModificationRoundResponse(
                    value.id().toString(), value.roundNumber(),
                    value.sourceRequest().id().toString(),
                    value.triggerDecision().id().toString(),
                    value.audit().createdAt().toString());
        }
    }

    public record ReviewerExecutionResponse(
            CommandReceiptResponse receipt,
            String reviewRequestId,
            long reviewRequestVersion,
            String status,
            int effectiveFindingCount,
            int insertedFindingCount,
            int duplicateObservationCount) {
        static ReviewerExecutionResponse from(ReviewerExecutionResult value) {
            return new ReviewerExecutionResponse(
                    CommandReceiptResponse.from(value.receipt()),
                    value.request().id().toString(), value.request().version(),
                    value.request().status().name(),
                    value.findings().map(result -> result.effectiveFindings().size()).orElse(0),
                    value.findings().map(result -> result.insertedFindings().size()).orElse(0),
                    value.findings().map(result -> result.duplicateObservations().size()).orElse(0));
        }
    }
}
