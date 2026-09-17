package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;

/**
 * The WorkItem facts a projection row must carry before anyone may judge its transitions.
 *
 * <p>A row that is not a WorkItem has none of these; it says so by carrying no subject at all rather
 * than by carrying a status string that happens to look like one. That keeps a list row from being
 * mistaken for a WorkItem whose transitions merely happen to be unavailable.
 *
 * <p>Deliberately facts only: whether the member may participate is a policy question, answered once
 * per request by {@link WorkItemAccessPolicy#resolvePermission} and never carried on a row.
 */
public record WorkItemTransitionSubject(
    WorkProjectId projectId, WorkItemStatus status, boolean nativeSource) {

  public WorkItemTransitionSubject {
    projectId = Objects.requireNonNull(projectId, "projectId");
    status = Objects.requireNonNull(status, "status");
  }
}
