package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;
import java.util.Optional;

/**
 * Member-initiated distillation command (A02b): distill one completed Task execution
 * attempt into a new DRAFT knowledge entry under the caller-chosen immutable key.
 */
public record DistillKnowledgeEntryCommand(
        TaskExecutionId taskExecutionId,
        KnowledgeEntryKey entryKey,
        Optional<KnowledgeCategory> category) {

    public DistillKnowledgeEntryCommand {
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        entryKey = Objects.requireNonNull(entryKey, "entryKey");
        category = Objects.requireNonNull(category, "category");
    }
}
