package io.crewscope.domain.skill;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.team.TeamScope;
import java.util.Map;
import java.util.Objects;

/** Reports a Team Skill key already present inside the same Team scope. */
public final class TeamSkillKeyConflictException extends DomainException {

    public TeamSkillKeyConflictException(TeamScope scope, TeamSkillKey skillKey) {
        super(new DomainError(
                DomainErrorCode.SKILL_KEY_CONFLICT,
                "Team Skill key is already present in this Team",
                Map.of(
                        "organizationId",
                        Objects.requireNonNull(scope, "scope").organizationId().toString(),
                        "teamId",
                        scope.teamId().toString(),
                        "skillKey",
                        Objects.requireNonNull(skillKey, "skillKey").value())));
    }
}
