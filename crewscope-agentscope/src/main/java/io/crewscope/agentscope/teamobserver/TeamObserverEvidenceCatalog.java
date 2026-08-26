package io.crewscope.agentscope.teamobserver;

import io.crewscope.application.teamobserver.output.TeamSummaryOutputEntryV1;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.teamobserver.TeamSummaryEntry;
import io.crewscope.domain.teamobserver.TeamSummarySection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Invocation-local authority mapping model-selected tuples back to authorized projection facts. */
final class TeamObserverEvidenceCatalog {

    private final Map<EvidenceIdentity, TeamSummaryEntry> entries = new LinkedHashMap<>();

    void observe(List<TeamSummaryEntry> values) {
        for (TeamSummaryEntry entry : List.copyOf(Objects.requireNonNull(values, "values"))) {
            TeamSummaryEntry required = Objects.requireNonNull(entry, "entry");
            EvidenceIdentity identity = EvidenceIdentity.from(required);
            TeamSummaryEntry previous = entries.putIfAbsent(identity, required);
            if (previous != null && !previous.equals(required)) {
                throw rejected();
            }
        }
    }

    List<TeamSummaryEntry> resolve(
            TeamSummarySection section,
            List<TeamSummaryOutputEntryV1> selected,
            int maximumItems) {
        List<TeamSummaryOutputEntryV1> values = List.copyOf(
                Objects.requireNonNull(selected, "selected"));
        if (values.size() > maximumItems) {
            throw rejected();
        }
        Set<EvidenceIdentity> seen = new HashSet<>();
        return values.stream().map(value -> {
            EvidenceIdentity identity = new EvidenceIdentity(
                    Objects.requireNonNull(section, "section"),
                    value.summary(),
                    value.evidencePath());
            if (!seen.add(identity)) {
                throw rejected();
            }
            TeamSummaryEntry entry = entries.get(identity);
            if (entry == null) {
                throw rejected();
            }
            return entry;
        }).toList();
    }

    private static DomainValidationException rejected() {
        return new DomainValidationException(
                "teamSummary.output",
                "must select unique exact facts returned by an authorized read-only Tool call");
    }

    private record EvidenceIdentity(
            TeamSummarySection section, String summary, String evidencePath) {

        private static EvidenceIdentity from(TeamSummaryEntry entry) {
            return new EvidenceIdentity(entry.section(), entry.summary(), entry.evidencePath());
        }
    }
}
