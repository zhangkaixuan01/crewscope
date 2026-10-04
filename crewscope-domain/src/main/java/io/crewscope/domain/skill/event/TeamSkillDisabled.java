package io.crewscope.domain.skill.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillKey;
import java.util.Objects;
import java.util.UUID;

/**
 * Version 1 business payload emitted after a Team Skill head is disabled. The head keeps
 * its last effective revision and draft as historical evidence; later executions must
 * not load a disabled skill (ADR-031 §4).
 */
public record TeamSkillDisabled(UUID skillId, String skillKey, String reason)
        implements DomainEvent {

    public TeamSkillDisabled {
        skillId = AggregateId.requireValue(skillId, "TeamSkillDisabled.skillId");
        skillKey = new TeamSkillKey(skillKey).value();
        if (reason != null) {
            reason = reason.strip();
            if (reason.isEmpty()) {
                reason = null;
            } else if (reason.length() > TeamSkill.MAX_DISABLE_REASON_LENGTH) {
                throw new DomainValidationException(
                        "teamSkillDisabled.reason",
                        "must contain at most " + TeamSkill.MAX_DISABLE_REASON_LENGTH
                                + " characters");
            }
        }
    }

    /** Creates the fact from the disabled head. */
    public static TeamSkillDisabled from(TeamSkill skill) {
        TeamSkill source = Objects.requireNonNull(skill, "skill");
        return new TeamSkillDisabled(
                source.id().value(),
                source.skillKey().value(),
                source.disableReason().orElse(null));
    }
}
