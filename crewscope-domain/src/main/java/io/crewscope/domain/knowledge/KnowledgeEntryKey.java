package io.crewscope.domain.knowledge;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Stable, human-readable business key of one Knowledge entry inside its Team. */
public record KnowledgeEntryKey(String value) {

    public static final String FORMAT_REGEX = "[a-z0-9][a-z0-9-]{0,62}";

    private static final Pattern FORMAT = Pattern.compile(FORMAT_REGEX);

    public KnowledgeEntryKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new DomainValidationException(
                    "knowledgeEntry.entryKey", "must match " + FORMAT_REGEX);
        }
    }

    public static KnowledgeEntryKey parse(String value) {
        return new KnowledgeEntryKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
