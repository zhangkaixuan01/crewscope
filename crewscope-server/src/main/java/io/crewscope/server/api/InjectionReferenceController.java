package io.crewscope.server.api;

import io.crewscope.application.retrieval.InjectionClaimedReferences;
import io.crewscope.application.retrieval.InjectionReferenceService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.retrieval.InjectionManifest;
import io.crewscope.domain.retrieval.ManifestSourceKey;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * M10-I02c HTTP evidence face of one execution: the sealed manifests read back in
 * three zones (candidates, injected, claimed), a member's "not applicable" judgement
 * against the injected union, and the model's claimed-reference receipt against one
 * attempt's manifest. Everything is synchronous, member-level and no-store — sealed
 * evidence is a historical fact and never gated on the injection switches.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}")
public final class InjectionReferenceController {

    private final InjectionReferenceService service;
    private final TeamRequestIdentityResolver identityResolver;

    public InjectionReferenceController(
            InjectionReferenceService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = Objects.requireNonNull(service, "service");
        this.identityResolver =
                Objects.requireNonNull(identityResolver, "identityResolver");
    }

    @GetMapping("/attempts/{executionId}/injection-references")
    public Mono<ResponseEntity<InjectionReferencesResponse>> view(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Coordinates coordinates = coordinates(organizationId, teamId, taskId, executionId);
        return query(
                        authentication,
                        coordinates.organizationId(),
                        exchange,
                        access -> service.view(
                                access, coordinates.organizationId(), coordinates.teamId(),
                                coordinates.taskId(), coordinates.executionId()))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(InjectionReferencesResponse.from(
                                coordinates.executionId(), coordinates.taskId(), value)));
    }

    @PostMapping("/attempts/{executionId}/injection-references/feedback")
    public Mono<ResponseEntity<ReferenceKeyResponse>> feedback(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @RequestBody ReferenceKeyBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Coordinates coordinates = coordinates(organizationId, teamId, taskId, executionId);
        ManifestSourceKey source = parseKey(body);
        return query(
                        authentication,
                        coordinates.organizationId(),
                        exchange,
                        access -> service.submitFeedback(
                                access, coordinates.organizationId(), coordinates.teamId(),
                                coordinates.taskId(), coordinates.executionId(), source))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(ReferenceKeyResponse.from(value.source())));
    }

    @PostMapping("/attempts/{executionId}/injection-references/claimed")
    public Mono<ResponseEntity<ClaimedReceiptResponse>> claimed(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @RequestBody ClaimedBody body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Coordinates coordinates = coordinates(organizationId, teamId, taskId, executionId);
        int attempt = parseAttempt(body.attempt());
        List<ReferenceKeyBody> references =
                body.references() == null ? List.of() : body.references();
        List<ManifestSourceKey> claimed = references.stream()
                .filter(Objects::nonNull)
                .map(InjectionReferenceController::parseKey)
                .toList();
        return query(
                        authentication,
                        coordinates.organizationId(),
                        exchange,
                        access -> service.submitClaimed(
                                access, coordinates.organizationId(), coordinates.teamId(),
                                coordinates.taskId(), coordinates.executionId(), attempt,
                                claimed))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(ClaimedReceiptResponse.from(value)));
    }

    // ------------------------------------------------------------------ helpers

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

    private record Coordinates(
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {
    }

    private static Coordinates coordinates(
            String organizationId, String teamId, String taskId, String executionId) {
        return new Coordinates(
                parse(organizationId, "organizationId", OrganizationId::from),
                parse(teamId, "teamId", TeamId::from),
                parse(taskId, "taskId", value -> TaskId.from(value)),
                parse(executionId, "executionId", value ->
                        new TaskExecutionId(UUID.fromString(value))));
    }

    private static ManifestSourceKey parseKey(ReferenceKeyBody body) {
        Objects.requireNonNull(body, "body");
        return new ManifestSourceKey(
                parse(body.type(), "type", ManifestSourceType::valueOf),
                body.sourceId(),
                parse(body.version(), "version", Long::parseLong),
                body.contentHash());
    }

    private static int parseAttempt(String attempt) {
        int parsed = parse(attempt, "attempt", Integer::parseInt);
        if (parsed < 1) {
            throw invalidField("attempt");
        }
        return parsed;
    }

    private static <T> T parse(String value, String field, Function<String, T> parser) {
        try {
            return parser.apply(value);
        } catch (RuntimeException failure) {
            throw invalidField(field);
        }
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid injection reference field",
                Map.of("field", field));
    }

    // ------------------------------------------------------------------ DTOs

    /** The stage-free key as it travels in a request — shared by feedback and claims. */
    public record ReferenceKeyBody(
            String type, String sourceId, String version, String contentHash) {}

    public record ClaimedBody(String attempt, List<ReferenceKeyBody> references) {}

    /** The stage-free key — the shared address of feedback and claims. */
    public record ReferenceKeyResponse(
            String type, String sourceId, long version, String contentHash) {

        static ReferenceKeyResponse from(ManifestSourceKey key) {
            return new ReferenceKeyResponse(
                    key.type().name(), key.sourceId(), key.version(), key.contentHash());
        }
    }

    public record ClaimedReceiptResponse(
            String executionId, int attempt, List<ReferenceKeyResponse> claimed,
            String createdAt) {

        static ClaimedReceiptResponse from(InjectionClaimedReferences receipt) {
            return new ClaimedReceiptResponse(
                    receipt.executionId().value().toString(),
                    receipt.attempt(),
                    receipt.claimed().stream().map(ReferenceKeyResponse::from).toList(),
                    receipt.createdAt().value().toString());
        }
    }

    /** One sealed assembly in three zones: candidates, injected, and the claim. */
    public record AttemptResponse(
            String manifestId,
            int attempt,
            String createdAt,
            BudgetResponse budget,
            List<String> degradations,
            List<TrimResponse> trims,
            List<ReferenceResponse> references,
            List<ReferenceKeyResponse> claimed) {

        static AttemptResponse from(
                InjectionManifest manifest,
                Set<ManifestSourceKey> ownFeedback,
                InjectionClaimedReferences receipt) {
            return new AttemptResponse(
                    manifest.id().value().toString(),
                    manifest.attempt(),
                    manifest.createdAt().value().toString(),
                    BudgetResponse.from(manifest.budget()),
                    manifest.degradations().stream().map(Enum::name).toList(),
                    manifest.trims().stream().map(TrimResponse::from).toList(),
                    manifest.references().stream()
                            .map(reference -> ReferenceResponse.from(
                                    reference,
                                    ownFeedback.contains(ManifestSourceKey.of(reference))))
                            .toList(),
                    receipt == null ? null
                            : receipt.claimed().stream()
                                    .map(ReferenceKeyResponse::from).toList());
        }
    }

    public record ReferenceResponse(
            String type, String sourceId, long version, String contentHash,
            String stage, boolean notApplicable) {

        static ReferenceResponse from(
                io.crewscope.domain.retrieval.ManifestSourceRef reference,
                boolean notApplicable) {
            return new ReferenceResponse(
                    reference.type().name(),
                    reference.sourceId(),
                    reference.version(),
                    reference.contentHash(),
                    reference.stage().name(),
                    notApplicable);
        }
    }

    public record BudgetResponse(
            long totalTokens, long knowledgeTokens, long chunkTokens, long memoryTokens) {

        static BudgetResponse from(io.crewscope.domain.retrieval.PromptBudgetSnapshot budget) {
            return new BudgetResponse(
                    budget.totalTokens(), budget.knowledgeTokens(),
                    budget.chunkTokens(), budget.memoryTokens());
        }
    }

    public record TrimResponse(String layer, int trimmedCount, String reason) {

        static TrimResponse from(io.crewscope.domain.retrieval.TrimRecord trim) {
            return new TrimResponse(
                    trim.layer().name(), trim.trimmedCount(), trim.reason());
        }
    }

    public record InjectionReferencesResponse(
            String executionId, String taskId, List<AttemptResponse> attempts) {

        static InjectionReferencesResponse from(
                TaskExecutionId executionId,
                TaskId taskId,
                InjectionReferenceService.InjectionReferenceView view) {
            Set<ManifestSourceKey> ownFeedback = new HashSet<>();
            view.memberFeedback().forEach(row -> ownFeedback.add(row.source()));
            Map<Integer, InjectionClaimedReferences> receiptsByAttempt =
                    view.claimed().stream()
                            .collect(Collectors.toMap(
                                    InjectionClaimedReferences::attempt,
                                    receipt -> receipt));
            return new InjectionReferencesResponse(
                    executionId.value().toString(),
                    taskId.value().toString(),
                    view.manifests().stream()
                            .map(manifest -> AttemptResponse.from(
                                    manifest, ownFeedback,
                                    receiptsByAttempt.get(manifest.attempt())))
                            .toList());
        }
    }
}
