package io.crewscope.server.api;

import io.crewscope.application.task.TaskAssociationCursor;
import io.crewscope.application.task.TaskAssociationPage;
import io.crewscope.application.task.TaskAssociationService;
import io.crewscope.application.task.TaskAssociationSourceType;
import io.crewscope.application.task.TaskDeliverySummary;
import io.crewscope.application.task.TaskDeliverySummaryService;
import io.crewscope.application.task.TaskListItem;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.server.observability.TaskDeliveryObservationRecorder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Member-safe Task and Conversation delivery cards with current authorization checks. */
@RestController
public final class TaskDeliverySummaryController {

    private final TaskDeliverySummaryService summaries;
    private final TaskAssociationService associations;
    private final TeamRequestIdentityResolver identityResolver;
    private final TaskDeliveryObservationRecorder recorder;
    private final TaskAssociationCursorCodec cursorCodec = new TaskAssociationCursorCodec();

    public TaskDeliverySummaryController(
            TaskDeliverySummaryService summaries,
            TaskAssociationService associations,
            TeamRequestIdentityResolver identityResolver,
            TaskDeliveryObservationRecorder recorder) {
        this.summaries = summaries;
        this.associations = associations;
        this.identityResolver = identityResolver;
        this.recorder = recorder;
    }

    @GetMapping(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/delivery-summary")
    public Mono<ResponseEntity<TaskDeliverySummary>> task(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        TaskId task = taskId(taskId);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> new Authorized<>(
                        access,
                        summaries.get(access, route.organizationId(), route.teamId(), task))))
                .map(result -> {
                    recorder.record(
                            TaskDeliveryObservationRecorder.View.TASK,
                            result.access(),
                            route.organizationId(),
                            route.teamId(),
                            correlationId,
                            1,
                            result.value());
                    return noStore(result.value());
                });
    }

    @GetMapping(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/delivery-cards")
    public Mono<ResponseEntity<DeliveryCardPageResponse>> conversation(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String conversationId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        ConversationId conversation = conversationId(conversationId);
        Optional<TaskAssociationCursor> cursor = Optional.ofNullable(after).map(value ->
                cursorCodec.decode(
                        value,
                        route.organizationId(),
                        route.teamId(),
                        TaskAssociationSourceType.CONVERSATION,
                        conversation.value()));
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> {
                    TaskAssociationPage page = associations.byConversation(
                            access,
                            route.organizationId(),
                            route.teamId(),
                            conversation,
                            cursor,
                            ApiPagination.deliveryCardsLimit(limit));
                    return new Authorized<>(
                            access,
                            new DeliveryPage(
                                    page,
                                    summaries.summarizePage(
                                            access,
                                            route.organizationId(),
                                            route.teamId(),
                                            page)));
                }))
                .map(result -> {
                    List<TaskDeliverySummary> values = result.value().summaries();
                    recorder.record(
                            TaskDeliveryObservationRecorder.View.CONVERSATION,
                            result.access(),
                            route.organizationId(),
                            route.teamId(),
                            correlationId,
                            values.size(),
                            values.isEmpty() ? null : values.get(0));
                    return noStore(DeliveryCardPageResponse.from(
                            result.value(), cursorCodec));
                });
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static Route route(String organizationId, String teamId) {
        try {
            return new Route(OrganizationId.from(organizationId), TeamId.from(teamId));
        } catch (IllegalArgumentException exception) {
            throw invalid("route");
        }
    }

    private static TaskId taskId(String value) {
        try {
            return TaskId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("taskId");
        }
    }

    private static ConversationId conversationId(String value) {
        try {
            return ConversationId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("conversationId");
        }
    }

    private static ApiRequestException invalid(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", field));
    }

    private record Route(OrganizationId organizationId, TeamId teamId) {}

    private record Authorized<T>(TeamAccessContext access, T value) {}

    private record DeliveryPage(
            TaskAssociationPage associations,
            List<TaskDeliverySummary> summaries) {

        private DeliveryPage {
            summaries = List.copyOf(summaries);
            if (associations.items().size() != summaries.size()) {
                throw new IllegalArgumentException("Delivery cards must match association page");
            }
        }
    }

    public record DeliveryCardPageResponse(List<DeliveryCardResponse> items, String nextCursor) {

        static DeliveryCardPageResponse from(
                DeliveryPage page, TaskAssociationCursorCodec codec) {
            List<DeliveryCardResponse> items = java.util.stream.IntStream
                    .range(0, page.associations().items().size())
                    .mapToObj(index -> DeliveryCardResponse.from(
                            page.associations().items().get(index).task(),
                            page.summaries().get(index)))
                    .toList();
            return new DeliveryCardPageResponse(
                    items,
                    page.associations().nextCursor().map(codec::encode).orElse(null));
        }
    }

    public record DeliveryCardResponse(
            UUID taskId,
            String objective,
            String status,
            UUID currentExecutionId,
            Integer currentAttempt,
            TaskDeliverySummary delivery) {

        static DeliveryCardResponse from(TaskListItem task, TaskDeliverySummary delivery) {
            return new DeliveryCardResponse(
                    task.id().value(),
                    task.brief().objective(),
                    task.status().name(),
                    task.currentExecutionId().map(value -> value.value()).orElse(null),
                    task.currentAttempt().orElse(null),
                    delivery);
        }
    }
}
