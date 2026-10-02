package io.crewscope.domain.knowledge;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Mutable working copy of one Knowledge entry; drafts are never retrievable. */
public record KnowledgeDraft(String title, String content) {

    public KnowledgeDraft {
        title = requireTitle(title);
        content = requireContent(content);
    }

    static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new DomainValidationException("knowledgeDraft.title", "must not be blank");
        }
        String stripped = title.strip();
        if (stripped.length() > KnowledgeEntryVersion.MAX_TITLE_LENGTH) {
            throw new DomainValidationException(
                    "knowledgeDraft.title",
                    "must contain at most " + KnowledgeEntryVersion.MAX_TITLE_LENGTH
                            + " characters");
        }
        return stripped;
    }

    static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new DomainValidationException("knowledgeDraft.content", "must not be blank");
        }
        if (content.length() > KnowledgeEntryVersion.MAX_CONTENT_LENGTH) {
            throw new DomainValidationException(
                    "knowledgeDraft.content",
                    "must contain at most " + KnowledgeEntryVersion.MAX_CONTENT_LENGTH
                            + " characters");
        }
        return content;
    }

    public KnowledgeContentHash contentHash() {
        return KnowledgeContentHash.of(title, content);
    }
}
