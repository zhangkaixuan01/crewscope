package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.TaskDetails;
import io.crewscope.application.task.TaskListCursor;
import io.crewscope.application.task.TaskListItem;
import io.crewscope.application.task.TaskListPage;
import io.crewscope.application.task.TaskQueryService;
import io.crewscope.application.task.TaskRuntimeFacts;
import io.crewscope.application.team.TeamAccessContext;
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
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPriority;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.task.TaskSourceType;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** HTTP and disclosure contract for M3-A02 Task query APIs. */
class TaskQueryControllerTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-15T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, WorkspaceId.generate(), projectId);
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, teamId),
            PrincipalType.USER,
            Optional.empty(),
            "Member",
            Optional.empty(),
            PrincipalVisibility.TEAM,
            NOW);

    private TaskQueryService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(TaskQueryService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(new TaskQueryController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsStableTaskSummariesAndReturnsAnOpaqueNextCursor() {
        TaskId taskId = TaskId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        TaskListItem item = new TaskListItem(
                taskId,
                scope,
                WorkItemId.generate(),
                new TaskBrief("Ship Task query API", List.of("Stable cursor")),
                TaskStatus.ACTIVE,
                Optional.of(executionId),
                Optional.of(1),
                Optional.of(TaskExecutionStatus.WAITING),
                Optional.of(io.crewscope.domain.task.TaskExecutionWaitReason.RUNTIME),
                3,
                AuditMetadata.createdBy(actor.id(), NOW));
        when(service.list(any(), any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(new TaskListPage(List.of(item), Optional.of(item.cursor())));

        client.get()
                .uri(root() + "?projectId=" + projectId + "&status=ACTIVE&limit=20")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items[0].id").isEqualTo(taskId.toString())
                .jsonPath("$.items[0].currentExecutionStatus").isEqualTo("WAITING")
                .jsonPath("$.items[0].currentWaitingReason").isEqualTo("RUNTIME")
                .jsonPath("$.nextCursor").isNotEmpty();
    }

    @Test
    void returnsTaskDetailsAndOrderedAttemptSummariesWithStrongEtags() {
        Task task = task();
        TaskExecution execution = execution(task.id());
        TaskDetails details = new TaskDetails(task, List.of(execution));
        when(service.get(any(), any(), any(), any())).thenReturn(details);
        when(service.attempts(any(), any(), any(), any()))
                .thenReturn(List.of(execution));

        client.get()
                .uri(root() + "/" + task.id())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"4\"")
                .expectBody()
                .jsonPath("$.objective").isEqualTo("Inspect runtime history")
                .jsonPath("$.source.type").isEqualTo("WORK_ITEM")
                .jsonPath("$.attempts[0].status").isEqualTo("READY");

        client.get()
                .uri(root() + "/" + task.id() + "/attempts")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].attempt").isEqualTo(1)
                .jsonPath("$[0].status").isEqualTo("READY");
    }

    @Test
    void runtimeFactsNeverSerializeTokensFencingOrAgentScopeStateCoordinates() {
        Task task = task();
        TaskExecution execution = execution(task.id());
        TaskRuntimeFacts facts = new TaskRuntimeFacts(
                task,
                execution,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        when(service.runtimeFacts(any(), any(), any(), any(), any())).thenReturn(facts);

        client.get()
                .uri(root() + "/" + task.id() + "/attempts/" + execution.id()
                        + "/runtime-facts")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"2\"")
                .expectBody(String.class)
                .value(body -> {
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"execution\""));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("claimToken"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("fencingToken"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("agentScopeUserId"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("stateReference"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("contentHash"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("interruptToken"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("responseHash"));
                });
    }

    @Test
    void rejectsMalformedCursorsAndRouteIdentifiersBeforeCallingTheService() {
        client.get()
                .uri(root() + "?after=not+canonical")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_cursor");

        client.get()
                .uri(root() + "/not-a-task")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    }

    private Task task() {
        Task task = mock(Task.class);
        TaskId id = new TaskId(UUID.nameUUIDFromBytes(
                "m3-a02-task".getBytes(StandardCharsets.UTF_8)));
        TaskSource source = mock(TaskSource.class);
        TaskResponsibilitySnapshot snapshot = mock(TaskResponsibilitySnapshot.class);
        when(task.id()).thenReturn(id);
        when(task.scope()).thenReturn(scope);
        when(task.workItemId()).thenReturn(WorkItemId.generate());
        when(task.brief()).thenReturn(new TaskBrief(
                "Inspect runtime history", List.of("Hide internal state")));
        when(source.type()).thenReturn(TaskSourceType.WORK_ITEM);
        when(source.workItemVersion()).thenReturn(7L);
        when(source.conversationId()).thenReturn(Optional.empty());
        when(source.inputReference()).thenReturn(Optional.empty());
        when(task.source()).thenReturn(source);
        when(snapshot.entries()).thenReturn(List.of());
        when(snapshot.capturedAt()).thenReturn(NOW);
        when(task.responsibilitySnapshot()).thenReturn(snapshot);
        when(task.status()).thenReturn(TaskStatus.ACTIVE);
        when(task.currentExecutionId()).thenReturn(Optional.empty());
        when(task.cancellation()).thenReturn(Optional.empty());
        when(task.version()).thenReturn(4L);
        when(task.audit()).thenReturn(AuditMetadata.createdBy(actor.id(), NOW));
        return task;
    }

    private TaskExecution execution(TaskId taskId) {
        TaskExecution execution = mock(TaskExecution.class);
        when(execution.id()).thenReturn(TaskExecutionId.generate());
        when(execution.taskId()).thenReturn(taskId);
        when(execution.scope()).thenReturn(scope);
        when(execution.attempt()).thenReturn(1);
        when(execution.maxAttempts()).thenReturn(3);
        when(execution.parentExecutionId()).thenReturn(Optional.empty());
        when(execution.priority()).thenReturn(TaskExecutionPriority.NORMAL);
        when(execution.notBefore()).thenReturn(NOW);
        when(execution.status()).thenReturn(TaskExecutionStatus.READY);
        when(execution.waiting()).thenReturn(Optional.empty());
        when(execution.controlRequest()).thenReturn(Optional.empty());
        when(execution.terminal()).thenReturn(Optional.empty());
        when(execution.planningContext()).thenReturn(Optional.empty());
        when(execution.version()).thenReturn(2L);
        when(execution.audit()).thenReturn(AuditMetadata.createdBy(actor.id(), NOW));
        return execution;
    }

    private String root() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId + "/tasks";
    }
}
