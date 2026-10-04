package io.crewscope.application.skill;

import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/**
 * Member-initiated distillation command (M10-A03b): distill one completed Task
 * execution attempt into a new DRAFT Team Skill under the caller-chosen immutable
 * key. The key is command-owned (D4) — the model produces only the description and
 * body, and the server assembles the frontmatter naming exactly this key.
 */
public record DistillTeamSkillCommand(
        TaskExecutionId taskExecutionId,
        TeamSkillKey skillKey) {

    public DistillTeamSkillCommand {
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        skillKey = Objects.requireNonNull(skillKey, "skillKey");
    }
}
