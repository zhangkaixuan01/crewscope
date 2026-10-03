package io.crewscope.domain.knowledge;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/**
 * Soft cross-aggregate reference to the completed Task execution attempt a distilled
 * entry was sourced from (ADR-030 §2 "knowledge entries carry their origin"). Deliberately
 * not a foreign key: attribution outlives the Task lifecycle and follows the entry forever.
 *
 * @param taskExecutionId the execution whose sanitized event stream fed the distillation
 * @param attempt the 1-based execution attempt that produced the source material
 */
public record KnowledgeEntryOrigin(UUID taskExecutionId, int attempt) {

    public KnowledgeEntryOrigin {
        taskExecutionId = AggregateId.requireValue(taskExecutionId, "KnowledgeEntryOrigin.taskExecutionId");
        if (attempt < 1) {
            throw new IllegalArgumentException("KnowledgeEntryOrigin.attempt must be positive");
        }
    }

    @Override
    public String toString() {
        return "KnowledgeEntryOrigin[taskExecutionId=" + taskExecutionId + ", attempt=" + attempt + "]";
    }
}
