package io.crewscope.server.api;

import io.crewscope.application.retrieval.KnowledgeIndexControlService;
import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.application.retrieval.KnowledgeIndexJobFilter;
import io.crewscope.application.retrieval.KnowledgeIndexJobPage;
import io.crewscope.application.retrieval.KnowledgeIndexJobPageRequest;
import io.crewscope.application.retrieval.KnowledgeIndexJobSource;
import io.crewscope.application.retrieval.KnowledgeIndexJobStatus;
import io.crewscope.application.retrieval.RepositoryBuildEnqueueResult;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * M10-I01c HTTP control plane over the durable knowledge-index job model: rebuild and
 * repository-build triggers answer 202 (a closed refresh gate answers 202 with zero —
 * a skip, not an error), job reads are member-level and no-store, and cancelling a
 * still-QUEUED job is a synchronous 200. Idempotency is structural — the live-job
 * partial unique indexes collapse duplicate triggers and cancel replays the terminal
 * snapshot — so no Idempotency-Key or If-Match is required.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/knowledge/index")
public final class KnowledgeIndexController {

    private final KnowledgeIndexControlService service;
    private final TeamRequestIdentityResolver identityResolver;

    public KnowledgeIndexController(
            KnowledgeIndexControlService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/rebuilds")
    public Mono<ResponseEntity<RebuildAcceptedResponse>> rebuild(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        return query(
                authentication,
                organization,
                exchange,
                access -> ResponseEntity.accepted()
                        .body(new RebuildAcceptedResponse(
                                service.rebuild(access, organization, team))));
    }

    @PostMapping("/repository-builds")
    public Mono<ResponseEntity<RepositoryBuildAcceptedResponse>> enqueueRepositoryBuild(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @Valid @RequestBody RepositoryBuildBody request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        WorkProjectId project = new WorkProjectId(request.projectId());
        RepositoryBindingId binding = new RepositoryBindingId(request.bindingId());
        SourceCommit commit = commit(request.commit());
        return query(
                authentication,
                organization,
                exchange,
                access -> {
                    RepositoryBuildEnqueueResult result = service.enqueueRepositoryBuild(
                            access, organization, team, project, binding, commit);
                    return ResponseEntity.accepted()
                            .body(new RepositoryBuildAcceptedResponse(
                                    result.accepted() ? 1 : 0,
                                    result.job()
                                            .map(KnowledgeIndexJobResponse::from)
                                            .orElse(null)));
                });
    }

    @GetMapping("/jobs")
    public Mono<ResponseEntity<KnowledgeIndexJobListResponse>> list(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        KnowledgeIndexJobFilter filter = filter(source, status);
        KnowledgeIndexJobPageRequest pageRequest = new KnowledgeIndexJobPageRequest(
                afterJobId(after), ApiPagination.limit(limit));
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.list(access, organization, team, filter, pageRequest))
                .map(page -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(KnowledgeIndexJobListResponse.from(page)));
    }

    @GetMapping("/jobs/{jobId}")
    public Mono<ResponseEntity<KnowledgeIndexJobResponse>> job(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String jobId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        UUID job = jobId(jobId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.job(access, organization, team, job))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(KnowledgeIndexJobResponse.from(value)));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public Mono<ResponseEntity<KnowledgeIndexJobResponse>> cancel(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String jobId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        UUID job = jobId(jobId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.cancel(access, organization, team, job))
                .map(value -> ResponseEntity.ok().body(KnowledgeIndexJobResponse.from(value)));
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

    private static KnowledgeIndexJobFilter filter(String source, String status) {
        KnowledgeIndexJobSource parsedSource =
                source == null ? null : parseEnum(source, KnowledgeIndexJobSource::valueOf, "source");
        KnowledgeIndexJobStatus parsedStatus =
                status == null ? null : parseEnum(status, KnowledgeIndexJobStatus::valueOf, "status");
        return new KnowledgeIndexJobFilter(
                Optional.ofNullable(parsedSource), Optional.ofNullable(parsedStatus));
    }

    private static Optional<UUID> afterJobId(String after) {
        return Optional.ofNullable(after).map(value -> {
            try {
                return UUID.fromString(value);
            } catch (RuntimeException failure) {
                throw invalidField("after");
            }
        });
    }

    private static UUID jobId(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException failure) {
            throw invalidField("jobId");
        }
    }

    private static SourceCommit commit(String value) {
        try {
            return new SourceCommit(value);
        } catch (RuntimeException failure) {
            throw invalidField("commit");
        }
    }

    private static <E extends Enum<E>> E parseEnum(
            String value, Function<String, E> parser, String field) {
        try {
            return parser.apply(value);
        } catch (RuntimeException failure) {
            throw invalidField(field);
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
                "Request contains an invalid knowledge index field",
                Map.of("field", field));
    }

    // ------------------------------------------------------------------ DTOs

    public record RepositoryBuildBody(
            @NotNull UUID projectId,
            @NotNull UUID bindingId,
            @NotBlank
            @Pattern(regexp = "[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
            String commit) {}

    public record RebuildAcceptedResponse(int enqueued) {}

    public record RepositoryBuildAcceptedResponse(
            int enqueued, KnowledgeIndexJobResponse job) {}

    /** The closed job snapshot; claimToken is deliberately absent (worker-internal). */
    public record KnowledgeIndexJobResponse(
            String id,
            String source,
            String status,
            String entryId,
            String projectId,
            IndexKeyResponse indexKey,
            int attempt,
            int chunksDone,
            int chunksTotal,
            String failureCode,
            long generationBuildSequence,
            String claimedBy,
            String leaseExpiresAt,
            String createdBy,
            String createdAt,
            String updatedAt) {

        static KnowledgeIndexJobResponse from(KnowledgeIndexJob job) {
            return new KnowledgeIndexJobResponse(
                    job.id().toString(),
                    job.source().name(),
                    job.status().name(),
                    job.entryId().map(entry -> entry.value().toString()).orElse(null),
                    job.projectId().map(project -> project.value().toString()).orElse(null),
                    job.indexKey().map(IndexKeyResponse::from).orElse(null),
                    job.attempt(),
                    job.chunksDone(),
                    job.chunksTotal(),
                    job.failureCode().orElse(null),
                    job.generationBuildSequence(),
                    job.claimedBy().orElse(null),
                    job.leaseExpiresAt().map(lease -> lease.value().toString()).orElse(null),
                    job.createdBy().value().toString(),
                    job.createdAt().value().toString(),
                    job.updatedAt().value().toString());
        }
    }

    /** The frozen repository index coordinate, decomposed (never a canonical string). */
    public record IndexKeyResponse(
            String bindingId,
            String commit,
            String chunkPolicyHash,
            String modelKey,
            long modelRevision) {

        static IndexKeyResponse from(RepositoryIndexKey key) {
            return new IndexKeyResponse(
                    key.repositoryBindingId().value().toString(),
                    key.sourceCommit().value(),
                    key.chunkingPolicyHash().value(),
                    key.embeddingModelRevision().modelKey(),
                    key.embeddingModelRevision().revision());
        }
    }

    public record KnowledgeIndexJobListResponse(
            List<KnowledgeIndexJobResponse> items, String nextAfter) {

        static KnowledgeIndexJobListResponse from(KnowledgeIndexJobPage page) {
            return new KnowledgeIndexJobListResponse(
                    page.items().stream().map(KnowledgeIndexJobResponse::from).toList(),
                    page.nextAfterJobId().map(UUID::toString).orElse(null));
        }
    }
}
