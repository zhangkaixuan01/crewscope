package io.crewscope.application.retrieval;

import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.List;
import java.util.Objects;

/**
 * One unified retrieval candidate (M10-A01, plan §4.2 first fact): the source identity,
 * its immutable version/commit coordinate, the content hash, the explainable score and
 * rank, and the content itself. Adjacent same-file chunk hits merge into one candidate
 * that keeps every fragment's exact span (S01 §3.4), so the presented candidate count
 * can only shrink, never inflate.
 */
public record RetrievalCandidate(
        ManifestSourceType source,
        int rank,
        double score,
        KnowledgeEntryHit entry,
        List<RepositoryFragment> fragments) {

    public RetrievalCandidate {
        Objects.requireNonNull(source, "source");
        if (rank < 1) {
            throw new DomainValidationException(
                    "retrievalCandidate.rank", "must be positive");
        }
        if (!Double.isFinite(score)) {
            throw new DomainValidationException(
                    "retrievalCandidate.score", "must be finite");
        }
        fragments = fragments == null ? List.of() : List.copyOf(fragments);
        if (source == ManifestSourceType.KNOWLEDGE_ENTRY) {
            Objects.requireNonNull(entry, "entry");
            if (!fragments.isEmpty()) {
                throw new DomainValidationException(
                        "retrievalCandidate.fragments",
                        "knowledge candidates carry no repository fragments");
            }
        } else if (source == ManifestSourceType.REPOSITORY_CHUNK) {
            if (entry != null) {
                throw new DomainValidationException(
                        "retrievalCandidate.entry",
                        "repository candidates carry no knowledge entry");
            }
            if (fragments.isEmpty()) {
                throw new DomainValidationException(
                        "retrievalCandidate.fragments",
                        "repository candidates keep at least one fragment");
            }
            String path = fragments.get(0).path();
            for (RepositoryFragment fragment : fragments) {
                if (!fragment.path().equals(path)) {
                    throw new DomainValidationException(
                            "retrievalCandidate.fragments",
                            "merged fragments must share one path");
                }
            }
        } else {
            throw new DomainValidationException(
                    "retrievalCandidate.source",
                    "unified retrieval only yields KNOWLEDGE_ENTRY and REPOSITORY_CHUNK");
        }
    }

    /** The effective-version hit: what the entry head pointed at when scored. */
    public record KnowledgeEntryHit(
            KnowledgeEntryId entryId,
            KnowledgeEntryRevision revision,
            String title,
            String contentHash,
            String content) {

        public KnowledgeEntryHit {
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(revision, "revision");
            title = Objects.requireNonNull(title, "title").strip();
            if (title.isEmpty()) {
                throw new DomainValidationException(
                        "knowledgeEntryHit.title", "must not be empty");
            }
            Objects.requireNonNull(contentHash, "contentHash");
            Objects.requireNonNull(content, "content");
        }
    }

    /** One exact chunk span inside the merged candidate's file. */
    public record RepositoryFragment(
            RepositoryBindingId bindingId,
            SourceCommit commit,
            long generationBuildSequence,
            int chunkSeq,
            String path,
            String language,
            int startLine,
            int endLine,
            String contentHash,
            String content) {

        public RepositoryFragment {
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(commit, "commit");
            if (generationBuildSequence < 1) {
                throw new DomainValidationException(
                        "repositoryFragment.generationBuildSequence", "must be positive");
            }
            if (chunkSeq < 1) {
                throw new DomainValidationException(
                        "repositoryFragment.chunkSeq", "must be positive");
            }
            path = Objects.requireNonNull(path, "path").strip();
            if (path.isEmpty()) {
                throw new DomainValidationException(
                        "repositoryFragment.path", "must not be empty");
            }
            language = Objects.requireNonNull(language, "language").strip();
            if (language.isEmpty()) {
                throw new DomainValidationException(
                        "repositoryFragment.language", "must not be empty");
            }
            if (startLine < 1 || endLine < startLine) {
                throw new DomainValidationException(
                        "repositoryFragment.lineSpan",
                        "must satisfy 1 <= startLine <= endLine");
            }
            Objects.requireNonNull(contentHash, "contentHash");
            Objects.requireNonNull(content, "content");
        }
    }
}
