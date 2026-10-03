package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.KnowledgeCategory;
import java.util.Objects;
import java.util.Optional;

/**
 * Draft replacement and optional reclassification. An absent category keeps the head's
 * current classification; the category never enters the version content hash.
 */
public record UpdateKnowledgeDraftCommand(
        String title, String content, Optional<KnowledgeCategory> category) {

    public UpdateKnowledgeDraftCommand {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        category = Objects.requireNonNull(category, "category");
    }
}
