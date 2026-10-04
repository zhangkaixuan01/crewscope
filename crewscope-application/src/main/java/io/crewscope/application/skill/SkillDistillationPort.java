package io.crewscope.application.skill;

import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelTokenUsage;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronous Port for one no-Tool AgentScope Skill Distiller structured-output call
 * (M10-A03b). The adapter resolves the Team's built-in skill-distiller@1 profile through
 * the full governance chain before the call and reports per-attempt token usage
 * afterwards, mirroring the Knowledge Distillation Port.
 */
@FunctionalInterface
public interface SkillDistillationPort {

    CompletionStage<SkillDistillationResult> distill(SkillDistillationRequest request);

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
            CallAttribution attribution) {

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

    /** Stable coordinates of the resolved call plus one usage entry per real attempt. */
    record CallAttribution(
            ModelProviderKey providerKey,
            ModelId modelId,
            ModelConnectionId connectionId,
            long connectionVersion,
            List<AttemptUsage> attempts) {

        public CallAttribution {
            providerKey = Objects.requireNonNull(providerKey, "providerKey");
            modelId = Objects.requireNonNull(modelId, "modelId");
            connectionId = Objects.requireNonNull(connectionId, "connectionId");
            if (connectionVersion < 0) {
                throw new IllegalArgumentException("connectionVersion must not be negative");
            }
            attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
            if (attempts.isEmpty()) {
                throw new IllegalArgumentException("attempts must not be empty");
            }
        }
    }

    /** Token quadruple of one real Provider attempt; zeros mean "not reported". */
    record AttemptUsage(int attempt, ModelTokenUsage usage) {

        public AttemptUsage {
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            usage = Objects.requireNonNull(usage, "usage");
        }
    }
}
