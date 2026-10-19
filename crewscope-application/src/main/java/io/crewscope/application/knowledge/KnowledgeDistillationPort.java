package io.crewscope.application.knowledge;

import io.crewscope.application.distiller.DistillationCallAttribution;
import io.crewscope.application.distiller.DistillationCallPort;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Asynchronous Port for one no-Tool AgentScope Knowledge Distiller structured-output call.
 * The adapter resolves the Team's built-in knowledge-distiller@1 profile through the full
 * governance chain before the call and reports per-attempt token usage afterwards.
 */
@FunctionalInterface
public interface KnowledgeDistillationPort
        extends DistillationCallPort<
                KnowledgeDistillationPort.KnowledgeDistillationRequest,
                KnowledgeDistillationPort.KnowledgeDistillationResult> {

    /** One sanitized distillation invocation; the source text is already whitelist-rendered. */
    record KnowledgeDistillationRequest(
            OrganizationId organizationId,
            TeamId teamId,
            TaskExecutionId taskExecutionId,
            int executionAttempt,
            String sanitizedSourceText,
            Optional<KnowledgeCategory> requestedCategory,
            PrincipalId initiatedBy,
            UUID commandId,
            UUID correlationId) {

        public KnowledgeDistillationRequest {
            organizationId = Objects.requireNonNull(organizationId, "organizationId");
            teamId = Objects.requireNonNull(teamId, "teamId");
            taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
            if (executionAttempt < 1) {
                throw new IllegalArgumentException("executionAttempt must be positive");
            }
            Objects.requireNonNull(sanitizedSourceText, "sanitizedSourceText");
            requestedCategory = Objects.requireNonNull(requestedCategory, "requestedCategory");
            initiatedBy = Objects.requireNonNull(initiatedBy, "initiatedBy");
            commandId = Objects.requireNonNull(commandId, "commandId");
            correlationId = Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    /** Model-produced draft plus the connection coordinates and per-attempt usage facts. */
    record KnowledgeDistillationResult(
            DistilledDraft draft,
            DistillationCallAttribution attribution) {

        public KnowledgeDistillationResult {
            draft = Objects.requireNonNull(draft, "draft");
            attribution = Objects.requireNonNull(attribution, "attribution");
        }
    }

    /** Title/content are model suggestions; a human curates through updateDraft. */
    record DistilledDraft(String title, String content, String suggestedCategory) {

        public DistilledDraft {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(suggestedCategory, "suggestedCategory");
        }
    }
}
