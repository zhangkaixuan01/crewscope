package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.review.ReviewGateApplicationService;
import io.crewscope.application.review.ReviewRequestApplicationService;
import io.crewscope.application.review.ReviewRequestProjection;
import io.crewscope.application.review.ReviewerExecutionApplicationService;
import io.crewscope.application.review.ReviewerExecutionResult;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** M5-A05 route, ETag, idempotency and safe Review DTO contract tests. */
class ReviewControllerM5A05Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T16:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final ReviewRequestId requestId = ReviewRequestId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
    private final Principal actor = Principal.create(
            PrincipalId.generate(), PrincipalScope.team(organizationId, teamId),
            PrincipalType.USER, Optional.empty(), "Gate reviewer", Optional.empty(),
            PrincipalVisibility.TEAM, NOW);

    private ReviewRequestApplicationService requests;
    private ReviewerExecutionApplicationService reviewer;
    private ReviewGateApplicationService gate;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        requests = mock(ReviewRequestApplicationService.class);
        reviewer = mock(ReviewerExecutionApplicationService.class);
        gate = mock(ReviewGateApplicationService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(
                        new ReviewController(requests, reviewer, gate, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createsReviewFromOnlyTheReviewerPolicyCoordinate() {
        CommandReceipt receipt = receipt(0);
        when(requests.create(any(), any(), any(), any(), any()))
                .thenReturn(CommandExecution.completed(mock(ReviewRequest.class), receipt));

        client.post()
                .uri(base())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a05-create-review")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"reviewerPolicySnapshotId":"%s"}
                        """.formatted(PolicySnapshotId.generate()))
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.domainEventId").isEqualTo(receipt.domainEventId().toString())
                .jsonPath("$.diffArtifactId").doesNotExist()
                .jsonPath("$.reviewerPrincipalId").doesNotExist();
    }

    @Test
    void listsOnlySafeProjectionFields() {
        ReviewRequestProjection projection = new ReviewRequestProjection(
                requestId, scope, taskId, executionId, 1, 2, 4,
                ReviewRequestStatus.COMPLETED, Optional.empty(),
                TaskFactHash.sha256("context"), 3, 1, 0, 2,
                Optional.empty(), Optional.empty(), Optional.empty(), 1, NOW);
        when(requests.list(any(), any(), any(), any(), any()))
                .thenReturn(List.of(projection));

        client.get()
                .uri(base())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items[0].id").isEqualTo(requestId.toString())
                .jsonPath("$.items[0].status").isEqualTo("COMPLETED")
                .jsonPath("$.items[0].contextHash")
                .isEqualTo(projection.contextHash().toString())
                .jsonPath("$.items[0].patch").doesNotExist()
                .jsonPath("$.items[0].prompt").doesNotExist()
                .jsonPath("$.items[0].credential").doesNotExist();
    }

    @Test
    void executesReviewerWithStrongEtagAndMarksReceiptReplay() {
        ReviewRequest request = mock(ReviewRequest.class);
        when(request.id()).thenReturn(requestId);
        when(request.version()).thenReturn(2L);
        when(request.status()).thenReturn(ReviewRequestStatus.COMPLETED);
        CommandReceipt receipt = receipt(1);
        ReviewerExecutionResult result = new ReviewerExecutionResult(
                request, Optional.empty(), receipt, true);
        when(reviewer.execute(any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(result));

        client.post()
                .uri(base() + "/" + requestId + "/execute")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a05-execute-reviewer")
                .header(ApiHeaders.IF_MATCH, "\"1\"")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(ApiHeaders.ETAG, "\"2\"")
                .expectHeader().valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
                .expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED")
                .jsonPath("$.effectiveFindingCount").isEqualTo(0);
    }

    @Test
    void modificationRouteForcesChangesRequestedAndRequiresCommandHeaders() {
        CommandReceipt receipt = receipt(1);
        when(gate.record(any(), any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(CommandExecution.completed(mock(ReviewDecision.class), receipt));

        client.post()
                .uri(base() + "/" + requestId + "/modifications")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a05-request-changes")
                .header(ApiHeaders.IF_MATCH, "\"2\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"rationale\":\"Please cover the null branch\"}")
                .exchange()
                .expectStatus().isAccepted();

        ArgumentCaptor<io.crewscope.application.review.RecordReviewDecisionCommand> command =
                ArgumentCaptor.forClass(
                        io.crewscope.application.review.RecordReviewDecisionCommand.class);
        verify(gate).record(any(), any(), any(), any(), any(), anyLong(), command.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                io.crewscope.domain.review.ReviewDecisionType.CHANGES_REQUESTED,
                command.getValue().type());

        client.post()
                .uri(base() + "/" + requestId + "/execute")
                .exchange()
                .expectStatus().isBadRequest();
    }

    private CommandReceipt receipt(long version) {
        return new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), version, UUID.randomUUID());
    }

    private String base() {
        return "/api/v1/organizations/" + organizationId
                + "/teams/" + teamId
                + "/tasks/" + taskId
                + "/attempts/" + executionId
                + "/reviews";
    }
}
