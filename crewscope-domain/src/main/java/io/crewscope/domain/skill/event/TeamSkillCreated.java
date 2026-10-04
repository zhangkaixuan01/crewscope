package io.crewscope.domain.skill.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillDraft;
import io.crewscope.domain.skill.TeamSkillKey;
import java.util.Objects;
import java.util.UUID;

/** Version 1 business payload emitted after a Team Skill catalog entry is created as DRAFT. */
public record TeamSkillCreated(UUID skillId, String skillKey, String description)
        implements DomainEvent {

    public TeamSkillCreated {
        skillId = AggregateId.requireValue(skillId, "TeamSkillCreated.skillId");
        skillKey = new TeamSkillKey(skillKey).value();
        if (description == null || description.isBlank()) {
            throw new DomainValidationException(
                    "teamSkillCreated.description", "must not be blank");
        }
        description = description.strip();
        if (description.length() > TeamSkillDraft.MAX_DESCRIPTION_LENGTH) {
            throw new DomainValidationException(
                    "teamSkillCreated.description",
                    "must contain at most " + TeamSkillDraft.MAX_DESCRIPTION_LENGTH
                            + " characters");
        }
    }

    /** Creates the fact from the committed DRAFT head. */
    public static TeamSkillCreated from(TeamSkill skill) {
        TeamSkill source = Objects.requireNonNull(skill, "skill");
        return new TeamSkillCreated(
                source.id().value(),
                source.skillKey().value(),
                source.draft().orElseThrow(() -> new DomainValidationException(
                        "teamSkillCreated.description", "requires the initial draft"))
                        .description());
    }
}
