package io.crewscope.server.api;

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
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Visibility-aware HTTP boundary for all WorkItem/Conversation/Task association directions. */
@RestController
public final class TaskAssociationController {

    private final TaskAssociationService service;
    private final TeamRequestIdentityResolver identityResolver;
    private final TaskAssociationCursorCodec cursorCodec = new TaskAssociationCursorCodec();

    public TaskAssociationController(
            TaskAssociationService service, TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/tasks")
    public Mono<ResponseEntity<TaskAssociationPageResponse>> byWorkItem(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            @PathVariable String workItemId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        WorkProjectId project = projectId(projectId);
        WorkItemId workItem = workItemId(workItemId);
        Optional<TaskAssociationCursor> cursor = cursor(
                after,
                route,
                TaskAssociationSourceType.WORK_ITEM,
                workItem.value());
        return query(authentication, route.organizationId(), exchange, access ->
                        service.byWorkItem(
                                access,
                                route.organizationId(),
                                route.teamId(),
                                project,
                                workItem,
                                cursor,
                                ApiPagination.limit(limit)))
                .map(page -> pageResponse(page, route));
    }

    @GetMapping(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/tasks")
    public Mono<ResponseEntity<TaskAssociationPageResponse>> byConversation(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String conversationId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        ConversationId conversation = conversationId(conversationId);
        Optional<TaskAssociationCursor> cursor = cursor(
                after,
                route,
                TaskAssociationSourceType.CONVERSATION,
                conversation.value());
        return query(authentication, route.organizationId(), exchange, access ->
                        service.byConversation(
                                access,
                                route.organizationId(),
                                route.teamId(),
                                conversation,
                                cursor,
                                ApiPagination.limit(limit)))
                .map(page -> pageResponse(page, route));
    }

    @GetMapping(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/associations")
    public Mono<ResponseEntity<TaskAssociationDetailsResponse>> byTask(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        TaskId task = taskId(taskId);
        Optional<TaskAssociationCursor> cursor = cursor(
                after, route, TaskAssociationSourceType.TASK, task.value());
        return query(authentication, route.organizationId(), exchange, access ->
                        service.byTask(
                                access,
                                route.organizationId(),
                                route.teamId(),
                                task,
                                cursor,
                                ApiPagination.limit(limit)))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(TaskAssociationDetailsResponse.from(value, cursorCodec)));
    }

    private ResponseEntity<TaskAssociationPageResponse> pageResponse(
            TaskAssociationPage page, Route route) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TaskAssociationPageResponse.from(page, route, cursorCodec));
    }

    private Optional<TaskAssociationCursor> cursor(
            String token,
            Route route,
            TaskAssociationSourceType sourceType,
            UUID sourceId) {
        return Optional.ofNullable(token).map(value -> cursorCodec.decode(
                value, route.organizationId(), route.teamId(), sourceType, sourceId));
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

    private static Route route(String organizationId, String teamId) {
        try {
            return new Route(OrganizationId.from(organizationId), TeamId.from(teamId));
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("route");
        }
    }

    private static WorkProjectId projectId(String value) {
        try {
            return WorkProjectId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("projectId");
        }
    }

    private static WorkItemId workItemId(String value) {
        try {
            return WorkItemId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("workItemId");
        }
    }

    private static ConversationId conversationId(String value) {
        try {
            return ConversationId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("conversationId");
        }
    }

    private static TaskId taskId(String value) {
        try {
            return TaskId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("taskId");
        }
    }

    private static ApiRequestException invalidIdentifier(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", field));
    }

    private static String taskHref(TaskListItem task) {
        return UriComponentsBuilder.fromPath("/work")
                .queryParam("team", task.scope().teamId())
                .queryParam("project", task.scope().projectId())
                .queryParam("workItem", task.workItemId())
                .queryParam("task", task.id())
                .build()
                .encode()
                .toUriString();
    }

    private static String taskHref(io.crewscope.domain.task.Task task) {
        return UriComponentsBuilder.fromPath("/work")
                .queryParam("team", task.scope().teamId())
                .queryParam("project", task.scope().projectId())
                .queryParam("workItem", task.workItemId())
                .queryParam("task", task.id())
                .build()
                .encode()
                .toUriString();
    }

    private static String workItemHref(WorkItem workItem) {
        return UriComponentsBuilder.fromPath("/work")
                .queryParam("team", workItem.scope().teamId())
                .queryParam("project", workItem.scope().projectId())
                .queryParam("workItem", workItem.id())
                .queryParam("focus", workItem.key().value())
                .build()
                .encode()
                .toUriString();
    }

    private static String conversationHref(TaskConversationAssociation conversation) {
        return UriComponentsBuilder.fromPath("/conversation")
                .queryParam("team", conversation.scope().teamId())
                .queryParam("conversation", conversation.id())
                .build()
                .encode()
                .toUriString();
    }

    private record Route(OrganizationId organizationId, TeamId teamId) {}

    public record TaskAssociationPageResponse(
            List<TaskAssociationItemResponse> items, String nextCursor) {

        static TaskAssociationPageResponse from(
                TaskAssociationPage page,
                Route route,
                TaskAssociationCursorCodec codec) {
            return new TaskAssociationPageResponse(
                    page.items().stream()
                            .map(value -> TaskAssociationItemResponse.from(value, route))
                            .toList(),
                    page.nextCursor().map(codec::encode).orElse(null));
        }
    }

    public record TaskAssociationItemResponse(
            String origin, Instant associatedAt, TaskAssociationTaskResponse task) {

        static TaskAssociationItemResponse from(TaskAssociationItem value, Route route) {
            TaskListItem task = value.task();
            if (!task.scope().organizationId().equals(route.organizationId())
                    || !task.scope().teamId().equals(route.teamId())) {
                throw new IllegalArgumentException("Task response must match its route");
            }
            return new TaskAssociationItemResponse(
                    value.conversationOrigin().map(Enum::name).orElse("WORK_ITEM_ROOT"),
                    value.associatedAt().value(),
                    TaskAssociationTaskResponse.from(task));
        }
    }

    public record TaskAssociationTaskResponse(
            UUID id,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            String objective,
            List<String> acceptanceCriteria,
            String status,
            UUID currentExecutionId,
            Integer currentAttempt,
            String currentExecutionStatus,
            long version,
            Instant createdAt,
            Instant updatedAt,
            String href) {

        static TaskAssociationTaskResponse from(TaskListItem value) {
            return new TaskAssociationTaskResponse(
                    value.id().value(),
                    value.scope().workspaceId().value(),
                    value.scope().projectId().value(),
                    value.workItemId().value(),
                    value.brief().objective(),
                    value.brief().acceptanceCriteria(),
                    value.status().name(),
                    value.currentExecutionId().map(valueId -> valueId.value()).orElse(null),
                    value.currentAttempt().orElse(null),
                    value.currentExecutionStatus().map(Enum::name).orElse(null),
                    value.version(),
                    value.audit().createdAt().value(),
                    value.audit().updatedAt().value(),
                    taskHref(value));
        }
    }

    public record TaskAssociationDetailsResponse(
            TaskReferenceResponse task,
            WorkItemReferenceResponse workItem,
            ConversationAssociationPageResponse conversations) {

        static TaskAssociationDetailsResponse from(
                TaskAssociationDetails value, TaskAssociationCursorCodec codec) {
            return new TaskAssociationDetailsResponse(
                    TaskReferenceResponse.from(value.task()),
                    WorkItemReferenceResponse.from(value.workItem()),
                    ConversationAssociationPageResponse.from(value.conversations(), codec));
        }
    }

    public record TaskReferenceResponse(
            UUID id, UUID projectId, UUID workItemId, String status, String objective, String href) {

        static TaskReferenceResponse from(io.crewscope.domain.task.Task value) {
            return new TaskReferenceResponse(
                    value.id().value(),
                    value.scope().projectId().value(),
                    value.workItemId().value(),
                    value.status().name(),
                    value.brief().objective(),
                    taskHref(value));
        }
    }

    public record WorkItemReferenceResponse(
            UUID id,
            UUID projectId,
            String key,
            String title,
            String status,
            String href) {

        static WorkItemReferenceResponse from(WorkItem value) {
            return new WorkItemReferenceResponse(
                    value.id().value(),
                    value.scope().projectId().value(),
                    value.key().value(),
                    value.title(),
                    value.status().name(),
                    workItemHref(value));
        }
    }

    public record ConversationAssociationPageResponse(
            List<ConversationReferenceResponse> items, String nextCursor) {

        static ConversationAssociationPageResponse from(
                TaskConversationAssociationPage page,
                TaskAssociationCursorCodec codec) {
            return new ConversationAssociationPageResponse(
                    page.items().stream().map(ConversationReferenceResponse::from).toList(),
                    page.nextCursor().map(codec::encode).orElse(null));
        }
    }

    public record ConversationReferenceResponse(
            UUID id,
            String title,
            String visibility,
            String status,
            String origin,
            Instant associatedAt,
            String href) {

        static ConversationReferenceResponse from(TaskConversationAssociation value) {
            return new ConversationReferenceResponse(
                    value.id().value(),
                    value.title(),
                    value.visibility().name(),
                    value.status().name(),
                    value.origin().name(),
                    value.associatedAt().value(),
                    conversationHref(value));
        }
    }
}
