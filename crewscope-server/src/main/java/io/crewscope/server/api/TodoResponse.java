package io.crewscope.server.api;

import io.crewscope.domain.task.TodoSummaryItem;

/** One todo line of the plan summary. */
public record TodoResponse(
        String content, String status, String priority, String planStepKey) {
    static TodoResponse from(TodoSummaryItem value) {
        return new TodoResponse(
                value.content(),
                value.status().name(),
                value.priority().orElse(null),
                value.planStepKey().orElse(null));
    }
}
