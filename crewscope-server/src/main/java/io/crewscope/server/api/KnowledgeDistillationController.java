package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.knowledge.DistillKnowledgeEntryCommand;
import io.crewscope.application.knowledge.KnowledgeDistillationService;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A02b HTTP boundary for member-initiated knowledge distillation: one completed Task
 * execution attempt becomes one DRAFT entry through the built-in Distiller, synchronously
 * acknowledged with the standard 202 command receipt once the entry, its origin and the
 * DISTILLATION usage facts have committed.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/knowledge/distillations")
public final class KnowledgeDistillationController {

    /** A02b boundary: the index projection belongs to I01 and consumes the emitted events. */
    private static final String INDEX_STATUS_PENDING = "PENDING";

    private final KnowledgeDistillationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public KnowledgeDistillationController(
            KnowledgeDistillationService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @PostMapping
    public Mono<ResponseEntity<DistillationAcceptedResponse>> distill(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody DistillEntryBody request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        DistillKnowledgeEntryCommand command = new DistillKnowledgeEntryCommand(
                taskExecutionId(request.taskExecutionId()),
                entryKey(request.entryKey()),
                Optional.ofNullable(request.category()).map(KnowledgeDistillationController::category));
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, organization, correlationId)
                .flatMap(access -> Mono
                        .fromCompletionStage(() -> service.distill(
                                new TeamCommandContext(access, idempotencyKey, correlationId,
                                        Optional.empty()),
                                team, command))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(execution -> {
                    ResponseEntity.BodyBuilder response =
                            ResponseEntity.accepted().cacheControl(CacheControl.noStore());
                    if (execution.replayed()) {
                        response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
                    }
                    return response.body(DistillationAcceptedResponse.accepted(
                            execution, command.entryKey().value()));
                });
    }

    // ---------------------------------------------------------------- internals

    private static TaskExecutionId taskExecutionId(String value) {
        try {
            return TaskExecutionId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("taskExecutionId");
        }
    }

    private static KnowledgeEntryKey entryKey(String value) {
        try {
            return new KnowledgeEntryKey(value);
        } catch (RuntimeException failure) {
            throw invalidField("entryKey");
        }
    }

    private static KnowledgeCategory category(String value) {
        try {
            return KnowledgeCategory.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalidField("category");
        }
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("organizationId");
        }
    }

    private static TeamId teamId(String value) {
        try {
            return TeamId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("teamId");
        }
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid knowledge distillation field",
                Map.of("field", field));
    }

    public record DistillEntryBody(
            @NotNull String taskExecutionId,
            @NotBlank String entryKey,
            String category) {}

    /**
     * 202 receipt for first execution and replay alike; a replay never re-exposes the
     * entry id, but the caller-chosen immutable {@code entryKey} always identifies it.
     */
    public record DistillationAcceptedResponse(
            UUID commandId,
            UUID domainEventId,
            long committedVersion,
            UUID correlationId,
            String entryId,
            String entryKey,
            OriginResponse origin,
            String indexStatus) {

        static DistillationAcceptedResponse accepted(
                CommandExecution<KnowledgeEntry> execution, String entryKey) {
            KnowledgeEntry entry = execution.result().orElse(null);
            return new DistillationAcceptedResponse(
                    execution.receipt().commandId(),
                    execution.receipt().domainEventId(),
                    execution.receipt().committedVersion(),
                    execution.receipt().correlationId(),
                    entry == null ? null : entry.id().value().toString(),
                    entryKey,
                    entry == null ? null : OriginResponse.from(entry),
                    INDEX_STATUS_PENDING);
        }
    }

    /** Immutable distillation attribution carried on the entry for its whole lifecycle. */
    public record OriginResponse(String taskExecutionId, int attempt) {

        static OriginResponse from(KnowledgeEntry entry) {
            return entry.origin()
                    .map(origin -> new OriginResponse(
                            origin.taskExecutionId().toString(), origin.attempt()))
                    .orElse(null);
        }
    }
}
