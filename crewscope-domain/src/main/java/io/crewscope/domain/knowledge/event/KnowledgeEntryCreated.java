package io.crewscope.domain.knowledge.event;

import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.UUID;

/** Version 1 business payload emitted after a Knowledge entry is created as DRAFT. */
public record KnowledgeEntryCreated(
        UUID entryId, String entryKey, String title, String category)
        implements DomainEvent {

    public KnowledgeEntryCreated {
        entryId = AggregateId.requireValue(entryId, "KnowledgeEntryCreated.entryId");
        entryKey = new KnowledgeEntryKey(entryKey).value();
        if (title == null || title.isBlank()) {
            throw new DomainValidationException(
                    "knowledgeEntryCreated.title", "must not be blank");
        }
        title = title.strip();
        if (title.length() > KnowledgeEntryVersion.MAX_TITLE_LENGTH) {
            throw new DomainValidationException(
                    "knowledgeEntryCreated.title",
                    "must contain at most " + KnowledgeEntryVersion.MAX_TITLE_LENGTH
                            + " characters");
        }
        category = parseCategory(category);
    }

    /** Creates the fact from the committed DRAFT entry. */
    public static KnowledgeEntryCreated from(KnowledgeEntry entry) {
        KnowledgeEntry source = Objects.requireNonNull(entry, "entry");
        return new KnowledgeEntryCreated(
                source.id().value(),
                source.entryKey().value(),
                source.draft().orElseThrow(() -> new DomainValidationException(
                        "knowledgeEntryCreated.title", "requires the initial draft")).title(),
                source.category().name());
    }

    private static String parseCategory(String category) {
        try {
            return KnowledgeCategory.valueOf(category).name();
        } catch (IllegalArgumentException missingOrUnknown) {
            throw new DomainValidationException(
                    "knowledgeEntryCreated.category", "must be a known knowledge category");
        }
    }
}
