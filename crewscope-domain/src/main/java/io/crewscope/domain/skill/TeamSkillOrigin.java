package io.crewscope.domain.skill;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/**
 * Soft cross-aggregate reference to the completed Task execution attempt a Team Skill
 * was distilled from (ADR-031 §2). Deliberately not a foreign key: attribution outlives
 * the Task lifecycle. M10-A03a creates catalog entries without an origin — the A03b
 * distillation entry point fills it, which is why the columns ship in V61 already.
 *
 * @param taskExecutionId the execution the member explicitly selected for distillation
 * @param attempt the 1-based execution attempt that produced the source material
 */
public record TeamSkillOrigin(UUID taskExecutionId, int attempt) {

    public TeamSkillOrigin {
        taskExecutionId = AggregateId.requireValue(
                taskExecutionId, "TeamSkillOrigin.taskExecutionId");
        if (attempt < 1) {
            throw new IllegalArgumentException("TeamSkillOrigin.attempt must be positive");
        }
    }

    @Override
    public String toString() {
        return "TeamSkillOrigin[taskExecutionId=" + taskExecutionId + ", attempt=" + attempt + "]";
    }
}
