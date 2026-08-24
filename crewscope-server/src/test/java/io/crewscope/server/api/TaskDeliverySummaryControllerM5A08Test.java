package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.TaskAssociationCursor;
import io.crewscope.application.task.TaskAssociationItem;
import io.crewscope.application.task.TaskAssociationPage;
import io.crewscope.application.task.TaskAssociationService;
import io.crewscope.application.task.TaskAssociationSourceType;
import io.crewscope.application.task.TaskDeliverySummary;
import io.crewscope.application.task.TaskDeliverySummaryService;
import io.crewscope.application.task.TaskListItem;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ConversationTaskLinkOrigin;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.server.observability.TaskDeliveryObservationRecorder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** M5-A08 HTTP evidence for safe DTOs, bound cursors and current Conversation visibility. */
class TaskDeliverySummaryControllerM5A08Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T12:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final TaskId taskId = TaskId.generate();
    private final ConversationId conversationId = ConversationId.generate();
    private final TaskDeliverySummaryService summaries = mock(TaskDeliverySummaryService.class);
    private final TaskAssociationService associations = mock(TaskAssociationService.class);
    private final TaskDeliveryObservationRecorder recorder =
            mock(TaskDeliveryObservationRecorder.class);
    private final TeamAccessContext access = mock(TeamAccessContext.class);
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        when(access.actor()).thenReturn(mock(Principal.class));
        TeamRequestIdentityResolver identity = (authentication, organization, correlationId) ->
                Mono.just(access);
        client = WebTestClient.bindToController(new TaskDeliverySummaryController(
                        summaries, associations, identity, recorder))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void taskSummaryUsesNoStoreAndDoesNotExposeInternalCoordinates() {
        when(summaries.get(access, organizationId, teamId, taskId))
                .thenReturn(summary());

        client.get()
                .uri(taskRoot())
                .header(ApiCorrelationIds.HEADER, "28e70ca3-2209-4125-b133-d7a0bd4c55c2")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.agent.primaryModel.provider").isEqualTo("deepseek")
                .jsonPath("$.review.gateDecision").isEqualTo("APPROVED")
                .jsonPath("$.action.stages[0].externalStatus").isEqualTo("OPEN")
                .jsonPath("$.agent.connectionId").doesNotExist()
                .jsonPath("$.agent.credentialVersion").doesNotExist()
                .jsonPath("$.action.stages[0].externalId").doesNotExist()
                .jsonPath("$.action.stages[0].workerId").doesNotExist()
                .jsonPath("$.action.stages[0].fencingToken").doesNotExist();

        verify(recorder).record(any(), any(), any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void privateConversationVisibilityFailureIsNotConvertedIntoAnEmptyCardPage() {
        when(associations.byConversation(any(), any(), any(), any(), any(), any(Integer.class)))
                .thenThrow(new PolicyDeniedException("Conversation is private"));

        client.get()
                .uri(conversationRoot())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("policy_denied");
    }

    @Test
    void rejectsACursorCapturedFromAnotherTeamBeforeReadingAssociations() {
        String cursor = new TaskAssociationCursorCodec().encode(new TaskAssociationCursor(
                organizationId,
                TeamId.generate(),
                TaskAssociationSourceType.CONVERSATION,
                conversationId.value(),
                NOW,
                taskId.value()));

        client.get()
                .uri(conversationRoot() + "?after=" + cursor)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_cursor");
    }

    @Test
    void conversationCardsRetainTheAssociationKeysetCursor() {
        TaskListItem task = mock(TaskListItem.class);
        TaskExecutionId executionId = TaskExecutionId.generate();
        when(task.id()).thenReturn(taskId);
        when(task.brief()).thenReturn(new TaskBrief("Implement review", List.of("tests pass")));
        when(task.status()).thenReturn(TaskStatus.ACTIVE);
        when(task.currentExecutionId()).thenReturn(Optional.of(executionId));
        when(task.currentAttempt()).thenReturn(Optional.of(2));
        TaskAssociationCursor next = new TaskAssociationCursor(
                organizationId,
                teamId,
                TaskAssociationSourceType.CONVERSATION,
                conversationId.value(),
                NOW,
                taskId.value());
        TaskAssociationPage page = new TaskAssociationPage(
                List.of(new TaskAssociationItem(
                        task, Optional.of(ConversationTaskLinkOrigin.SOURCE), NOW)),
                Optional.of(next));
        when(associations.byConversation(any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(page);
        when(summaries.summarizePage(access, organizationId, teamId, page))
                .thenReturn(List.of(summary()));

        client.get()
                .uri(conversationRoot())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items[0].taskId").isEqualTo(taskId.toString())
                .jsonPath("$.items[0].delivery.review.gateDecision").isEqualTo("APPROVED")
                .jsonPath("$.nextCursor").isEqualTo(new TaskAssociationCursorCodec().encode(next));

        verify(associations).byConversation(
                access,
                organizationId,
                teamId,
                conversationId,
                Optional.empty(),
                20);
    }

    @Test
    void rejectsDeliveryCardPagesAboveTheIndependentReadBudget() {
        client.get()
                .uri(conversationRoot() + "?limit=51")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("limit must be between 1 and 50");

        verifyNoInteractions(associations);
    }

    private TaskDeliverySummary summary() {
        return new TaskDeliverySummary(
                taskId.toString(),
                "RUNNING",
                TaskExecutionId.generate().toString(),
                2,
                new TaskDeliverySummary.AgentSummary(
                        "profile", "coding", 1, 3, "PERSONAL", "DIRECT",
                        new TaskDeliverySummary.ModelSummary(
                                "deepseek", "deepseek-v4-flash", 2),
                        null),
                new TaskDeliverySummary.ReviewSummary(
                        "review", 1, "COMPLETED", 2, 0, 1, "APPROVED", 0),
                new TaskDeliverySummary.ActionSummary(
                        "bundle", 1, "a".repeat(64), "CURRENT", "ACTIVE", "repo",
                        List.of(new TaskDeliverySummary.ActionStageSummary(
                                "CREATE_DRAFT_PULL_REQUEST", "SUCCEEDED", "SUCCEEDED",
                                "OPEN", "PULL_REQUEST", "b".repeat(64)))));
    }

    private String taskRoot() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
                + "/tasks/" + taskId + "/delivery-summary";
    }

    private String conversationRoot() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
                + "/conversations/" + conversationId + "/delivery-cards";
    }
}
