package io.crewscope.application.skill;

import io.crewscope.application.distiller.DistillationCallAttribution;
import io.crewscope.application.distiller.DistillationCallPort;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;
import java.util.UUID;

/**
 * Asynchronous Port for one no-Tool AgentScope Skill Distiller structured-output call
 * (M10-A03b). The adapter resolves the Team's built-in skill-distiller@1 profile through
 * the full governance chain before the call and reports per-attempt token usage
 * afterwards, mirroring the Knowledge Distillation Port.
 */
@FunctionalInterface
public interface SkillDistillationPort
        extends DistillationCallPort<
                SkillDistillationPort.SkillDistillationRequest,
                SkillDistillationPort.SkillDistillationResult> {

    /** One sanitized distillation invocation; the source text is already whitelist-rendered. */
    record SkillDistillationRequest(
            OrganizationId organizationId,
            TeamId teamId,
            TaskExecutionId taskExecutionId,
            int executionAttempt,
            TeamSkillKey skillKey,
            String sanitizedSourceText,
            PrincipalId initiatedBy,
            UUID commandId,
            UUID correlationId) {

        public SkillDistillationRequest {
            organizationId = Objects.requireNonNull(organizationId, "organizationId");
            teamId = Objects.requireNonNull(teamId, "teamId");
            taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
            if (executionAttempt < 1) {
                throw new IllegalArgumentException("executionAttempt must be positive");
            }
            skillKey = Objects.requireNonNull(skillKey, "skillKey");
            Objects.requireNonNull(sanitizedSourceText, "sanitizedSourceText");
            initiatedBy = Objects.requireNonNull(initiatedBy, "initiatedBy");
            commandId = Objects.requireNonNull(commandId, "commandId");
            correlationId = Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    /** Model-produced draft plus the connection coordinates and per-attempt usage facts. */
    record SkillDistillationResult(
            DistilledSkillDraft draft,
            DistillationCallAttribution attribution) {

        public SkillDistillationResult {
            draft = Objects.requireNonNull(draft, "draft");
            attribution = Objects.requireNonNull(attribution, "attribution");
        }
    }

    /**
     * Description and Markdown body are model suggestions under the template's output
     * schema bounds; the skill key itself is command-owned (D4), so the server assembles
     * the final frontmatter and the human curates through the A03a draft surface.
     */
    record DistilledSkillDraft(String description, String body) {

        public DistilledSkillDraft {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(body, "body");
        }
    }
}
