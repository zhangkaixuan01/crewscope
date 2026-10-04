package io.crewscope.application.skill;

import io.crewscope.domain.skill.TeamSkillKey;
import java.util.Objects;
import java.util.Optional;

/**
 * Keyset pagination for the skill catalog listing, ordered by skill key ascending.
 * The cursor is the plain last-seen skill key; a missing key yields the first page.
 */
public record TeamSkillPageRequest(Optional<TeamSkillKey> afterSkillKey, int limit) {

    public TeamSkillPageRequest {
        afterSkillKey = Objects.requireNonNull(afterSkillKey, "afterSkillKey");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Team skill page limit must be within [1, 100]");
        }
    }
}
