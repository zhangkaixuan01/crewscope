package io.crewscope.domain.skill;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;

/**
 * Reports a Team Skill write command while the deployment switch is off. Reads stay
 * available — sealed history is a fact — but no new Team Skill may be drafted, revised,
 * published, disabled or rolled back until {@code crewscope.skill.enabled} is on
 * (plan §10.7: the switch stops the Team Skill chain, not the built-in skill).
 */
public final class TeamSkillDisabledException extends DomainException {

    public TeamSkillDisabledException() {
        super(new DomainError(
                DomainErrorCode.SKILL_DISABLED,
                "Team Skill chain is disabled by the deployment switch",
                Map.of("switch", "crewscope.skill.enabled")));
    }
}
