package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.TaskAssociationCursor;
import io.crewscope.application.task.TaskAssociationDetails;
import io.crewscope.application.task.TaskAssociationItem;
import io.crewscope.application.task.TaskAssociationPage;
import io.crewscope.application.task.TaskAssociationService;
import io.crewscope.application.task.TaskAssociationSourceType;
import io.crewscope.application.task.TaskConversationAssociation;
import io.crewscope.application.task.TaskConversationAssociationPage;
import io.crewscope.application.task.TaskListItem;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.ConversationStatus;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ConversationTaskLinkOrigin;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** HTTP, deep-link and disclosure contract for M3-A06 Task associations. */
class TaskAssociationControllerM3A06Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-15T10:30:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkspaceId workspaceId = WorkspaceId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemId workItemId = WorkItemId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, workspaceId, projectId);
    private TaskAssociationService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(TaskAssociationService.class);
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, teamId),
                PrincipalType.USER,
                Optional.empty(),
                "Member",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(
                        new TaskAssociationController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsMultipleHistoricalTasksWithOpaqueCursorAndObjectDeepLinks() {
        TaskAssociationItem active = taskItem(TaskStatus.ACTIVE, Optional.empty());
        TaskAssociationItem cancelled = taskItem(TaskStatus.CANCELLED, Optional.empty());
        TaskAssociationCursor next = new TaskAssociationCursor(
                organizationId,
                teamId,
                TaskAssociationSourceType.WORK_ITEM,
                workItemId.value(),
                cancelled.associatedAt(),
                cancelled.task().id().value());
        when(service.byWorkItem(any(), any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(new TaskAssociationPage(
                        List.of(active, cancelled), Optional.of(next)));

        client.get()
                .uri(workItemRoot() + "?limit=2")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.items[0].origin").isEqualTo("WORK_ITEM_ROOT")
                .jsonPath("$.items[1].task.status").isEqualTo("CANCELLED")
                .jsonPath("$.items[0].task.href").value(value ->
                        org.junit.jupiter.api.Assertions.assertTrue(
                                value.toString().startsWith("/work?team=")))
                .jsonPath("$.nextCursor").isNotEmpty();
    }

    @Test
    void exposesConversationLinkOriginWithoutInventingTaskState() {
        ConversationId conversationId = ConversationId.generate();
        PrincipalId owner = PrincipalId.generate();
        TaskListItem task = new TaskListItem(
                TaskId.generate(),
                scope,
                workItemId,
                new TaskBrief("Execute release", List.of("Verified")),
                TaskStatus.WAITING,
                Optional.of(TaskExecutionId.generate()),
                Optional.of(2),
                Optional.of(TaskExecutionStatus.WAITING),
                Optional.of(TaskExecutionWaitReason.CONFIRMATION),
                Optional.of(owner),
                1,
                AuditMetadata.createdBy(owner, NOW));
        TaskAssociationItem item = new TaskAssociationItem(
                task, Optional.of(ConversationTaskLinkOrigin.SOURCE), NOW);
        when(service.byConversation(any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(new TaskAssociationPage(List.of(item), Optional.empty()));

        client.get()
                .uri(conversationRoot(conversationId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].origin").isEqualTo("SOURCE")
                .jsonPath("$.items[0].task.status").isEqualTo("WAITING")
                .jsonPath("$.items[0].task.currentWaitingReason")
                .isEqualTo("CONFIRMATION")
                .jsonPath("$.items[0].task.ownerPrincipalId").isEqualTo(owner.toString())
                .jsonPath("$.nextCursor").doesNotExist();
    }

    @Test
    void returnsTaskWorkItemAndOnlyServiceAuthorizedConversationReferences() {
        Task task = mock(Task.class);
        TaskId taskId = TaskId.generate();
        WorkItem workItem = mock(WorkItem.class);
        ConversationId conversationId = ConversationId.generate();
        when(task.id()).thenReturn(taskId);
        when(task.scope()).thenReturn(scope);
        when(task.workItemId()).thenReturn(workItemId);
        when(task.status()).thenReturn(TaskStatus.ACTIVE);
        when(task.brief()).thenReturn(new TaskBrief("Execute release", List.of("Verified")));
        when(workItem.id()).thenReturn(workItemId);
        when(workItem.scope()).thenReturn(scope);
        when(workItem.key()).thenReturn(new WorkItemKey("CRW-42"));
        when(workItem.title()).thenReturn("Release CrewScope");
        when(workItem.status()).thenReturn(WorkItemStatus.IN_PROGRESS);
        TaskConversationAssociation conversation = new TaskConversationAssociation(
                conversationId,
                new ConversationScope(organizationId, teamId, workspaceId),
                "Release discussion",
                ConversationVisibility.PRIVATE,
                ConversationStatus.ACTIVE,
                ConversationTaskLinkOrigin.SOURCE,
                NOW);
        when(service.byTask(any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(new TaskAssociationDetails(
                        task,
                        workItem,
                        new TaskConversationAssociationPage(
                                List.of(conversation), Optional.empty())));

        client.get()
                .uri(taskRoot(taskId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.task.id").isEqualTo(taskId.toString())
                .jsonPath("$.workItem.key").isEqualTo("CRW-42")
                .jsonPath("$.workItem.href").value(value ->
                        org.junit.jupiter.api.Assertions.assertTrue(
                                value.toString().contains("focus=CRW-42")))
                .jsonPath("$.conversations.items[0].id")
                .isEqualTo(conversationId.toString())
                .jsonPath("$.conversations.items[0].href").value(value ->
                        org.junit.jupiter.api.Assertions.assertTrue(
                                value.toString().startsWith("/conversation?team=")));
    }

    @Test
    void rejectsAnAssociationCursorReplayedOnAnotherSource() {
        TaskAssociationCursorCodec codec = new TaskAssociationCursorCodec();
        String cursor = codec.encode(new TaskAssociationCursor(
                organizationId,
                teamId,
                TaskAssociationSourceType.WORK_ITEM,
                WorkItemId.generate().value(),
                NOW,
                UUID.randomUUID()));

        client.get()
                .uri(workItemRoot() + "?after=" + cursor)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_cursor");
    }

    private TaskAssociationItem taskItem(
            TaskStatus status, Optional<ConversationTaskLinkOrigin> origin) {
        TaskListItem task = new TaskListItem(
                TaskId.generate(),
                scope,
                workItemId,
                new TaskBrief("Execute release", List.of("Verified")),
                status,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                1,
                AuditMetadata.createdBy(PrincipalId.generate(), NOW));
        return new TaskAssociationItem(task, origin, NOW);
    }

    private String workItemRoot() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
                + "/work-projects/" + projectId + "/work-items/" + workItemId + "/tasks";
    }

    private String conversationRoot(ConversationId conversationId) {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
                + "/conversations/" + conversationId + "/tasks";
    }

    private String taskRoot(TaskId taskId) {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
                + "/tasks/" + taskId + "/associations";
    }
}
