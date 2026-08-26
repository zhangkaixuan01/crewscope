package io.crewscope.application.teamobserver.output;

import io.crewscope.domain.teamobserver.TeamSummaryEntry;
import java.util.Objects;

/** Minimal model-selected evidence tuple; authority is recovered from the Tool evidence catalog. */
public record TeamSummaryOutputEntryV1(String summary, String evidencePath) {

    public TeamSummaryOutputEntryV1 {
        summary = requireText(summary, "summary", TeamSummaryEntry.MAX_SUMMARY_LENGTH);
        evidencePath = requireText(
                evidencePath, "evidencePath", TeamSummaryEntry.MAX_EVIDENCE_PATH_LENGTH);
    }

    private static String requireText(String value, String field, int maximumLength) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is outside its bounded length");
        }
        return normalized;
    }
}
