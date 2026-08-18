package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;

/** Bounded safe summary; full command and report content remains in ArtifactStore. */
public record EvidenceSummary(String value) {

    public static final int MAX_BYTES = 4_096;
    public static final int MAX_LINES = 100;

    public EvidenceSummary {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid();
        }
        value = value.strip();
        if (value.isEmpty() || value.indexOf('\0') >= 0 || countLines(value) > MAX_LINES) {
            throw invalid();
        }
    }

    private static int countLines(String value) {
        int lines = 1;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static DomainValidationException invalid() {
        return new DomainValidationException(
                "evidence.summary", "must be non-empty bounded UTF-8 text without NUL");
    }

    @Override
    public String toString() {
        return value;
    }
}
