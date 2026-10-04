package io.crewscope.domain.skill;

import io.crewscope.domain.shared.error.DomainValidationException;

/**
 * Mutable working draft of one Team Skill: the full SKILL.md document exactly as the
 * runtime repository will serve it — a YAML frontmatter block with {@code name} and
 * {@code description}, followed by the Markdown body. Parsing here is intentionally
 * minimal (flat {@code key: value} lines, no YAML library — the domain stays
 * framework-free and the built-in SKILL.md is exactly this shape). The parsed name and
 * description are derived on demand for validation only; the stored document is the
 * truth, so they are not record components.
 */
public record TeamSkillDraft(String content) {

    public static final int MAX_CONTENT_LENGTH = 65536;
    public static final int MAX_DESCRIPTION_LENGTH = 200;

    public TeamSkillDraft(String content) {
        if (content == null || content.isBlank()) {
            throw new DomainValidationException("teamSkillDraft.content", "must not be blank");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new DomainValidationException(
                    "teamSkillDraft.content",
                    "must contain at most " + MAX_CONTENT_LENGTH + " characters");
        }
        this.content = content.stripTrailing();
        parseFrontmatter(this.content);
    }

    /** Frontmatter {@code name}; the head validates it equals the catalog skill key. */
    public String name() {
        return parseFrontmatter(content)[0];
    }

    /** Frontmatter {@code description}; a short human-facing summary. */
    public String description() {
        return parseFrontmatter(content)[1];
    }

    public SkillContentHash contentHash() {
        return SkillContentHash.of(content);
    }

    /**
     * Splits the document into {@code [name, description]}. Requires the leading
     * {@code ---} fence, a {@code name:} and a {@code description:} line inside, a
     * closing fence and a non-blank body after it.
     */
    private static String[] parseFrontmatter(String content) {
        String[] lines = content.split("\n", -1);
        if (!lines[0].strip().equals("---")) {
            throw new DomainValidationException(
                    "teamSkillDraft.content", "must start with a YAML frontmatter fence '---'");
        }
        String name = null;
        String description = null;
        int closing = -1;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.strip().equals("---")) {
                closing = index;
                break;
            }
            String stripped = line.strip();
            if (stripped.startsWith("name:")) {
                if (name != null) {
                    throw new DomainValidationException(
                            "teamSkillDraft.content", "frontmatter carries a duplicate 'name:' field");
                }
                name = requireValue(stripped.substring("name:".length()), "name");
            } else if (stripped.startsWith("description:")) {
                if (description != null) {
                    throw new DomainValidationException(
                            "teamSkillDraft.content",
                            "frontmatter carries a duplicate 'description:' field");
                }
                description = requireValue(
                        stripped.substring("description:".length()), "description");
            }
        }
        if (closing == -1) {
            throw new DomainValidationException(
                    "teamSkillDraft.content", "frontmatter is not closed by a '---' fence");
        }
        if (name == null) {
            throw new DomainValidationException(
                    "teamSkillDraft.content", "frontmatter must carry a 'name:' field");
        }
        if (description == null) {
            throw new DomainValidationException(
                    "teamSkillDraft.content", "frontmatter must carry a 'description:' field");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new DomainValidationException(
                    "teamSkillDraft.content",
                    "frontmatter description must contain at most "
                            + MAX_DESCRIPTION_LENGTH + " characters");
        }
        StringBuilder body = new StringBuilder();
        for (int index = closing + 1; index < lines.length; index++) {
            body.append(lines[index]).append('\n');
        }
        if (body.toString().isBlank()) {
            throw new DomainValidationException(
                    "teamSkillDraft.content", "must carry a non-blank Markdown body");
        }
        return new String[] {name, description};
    }

    private static String requireValue(String raw, String field) {
        String value = raw.strip();
        if (value.isEmpty()) {
            throw new DomainValidationException(
                    "teamSkillDraft.content", "frontmatter '" + field + ":' must not be blank");
        }
        return value;
    }
}
