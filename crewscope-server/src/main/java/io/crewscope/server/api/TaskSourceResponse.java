package io.crewscope.server.api;

import io.crewscope.domain.task.TaskInputReference;
import io.crewscope.domain.task.TaskSource;
import java.util.Optional;
import java.util.UUID;

/** The pinned Work Item provenance a Task was created from. */
public record TaskSourceResponse(
        String type,
        long workItemVersion,
        UUID conversationId,
        String inputType,
        UUID inputId,
        Long inputVersion) {
    static TaskSourceResponse from(TaskSource value) {
        TaskInputReference input = value.inputReference().orElse(null);
        return new TaskSourceResponse(
                value.type().name(),
                value.workItemVersion(),
                value.conversationId().map(id -> id.value()).orElse(null),
                input == null ? null : input.type().name(),
                input == null ? null : input.referenceId(),
                input == null ? null : input.referenceVersion());
    }
}
