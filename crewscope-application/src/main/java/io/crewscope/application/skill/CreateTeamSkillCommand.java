package io.crewscope.application.skill;

import io.crewscope.domain.skill.TeamSkillKey;
import java.util.Objects;

/**
 * Catalog-entry creation command (A03a). The SKILL.md document bounds are enforced by
 * the domain draft, including the frontmatter name equaling the skill key.
 */
public record CreateTeamSkillCommand(TeamSkillKey skillKey, String content) {

    public CreateTeamSkillCommand {
        Objects.requireNonNull(skillKey, "skillKey");
        Objects.requireNonNull(content, "content");
    }
}
