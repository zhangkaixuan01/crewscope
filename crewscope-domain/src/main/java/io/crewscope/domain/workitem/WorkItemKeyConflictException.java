package io.crewscope.domain.workitem;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;
import java.util.Objects;

/** Reports a WorkItem key that is already reserved inside the same WorkProject. */
public final class WorkItemKeyConflictException extends DomainException {

  public WorkItemKeyConflictException(WorkProjectId projectId, WorkItemKey key) {
    super(
        new DomainError(
            DomainErrorCode.WORK_ITEM_KEY_CONFLICT,
            "WorkItem key is already used in this WorkProject",
            Map.of(
                "projectId", Objects.requireNonNull(projectId, "projectId").toString(),
                "workItemKey", Objects.requireNonNull(key, "key").value())));
  }
}
