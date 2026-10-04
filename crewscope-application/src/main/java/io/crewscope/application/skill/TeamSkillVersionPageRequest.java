package io.crewscope.application.skill;

import io.crewscope.domain.skill.TeamSkillRevision;
import java.util.Objects;
import java.util.Optional;

/**
 * Keyset pagination for one skill's version history, ordered by revision ascending.
 * The cursor is the plain last-seen revision; a missing revision yields the first page.
 */
public record TeamSkillVersionPageRequest(
        Optional<TeamSkillRevision> afterRevision, int limit) {

    public TeamSkillVersionPageRequest {
        afterRevision = Objects.requireNonNull(afterRevision, "afterRevision");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Team skill version page limit must be within [1, 100]");
        }
    }
}
