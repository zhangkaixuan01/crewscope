package io.crewscope.server.api;

import io.crewscope.application.action.ActionBundleView;
import io.crewscope.application.action.ActionDeliveryApplicationService;
import io.crewscope.application.action.PlanSourceDeliveryActionRequest;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.action.ActionCancellationReason;
import io.crewscope.domain.action.ActionDispatchId;
import io.crewscope.domain.action.ActionReceiptResult;
import io.crewscope.domain.action.ConfirmationId;
import io.crewscope.domain.action.ExternalObjectType;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.ManualResolutionReason;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
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

/** Owner-authorized ActionBundle preview, confirmation, status and reconciliation HTTP boundary. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/actions")
public final class ActionDeliveryController {

    private final ActionDeliveryApplicationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public ActionDeliveryController(
            ActionDeliveryApplicationService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/bundles")
    public Mono<ResponseEntity<CommandReceiptResponse>> plan(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody PlanBundleBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        PlanSourceDeliveryActionRequest request = new PlanSourceDeliveryActionRequest(
                reviewDecisionId(body.reviewDecisionId()),
                providerBindingId(body.providerBindingId()),
                new ExternalRepositoryId(body.repositoryId()),
                optionalCommit(body.expectedRemoteHead()),
                body.title(),
                body.body());
        return command(authentication, route, key, exchange,
                context -> service.plan(
                        context,
                        route.organizationId(),
                        route.teamId(),
                        route.taskId(),
                        route.executionId(),
                        request));
    }

    @GetMapping("/bundles")
    public Mono<ResponseEntity<ActionBundleListResponse>> list(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        return query(authentication, route, exchange,
                        access -> service.list(
                                access,
                                route.organizationId(),
                                route.teamId(),
                                route.taskId(),
                                route.executionId()))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(new ActionBundleListResponse(values)));
    }

    @GetMapping("/bundles/{bundleId}")
    public Mono<ResponseEntity<ActionBundleView>> get(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @PathVariable String bundleId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        ActionBundleId bundle = actionBundleId(bundleId);
        return query(authentication, route, exchange,
                        access -> service.get(
                                access,
                                route.organizationId(),
                                route.teamId(),
                                route.taskId(),
                                route.executionId(),
                                bundle))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.version()))
                        .body(value));
    }

    @PostMapping("/bundles/{bundleId}/confirmations")
    public Mono<ResponseEntity<CommandReceiptResponse>> confirm(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @PathVariable String bundleId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ConfirmBundleBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        ActionBundleId bundle = actionBundleId(bundleId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        return command(authentication, route, key, exchange,
                context -> service.confirm(
                        context,
                        route.organizationId(),
                        route.teamId(),
                        route.taskId(),
                        route.executionId(),
                        bundle,
                        expectedVersion,
                        body.bundleDigest()));
    }

    @PostMapping("/confirmations/{confirmationId}/cancel")
    public Mono<ResponseEntity<CommandReceiptResponse>> cancel(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @PathVariable String confirmationId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CancelConfirmationBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        ConfirmationId confirmation = confirmationId(confirmationId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        ActionCancellationReason reason = cancellationReason(body.reason());
        return command(authentication, route, key, exchange,
                context -> service.cancel(
                        context,
                        route.organizationId(),
                        route.teamId(),
                        route.taskId(),
                        route.executionId(),
                        confirmation,
                        expectedVersion,
                        reason));
    }

    @PostMapping("/dispatches/{dispatchId}/manual-resolution")
    public Mono<ResponseEntity<CommandReceiptResponse>> resolveManually(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @PathVariable String dispatchId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ManualResolutionBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        Optional<ExternalResultIdentity> identity = externalIdentity(body);
        return command(authentication, route, key, exchange,
                        context -> service.resolveManually(
                                context,
                                route.organizationId(),
                                route.teamId(),
                                route.taskId(),
                                route.executionId(),
                                actionDispatchId(dispatchId),
                                expectedVersion,
                                receiptResult(body.result()),
                                identity,
                                optionalText(body.targetVersion()),
                                manualReason(body.reason()),
                                body.explanation()));
    }

    private <T> Mono<ResponseEntity<CommandReceiptResponse>> command(
            Authentication authentication,
            Route route,
            String key,
            ServerWebExchange exchange,
            Function<TeamCommandContext, CommandExecution<T>> action) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        return identityResolver.resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> action.apply(new TeamCommandContext(
                        access, idempotencyKey, correlationId, Optional.empty()))))
                .map(CommandReceiptResponse::accepted);
    }

    private <T> Mono<T> query(
            Authentication authentication,
            Route route,
            ServerWebExchange exchange,
            Function<TeamAccessContext, T> action) {
        return identityResolver.resolve(
                        authentication,
                        route.organizationId(),
                        ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> action.apply(access)));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static Route route(String organization, String team, String task, String execution) {
        try {
            return new Route(
                    OrganizationId.from(organization),
                    TeamId.from(team),
                    TaskId.from(task),
                    TaskExecutionId.from(execution));
        } catch (RuntimeException failure) {
            throw invalidField("route");
        }
    }

    private static ActionBundleId actionBundleId(String value) {
        try {
            return new ActionBundleId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw invalidField("bundleId");
        }
    }

    private static ConfirmationId confirmationId(String value) {
        try {
            return new ConfirmationId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw invalidField("confirmationId");
        }
    }

    private static ActionDispatchId actionDispatchId(String value) {
        try {
            return new ActionDispatchId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw invalidField("dispatchId");
        }
    }

    private static ReviewDecisionId reviewDecisionId(String value) {
        try {
            return new ReviewDecisionId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw invalidField("reviewDecisionId");
        }
    }

    private static ProviderBindingId providerBindingId(String value) {
        try {
            return ProviderBindingId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("providerBindingId");
        }
    }

    private static Optional<RepositoryCommitId> optionalCommit(String value) {
        try {
            return optionalText(value).map(RepositoryCommitId::new);
        } catch (RuntimeException failure) {
            throw invalidField("expectedRemoteHead");
        }
    }

    private static Optional<ExternalResultIdentity> externalIdentity(ManualResolutionBody body) {
        boolean none = body.connectionId() == null
                && body.objectType() == null
                && body.externalId() == null
                && body.businessKey() == null;
        boolean all = body.connectionId() != null
                && body.objectType() != null
                && body.externalId() != null
                && body.businessKey() != null;
        if (none) {
            return Optional.empty();
        }
        if (!all) {
            throw invalidField("externalIdentity");
        }
        try {
            return Optional.of(new ExternalResultIdentity(
                    ConnectionId.from(body.connectionId()),
                    ExternalObjectType.valueOf(body.objectType()),
                    body.externalId(),
                    body.businessKey()));
        } catch (RuntimeException failure) {
            throw invalidField("externalIdentity");
        }
    }

    private static ActionCancellationReason cancellationReason(String value) {
        try {
            return ActionCancellationReason.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalidField("reason");
        }
    }

    private static ActionReceiptResult receiptResult(String value) {
        try {
            ActionReceiptResult result = ActionReceiptResult.valueOf(value);
            if (!result.isManual()) {
                throw new IllegalArgumentException("not manual");
            }
            return result;
        } catch (RuntimeException failure) {
            throw invalidField("result");
        }
    }

    private static ManualResolutionReason manualReason(String value) {
        try {
            return ManualResolutionReason.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalidField("reason");
        }
    }

    private static Optional<String> optionalText(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid Action field",
                Map.of("field", field));
    }

    public record PlanBundleBody(
            @NotBlank String reviewDecisionId,
            @NotBlank String providerBindingId,
            @NotBlank @Size(max = 200) String repositoryId,
            @Size(max = 40) String expectedRemoteHead,
            @NotBlank @Size(max = 256) String title,
            @NotBlank @Size(max = 65_536) String body) {}

    public record ConfirmBundleBody(
            @NotBlank @Size(min = 64, max = 64) String bundleDigest) {}

    public record CancelConfirmationBody(@NotBlank String reason) {}

    public record ManualResolutionBody(
            @NotBlank String result,
            String connectionId,
            String objectType,
            @Size(max = 500) String externalId,
            @Size(max = 500) String businessKey,
            @Size(max = 500) String targetVersion,
            @NotBlank String reason,
            @NotBlank @Size(max = 2_000) String explanation) {}

    public record ActionBundleListResponse(List<ActionBundleView> items) {
        public ActionBundleListResponse {
            items = List.copyOf(items);
        }
    }

    private record Route(
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {}
}
