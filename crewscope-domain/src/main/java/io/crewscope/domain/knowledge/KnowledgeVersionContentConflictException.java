package io.crewscope.domain.knowledge;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;
import java.util.Objects;

/**
 * Reports a publish whose content hash already exists on the same entry: re-publishing
 * unchanged content is a detectable conflict, not a new revision (ADR-030 §2).
 */
public final class KnowledgeVersionContentConflictException extends DomainException {

    public KnowledgeVersionContentConflictException(
            KnowledgeEntryId entryId, KnowledgeEntryRevision revision, KnowledgeContentHash contentHash) {
        super(new DomainError(
                DomainErrorCode.KNOWLEDGE_VERSION_CONTENT_CONFLICT,
                "Knowledge entry already contains a version with this content",
                Map.of(
                        "entryId",
                        Objects.requireNonNull(entryId, "entryId").toString(),
                        "attemptedRevision",
                        Objects.requireNonNull(revision, "revision").toString(),
                        "contentHash",
                        Objects.requireNonNull(contentHash, "contentHash").value())));
    }
}
