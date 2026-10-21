package io.crewscope.domain.knowledge;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;
import java.util.Objects;

/**
 * Reports a rollback that would leave the effective content unchanged — rolling back
 * onto the currently effective revision. The command is a no-op, not a conflict: roll
 * back to a genuinely historical revision instead (M13b-A05, mirroring the Skill face).
 */
public final class KnowledgeVersionUnchangedException extends DomainException {

    public KnowledgeVersionUnchangedException(KnowledgeEntryId entryId, long revision) {
        super(new DomainError(
                DomainErrorCode.KNOWLEDGE_VERSION_UNCHANGED,
                "Knowledge entry content is unchanged",
                Map.of(
                        "entryId",
                        Objects.requireNonNull(entryId, "entryId").value().toString(),
                        "revision",
                        Long.toString(revision))));
    }
}
