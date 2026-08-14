package io.crewscope.domain.task;

import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentStatus;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.UUID;

/** Versioned reference to committed Conversation input; large message content stays at its source. */
public record TaskInputReference(
        TaskInputReferenceType type, UUID referenceId, long referenceVersion) {

    public TaskInputReference {
        type = Objects.requireNonNull(type, "type");
        referenceId = Objects.requireNonNull(referenceId, "referenceId");
        if (referenceVersion < 1) {
            throw new DomainValidationException(
                    "taskSource.inputReference.referenceVersion", "must be positive");
        }
    }

    public static TaskInputReference from(Message message) {
        Message required = Objects.requireNonNull(message, "message");
        return new TaskInputReference(
                TaskInputReferenceType.MESSAGE,
                required.id().value(),
                required.sequence().value());
    }

    public static TaskInputReference from(TaskIntent taskIntent) {
        TaskIntent required = Objects.requireNonNull(taskIntent, "taskIntent");
        if (required.status() != TaskIntentStatus.CONFIRMED) {
            throw new DomainValidationException(
                    "taskSource.inputReference", "must reference a confirmed TaskIntent");
        }
        return new TaskInputReference(
                TaskInputReferenceType.TASK_INTENT,
                required.id().value(),
                required.proposalRevision());
    }
}
