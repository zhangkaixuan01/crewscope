package io.crewscope.domain.workgraph;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Map;
import java.util.Objects;

/**
 * Reports a relation that would close a cycle: a blocking edge whose src is already reachable
 * from its dst, or a SPLIT_FROM edge whose child already sits on the source's ancestor chain
 * (ADR-033).
 */
public final class WorkItemRelationCycleException extends DomainException {

  public WorkItemRelationCycleException(WorkItemId src, WorkItemId dst) {
    super(
        new DomainError(
            DomainErrorCode.WORK_ITEM_RELATION_CYCLE,
            "WorkItem relation would close a cycle",
            Map.of(
                "srcWorkItemId", Objects.requireNonNull(src, "src").toString(),
                "dstWorkItemId", Objects.requireNonNull(dst, "dst").toString())));
  }
}
