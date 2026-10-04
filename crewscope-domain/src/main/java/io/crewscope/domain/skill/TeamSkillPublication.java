package io.crewscope.domain.skill;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/**
 * Result of {@link TeamSkill#publish} and {@link TeamSkill#rollback}: the advanced head
 * and the appended immutable version. Persistence must commit both rows in one
 * transaction (M10-A03a port contract).
 */
public record TeamSkillPublication(TeamSkill skill, TeamSkillVersion version) {

    public TeamSkillPublication {
        Objects.requireNonNull(skill, "skill");
        Objects.requireNonNull(version, "version");
        if (!skill.id().equals(version.skillId())
                || !skill.scope().equals(version.scope())) {
            throw new DomainValidationException(
                    "teamSkillPublication",
                    "skill and version must belong to the same aggregate");
        }
    }
}
