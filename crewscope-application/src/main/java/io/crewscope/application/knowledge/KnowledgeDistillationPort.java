package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelTokenUsage;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronous Port for one no-Tool AgentScope Knowledge Distiller structured-output call.
 * The adapter resolves the Team's built-in knowledge-distiller@1 profile through the full
 * governance chain before the call and reports per-attempt token usage afterwards.
 */
@FunctionalInterface
public interface KnowledgeDistillationPort {

    CompletionStage<KnowledgeDistillationResult> distill(KnowledgeDistillationRequest request);

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
            CallAttribution attribution) {

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
