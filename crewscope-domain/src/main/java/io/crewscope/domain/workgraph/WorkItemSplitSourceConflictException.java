package io.crewscope.domain.workgraph;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Map;
import java.util.Objects;

/**
 * Reports a second SPLIT_FROM source for a child that already has one — the split forest allows
 * at most one provenance edge per WorkItem (ADR-033).
 */
public final class WorkItemSplitSourceConflictException extends DomainException {

  public WorkItemSplitSourceConflictException(WorkItemId child) {
    super(
        new DomainError(
            DomainErrorCode.WORK_ITEM_RELATION_SPLIT_SOURCE_CONFLICT,
            "WorkItem already has a SPLIT_FROM source",
            Map.of(
                "srcWorkItemId",
                Objects.requireNonNull(child, "child").toString())));
  }
}
