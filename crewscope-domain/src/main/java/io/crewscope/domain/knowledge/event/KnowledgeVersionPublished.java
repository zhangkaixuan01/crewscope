package io.crewscope.domain.knowledge.event;

import io.crewscope.domain.knowledge.KnowledgeContentHash;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.UUID;

/**
 * Version 1 business payload emitted after one Knowledge entry revision is published and
 * becomes the effective version for new retrieval requests. Consumers that clean derived
 * indexes must gate on the authoritative head pointer, never on the event order.
 */
public record KnowledgeVersionPublished(
        UUID entryId,
        String entryKey,
        long revision,
        String contentHash,
        String title)
        implements DomainEvent {

    public KnowledgeVersionPublished {
        entryId = AggregateId.requireValue(entryId, "KnowledgeVersionPublished.entryId");
        entryKey = new KnowledgeEntryKey(entryKey).value();
        if (revision < 1) {
            throw new DomainValidationException(
                    "knowledgeVersionPublished.revision", "must be positive");
        }
        contentHash = new KnowledgeContentHash(contentHash).value();
        if (title == null || title.isBlank()) {
            throw new DomainValidationException(
                    "knowledgeVersionPublished.title", "must not be blank");
        }
        title = title.strip();
        if (title.length() > KnowledgeEntryVersion.MAX_TITLE_LENGTH) {
            throw new DomainValidationException(
                    "knowledgeVersionPublished.title",
                    "must contain at most " + KnowledgeEntryVersion.MAX_TITLE_LENGTH
                            + " characters");
        }
    }

    /** Creates the fact from the appended immutable version and its entry key. */
    public static KnowledgeVersionPublished from(
            KnowledgeEntryKey entryKey, KnowledgeEntryVersion version) {
        KnowledgeEntryVersion source = Objects.requireNonNull(version, "version");
        return new KnowledgeVersionPublished(
                source.entryId().value(),
                Objects.requireNonNull(entryKey, "entryKey").value(),
                source.revision().value(),
                source.contentHash().value(),
                source.title());
    }
}
