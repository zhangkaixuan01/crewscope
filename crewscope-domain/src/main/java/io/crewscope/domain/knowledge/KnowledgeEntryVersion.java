package io.crewscope.domain.knowledge;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamScope;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable published version of one Knowledge entry. Version rows are append-only and
 * carry no lifecycle of their own: whether a revision is currently effective is decided
 * solely by the head pointer {@link KnowledgeEntry#effectiveRevision()}, so replaying old
 * events can never resurrect a retired version (ADR-030 §2).
 */
public record KnowledgeEntryVersion(
        KnowledgeEntryId entryId,
        TeamScope scope,
        KnowledgeEntryRevision revision,
        Optional<KnowledgeEntryRevision> previousRevision,
        String title,
        String content,
        KnowledgeContentHash contentHash,
        AuditMetadata audit) {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_CONTENT_LENGTH = 65536;

    public KnowledgeEntryVersion {
        entryId = Objects.requireNonNull(entryId, "entryId");
        scope = Objects.requireNonNull(scope, "scope");
        revision = Objects.requireNonNull(revision, "revision");
        previousRevision = requirePreviousRevision(revision, previousRevision);
        title = KnowledgeDraft.requireTitle(title);
        content = KnowledgeDraft.requireContent(content);
        contentHash = Objects.requireNonNull(contentHash, "contentHash");
        if (!contentHash.equals(KnowledgeContentHash.of(title, content))) {
            throw new DomainValidationException(
                    "knowledgeEntryVersion.contentHash",
                    "must match the canonical digest of title and content");
        }
        audit = Objects.requireNonNull(audit, "audit");
    }

    /** Creates the immutable fact produced by {@link KnowledgeEntry#publish}. */
    public static KnowledgeEntryVersion create(
            KnowledgeEntryId entryId,
            TeamScope scope,
            KnowledgeEntryRevision revision,
            Optional<KnowledgeEntryRevision> previousRevision,
            String title,
            String content,
            PrincipalId publishedBy,
            UtcTimestamp occurredAt) {
        return new KnowledgeEntryVersion(
                entryId,
                scope,
                revision,
                previousRevision,
                title,
                content,
                KnowledgeContentHash.of(
                        KnowledgeDraft.requireTitle(title),
                        KnowledgeDraft.requireContent(content)),
                AuditMetadata.createdBy(
                        Objects.requireNonNull(publishedBy, "publishedBy"),
                        Objects.requireNonNull(occurredAt, "occurredAt")));
    }

    /** Restores a persisted row; the stored hash must still match the stored content. */
    public static KnowledgeEntryVersion reconstitute(
            KnowledgeEntryId entryId,
            TeamScope scope,
            KnowledgeEntryRevision revision,
            Optional<KnowledgeEntryRevision> previousRevision,
            String title,
            String content,
            KnowledgeContentHash contentHash,
            AuditMetadata audit) {
        return new KnowledgeEntryVersion(
                entryId, scope, revision, previousRevision, title, content, contentHash, audit);
    }

    private static Optional<KnowledgeEntryRevision> requirePreviousRevision(
            KnowledgeEntryRevision revision,
            Optional<KnowledgeEntryRevision> previousRevision) {
        Optional<KnowledgeEntryRevision> required =
                Objects.requireNonNull(previousRevision, "previousRevision");
        if (revision.value() == 1) {
            if (required.isPresent()) {
                throw new DomainValidationException(
                        "knowledgeEntryVersion.previousRevision",
                        "must be absent for the first revision");
            }
            return required;
        }
        if (required.isEmpty() || required.get().value() != revision.value() - 1) {
            throw new DomainValidationException(
                    "knowledgeEntryVersion.previousRevision",
                    "must be the immediately preceding revision");
        }
        return required;
    }
}
