package io.crewscope.application.teamobserver;

import io.crewscope.domain.teamobserver.TeamSummaryDataScope;
import io.crewscope.domain.teamobserver.TeamSummarySection;
import java.util.Objects;

/** Reauthorized link selected from one completed invocation's immutable structured result. */
public record TeamObserverEvidenceLink(
        int index,
        TeamSummarySection section,
        TeamSummaryDataScope dataScope,
        String summary,
        String path) {

    public TeamObserverEvidenceLink {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        section = Objects.requireNonNull(section, "section");
        dataScope = Objects.requireNonNull(dataScope, "dataScope");
        summary = Objects.requireNonNull(summary, "summary");
        path = Objects.requireNonNull(path, "path");
    }
}
