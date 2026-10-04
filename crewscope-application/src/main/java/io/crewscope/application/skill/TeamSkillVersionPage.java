package io.crewscope.application.skill;

import io.crewscope.domain.skill.TeamSkillRevision;
import io.crewscope.domain.skill.TeamSkillVersion;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One bounded skill version-history page with the next keyset cursor, if more remain. */
public record TeamSkillVersionPage(
        List<TeamSkillVersion> items, Optional<TeamSkillRevision> nextRevision) {

    public TeamSkillVersionPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextRevision = Objects.requireNonNull(nextRevision, "nextRevision");
        if (items.isEmpty() && nextRevision.isPresent()) {
            throw new IllegalArgumentException(
                    "Empty team skill version page cannot have a next cursor");
        }
    }
}
