package io.crewscope.domain.skill;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;
import java.util.Objects;

/**
 * Reports a publish or rollback that would leave the effective content unchanged —
 * publishing a draft identical to the effective version, or rolling back onto the
 * currently effective revision. The command is a no-op, not a conflict: roll back to a
 * genuinely historical revision or edit the draft first.
 */
public final class TeamSkillVersionUnchangedException extends DomainException {

    public TeamSkillVersionUnchangedException(TeamSkillId skillId, long revision) {
        super(new DomainError(
                DomainErrorCode.SKILL_VERSION_UNCHANGED,
                "Team Skill content is unchanged",
                Map.of(
                        "skillId",
                        Objects.requireNonNull(skillId, "skillId").value().toString(),
                        "revision",
                        Long.toString(revision))));
    }
}
