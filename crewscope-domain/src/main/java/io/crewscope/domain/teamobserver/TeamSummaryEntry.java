package io.crewscope.domain.teamobserver;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;

/** One sanitized member-visible summary statement and its continuously authorized evidence path. */
public record TeamSummaryEntry(
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId visibleToMemberId,
        TeamSummarySection section,
        TeamSummaryDataScope dataScope,
        String summary,
        String evidencePath) {

    public static final int MAX_SUMMARY_LENGTH = 1_000;
    public static final int MAX_EVIDENCE_PATH_LENGTH = 512;

    public TeamSummaryEntry {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        visibleToMemberId = Objects.requireNonNull(visibleToMemberId, "visibleToMemberId");
        section = Objects.requireNonNull(section, "section");
        dataScope = Objects.requireNonNull(dataScope, "dataScope");
        if (!dataScope.allowedSections().contains(section)) {
            throw new DomainValidationException(
                    "teamSummary.dataScope", "cannot contribute to the requested summary section");
        }
        summary = requireSummary(summary);
        evidencePath = requireEvidencePath(evidencePath);
    }

    private static String requireSummary(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("teamSummary.summary", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_SUMMARY_LENGTH
                || normalized.codePoints().anyMatch(character ->
                        Character.getType(character) == Character.FORMAT
                                || Character.isISOControl(character)
                                        && character != '\n'
                                        && character != '\t')) {
            throw new DomainValidationException(
                    "teamSummary.summary", "must be bounded sanitized text");
        }
        return normalized;
    }

    private static String requireEvidencePath(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("teamSummary.evidencePath", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_EVIDENCE_PATH_LENGTH
                || !normalized.startsWith("/")
                || normalized.startsWith("//")
                || normalized.contains("..")
                || normalized.contains("\\")
                || normalized.contains("%")
                || normalized.contains("://")
                || normalized.contains("?")
                || normalized.contains("#")
                || normalized.codePoints().anyMatch(character ->
                        Character.isWhitespace(character)
                                || Character.isISOControl(character)
                                || Character.getType(character) == Character.FORMAT)) {
            throw new DomainValidationException(
                    "teamSummary.evidencePath",
                    "must be a bounded canonical internal path without encoded data or traversal");
        }
        return normalized;
    }
}
