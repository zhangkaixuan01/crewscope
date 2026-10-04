package io.crewscope.application.skill;

import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One bounded skill catalog page with the next keyset cursor, if more pages remain. */
public record TeamSkillPage(
        List<TeamSkill> items, Optional<TeamSkillKey> nextSkillKey) {

    public TeamSkillPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextSkillKey = Objects.requireNonNull(nextSkillKey, "nextSkillKey");
        if (items.isEmpty() && nextSkillKey.isPresent()) {
            throw new IllegalArgumentException(
                    "Empty team skill page cannot have a next cursor");
        }
    }
}
