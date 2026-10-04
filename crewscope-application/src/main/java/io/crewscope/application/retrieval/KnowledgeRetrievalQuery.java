package io.crewscope.application.retrieval;

import io.crewscope.application.embedding.EmbeddingClient;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Set;

/**
 * One unified retrieval request over the two explicit knowledge sources (M10-A01,
 * S01 §3.4): the query text (embedded through the Team's governed model), the source
 * selection and, for the repository source, the exact (project, binding, commit)
 * target whose ACTIVE generation is searched. The server derives every coordinate —
 * callers never widen scope by hand. Top-K defaults to 8 and is capped at 20.
 */
public record KnowledgeRetrievalQuery(
        String query,
        Set<ManifestSourceType> sources,
        RepositoryTarget repository,
        int topK) {

    /** S01 §3.4 frozen default and ceiling. */
    public static final int DEFAULT_TOP_K = 8;
    public static final int MAX_TOP_K = 20;

    /** The repository search target; the ACTIVE generation of exactly this coordinate. */
    public record RepositoryTarget(
            WorkProjectId projectId, RepositoryBindingId bindingId, SourceCommit commit) {

        public RepositoryTarget {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(commit, "commit");
        }
    }

    public KnowledgeRetrievalQuery {
        query = Objects.requireNonNull(query, "query").strip();
        if (query.isEmpty() || query.length() > EmbeddingClient.MAX_INPUT_CHARS) {
            throw new DomainValidationException(
                    "knowledgeRetrieval.query",
                    "must contain 1 to " + EmbeddingClient.MAX_INPUT_CHARS + " characters");
        }
        if (Objects.requireNonNull(sources, "sources").isEmpty()) {
            throw new DomainValidationException(
                    "knowledgeRetrieval.sources", "must select at least one source");
        }
        for (ManifestSourceType source : sources) {
            if (source != ManifestSourceType.KNOWLEDGE_ENTRY
                    && source != ManifestSourceType.REPOSITORY_CHUNK) {
                throw new DomainValidationException(
                        "knowledgeRetrieval.sources",
                        "unified retrieval only covers KNOWLEDGE_ENTRY and REPOSITORY_CHUNK");
            }
        }
        sources = Set.copyOf(sources);
        boolean wantsRepository = sources.contains(ManifestSourceType.REPOSITORY_CHUNK);
        if (wantsRepository != (repository != null)) {
            throw new DomainValidationException(
                    "knowledgeRetrieval.repository",
                    "the repository target must be present exactly when REPOSITORY_CHUNK is selected");
        }
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new DomainValidationException(
                    "knowledgeRetrieval.topK", "must be between 1 and " + MAX_TOP_K);
        }
    }
}
