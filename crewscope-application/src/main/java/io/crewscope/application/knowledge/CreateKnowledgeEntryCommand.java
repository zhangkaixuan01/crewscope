package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import java.util.Objects;

/**
 * Manual-entry creation command (A02a). Title and content bounds are enforced by the
 * domain draft; overlength input is rejected, never silently truncated.
 */
public record CreateKnowledgeEntryCommand(
        KnowledgeEntryKey entryKey, KnowledgeCategory category, String title, String content) {

    public CreateKnowledgeEntryCommand {
        Objects.requireNonNull(entryKey, "entryKey");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
    }
}
