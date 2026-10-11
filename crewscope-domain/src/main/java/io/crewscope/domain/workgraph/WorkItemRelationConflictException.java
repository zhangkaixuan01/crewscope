package io.crewscope.domain.workgraph;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Map;
import java.util.Objects;

/** Reports an identical stored relation edge submitted again after normalization. */
public final class WorkItemRelationConflictException extends DomainException {

  public WorkItemRelationConflictException(WorkItemId src, WorkItemId dst) {
    super(
        new DomainError(
            DomainErrorCode.WORK_ITEM_RELATION_DUPLICATE,
            "WorkItem relation already exists",
            Map.of(
                "srcWorkItemId", Objects.requireNonNull(src, "src").toString(),
                "dstWorkItemId", Objects.requireNonNull(dst, "dst").toString())));
  }
}
