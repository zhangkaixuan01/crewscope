package io.crewscope.domain.knowledge.event;

import io.crewscope.domain.knowledge.KnowledgeContentHash;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.UUID;

/**
 * Version 1 business payload emitted after the effective version of one Knowledge entry
 * is retired. The retired revision stays persisted for attribution but is no longer
 * retrievable; replaying this event must never demote a newer effective revision.
 */
public record KnowledgeVersionRetired(
        UUID entryId, String entryKey, long retiredRevision, String contentHash)
        implements DomainEvent {

    public KnowledgeVersionRetired {
        entryId = AggregateId.requireValue(entryId, "KnowledgeVersionRetired.entryId");
        entryKey = new KnowledgeEntryKey(entryKey).value();
        if (retiredRevision < 1) {
            throw new DomainValidationException(
                    "knowledgeVersionRetired.retiredRevision", "must be positive");
        }
        contentHash = new KnowledgeContentHash(contentHash).value();
    }

    /** Creates the fact for the revision that was effective immediately before retiring. */
    public static KnowledgeVersionRetired of(
            UUID entryId, KnowledgeEntryKey entryKey, long retiredRevision, String contentHash) {
        Objects.requireNonNull(entryKey, "entryKey");
        return new KnowledgeVersionRetired(
                entryId, entryKey.value(), retiredRevision, contentHash);
    }
}
