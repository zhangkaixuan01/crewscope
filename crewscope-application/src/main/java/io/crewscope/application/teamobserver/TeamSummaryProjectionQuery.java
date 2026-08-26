package io.crewscope.application.teamobserver;

import io.crewscope.domain.teamobserver.TeamSummaryDataScope;
import io.crewscope.domain.teamobserver.TeamSummaryRequest;
import java.util.Objects;

/** One bounded, member-scoped read against an approved Team Observer projection family. */
public record TeamSummaryProjectionQuery(
        TeamSummaryRequest request, TeamSummaryDataScope dataScope, int limit) {

    public TeamSummaryProjectionQuery {
        request = Objects.requireNonNull(request, "request");
        dataScope = Objects.requireNonNull(dataScope, "dataScope");
        if (limit < 1 || limit > request.maxItemsPerSection()) {
            throw new IllegalArgumentException(
                    "Team Observer projection limit must be within the authorized request limit");
        }
    }
}
