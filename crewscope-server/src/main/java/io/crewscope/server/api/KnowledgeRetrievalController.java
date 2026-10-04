package io.crewscope.server.api;

import io.crewscope.application.embedding.EmbeddingClient;
import io.crewscope.application.retrieval.KnowledgeRetrievalQuery;
import io.crewscope.application.retrieval.KnowledgeRetrievalResult;
import io.crewscope.application.retrieval.KnowledgeRetrievalService;
import io.crewscope.application.retrieval.RetrievalCandidate;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.retrieval.DegradationReasonCode;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * M10-A01 preview surface over unified knowledge retrieval (S01 §3.7): the one read-only
 * HTTP entry that reuses the real retrieval path for offline evaluation and debugging.
 * Authorization mirrors the execution surface (member-level plus the four-coordinate
 * binding check), every answer — including degraded ones — is 200 with
 * {@code Cache-Control: no-store}, and scores pass through untruncated: the τ cutoff is
 * an evaluation-layer constant and never a product decision. This endpoint never feeds
 * the Prompt pipeline.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/knowledge")
public final class KnowledgeRetrievalController {

    private final KnowledgeRetrievalService service;
    private final TeamRequestIdentityResolver identityResolver;

    public KnowledgeRetrievalController(
            KnowledgeRetrievalService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/knowledge-retrieval:preview")
    public Mono<ResponseEntity<PreviewResponse>> preview(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @Valid @RequestBody PreviewBody request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        KnowledgeRetrievalQuery query = toQuery(request);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.retrieve(access, organization, team, query))
                .map(result -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(PreviewResponse.from(result, query)));
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

    private static KnowledgeRetrievalQuery toQuery(PreviewBody request) {
        // An absent sources list searches everything the body makes reachable: with a
        // repository target both sources, without it the knowledge entries alone.
        Set<ManifestSourceType> parsed =
                sources(request.sources(), request.repository() != null);
        if (parsed.contains(ManifestSourceType.REPOSITORY_CHUNK)
                != (request.repository() != null)) {
            throw invalidField("repository");
        }
        return new KnowledgeRetrievalQuery(
                request.query(),
                parsed,
                Optional.ofNullable(request.repository())
                        .map(body -> new KnowledgeRetrievalQuery.RepositoryTarget(
                                new WorkProjectId(body.projectId()),
                                new RepositoryBindingId(body.bindingId()),
                                commit(body.commit())))
                        .orElse(null),
                request.topK() == null
                        ? KnowledgeRetrievalQuery.DEFAULT_TOP_K
                        : request.topK());
    }

    private static Set<ManifestSourceType> sources(List<String> values, boolean repositoryGiven) {
        if (values == null) {
            return repositoryGiven
                    ? Set.of(ManifestSourceType.KNOWLEDGE_ENTRY, ManifestSourceType.REPOSITORY_CHUNK)
                    : Set.of(ManifestSourceType.KNOWLEDGE_ENTRY);
        }
        if (values.isEmpty()) {
            throw invalidField("sources");
        }
        Set<ManifestSourceType> parsed = EnumSet.noneOf(ManifestSourceType.class);
        for (String value : values) {
            try {
                parsed.add(ManifestSourceType.valueOf(value));
            } catch (RuntimeException failure) {
                throw invalidField("sources");
            }
        }
        return parsed;
    }

    private static SourceCommit commit(String value) {
        try {
            return new SourceCommit(value);
        } catch (RuntimeException failure) {
            throw invalidField("repository.commit");
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
                "Request contains an invalid knowledge retrieval field",
                Map.of("field", field));
    }

    // ------------------------------------------------------------------ DTOs

    public record PreviewBody(
            @NotBlank
            @Size(max = EmbeddingClient.MAX_INPUT_CHARS)
            String query,
            List<String> sources,
            @Min(1) @Max(KnowledgeRetrievalQuery.MAX_TOP_K) Integer topK,
            @Valid RepositoryBody repository) {

        public record RepositoryBody(
                @NotNull UUID projectId,
                @NotNull UUID bindingId,
                @NotBlank
                @Pattern(regexp = "[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
                String commit) {}
    }

    /** Always 200 — a degraded source never masquerades as an empty-but-successful page. */
    public record PreviewResponse(
            List<CandidateResponse> candidates,
            List<String> degraded,
            MetaResponse meta) {

        static PreviewResponse from(KnowledgeRetrievalResult result, KnowledgeRetrievalQuery query) {
            return new PreviewResponse(
                    result.candidates().stream().map(CandidateResponse::from).toList(),
                    result.degradations().stream().map(DegradationReasonCode::name).toList(),
                    new MetaResponse(
                            query.topK(),
                            query.sources().stream().map(ManifestSourceType::name).toList()));
        }

        /** The effective retrieval budget, so offline evaluation can replay the call. */
        public record MetaResponse(int topK, List<String> sources) {}
    }

    /** The closed candidate projection; either an entry hit or same-file fragments. */
    public record CandidateResponse(
            String source,
            int rank,
            double score,
            EntryHitResponse entry,
            List<FragmentResponse> fragments) {

        static CandidateResponse from(RetrievalCandidate candidate) {
            return new CandidateResponse(
                    candidate.source().name(),
                    candidate.rank(),
                    candidate.score(),
                    candidate.entry() == null
                            ? null
                            : new EntryHitResponse(
                                    candidate.entry().entryId().value().toString(),
                                    candidate.entry().revision().value(),
                                    candidate.entry().title(),
                                    candidate.entry().contentHash(),
                                    candidate.entry().content()),
                    candidate.fragments().stream()
                            .map(fragment -> new FragmentResponse(
                                    fragment.bindingId().value().toString(),
                                    fragment.commit().value(),
                                    fragment.generationBuildSequence(),
                                    fragment.chunkSeq(),
                                    fragment.path(),
                                    fragment.language(),
                                    fragment.startLine(),
                                    fragment.endLine(),
                                    fragment.contentHash(),
                                    fragment.content()))
                            .toList());
        }
    }

    public record EntryHitResponse(
            String entryId,
            long revision,
            String title,
            String contentHash,
            String content) {}

    public record FragmentResponse(
            String bindingId,
            String commit,
            long generationBuildSequence,
            int chunkSeq,
            String path,
            String language,
            int startLine,
            int endLine,
            String contentHash,
            String content) {}
}
