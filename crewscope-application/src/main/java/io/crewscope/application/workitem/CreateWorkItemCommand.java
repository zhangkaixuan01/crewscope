package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWorkItemCommand(
        @NotNull WorkProjectId projectId,
        @NotBlank
                @Size(max = WorkItemKey.MAX_LENGTH)
                @Pattern(regexp = WorkItemKey.FORMAT_REGEX)
                String key,
        @NotBlank @Size(max = WorkItem.MAX_TITLE_LENGTH) String title) {}
