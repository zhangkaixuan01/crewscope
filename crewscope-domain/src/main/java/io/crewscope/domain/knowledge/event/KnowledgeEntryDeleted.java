package io.crewscope.domain.knowledge.event;

import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/**
 * Version 1 business payload emitted after a Knowledge entry is deleted as an
 * irreversible tombstone. {@code lastEffectiveRevision} is zero when the entry was
 * never published; consumers must treat the tombstone as terminal.
 */
public record KnowledgeEntryDeleted(
        UUID entryId, String entryKey, long lastEffectiveRevision) implements DomainEvent {

    public KnowledgeEntryDeleted {
        entryId = AggregateId.requireValue(entryId, "KnowledgeEntryDeleted.entryId");
        entryKey = new KnowledgeEntryKey(entryKey).value();
        if (lastEffectiveRevision < 0) {
            throw new DomainValidationException(
                    "knowledgeEntryDeleted.lastEffectiveRevision", "must not be negative");
        }
    }

    /** Creates the tombstone fact; {@code lastEffectiveRevision} is zero when never published. */
    public static KnowledgeEntryDeleted of(
            UUID entryId, KnowledgeEntryKey entryKey, long lastEffectiveRevision) {
        return new KnowledgeEntryDeleted(entryId, entryKey.value(), lastEffectiveRevision);
    }
}
