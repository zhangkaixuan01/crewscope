package io.crewscope.application.responsibility;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Objects;

/** Reads the active responsibility chain after validating WorkItem visibility. */
public final class ResponsibilityQueryService {

  private final ResponsibilityAssignmentRepository assignmentRepository;
  private final PrincipalRepository principalRepository;
  private final WorkItemAccessPolicy accessPolicy;
  private final TransactionExecutor transactionExecutor;

  public ResponsibilityQueryService(
      ResponsibilityAssignmentRepository assignmentRepository,
      PrincipalRepository principalRepository,
      WorkItemAccessPolicy accessPolicy,
      TransactionExecutor transactionExecutor) {
    this.assignmentRepository =
        Objects.requireNonNull(assignmentRepository, "assignmentRepository");
    this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
  }

  public List<ResponsibilityAssignmentView> listActive(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId) {
    return transactionExecutor.required(
        () -> {
          WorkItem item =
              accessPolicy.requireVisibleWorkItem(
                  context, organizationId, teamId, projectId, workItemId);
          return assignmentRepository
              .findActiveByWorkItem(organizationId, item.id())
              .stream()
              .map(
                  assignment -> {
                    Principal principal =
                        principalRepository
                            .findById(organizationId, assignment.actorPrincipalId())
                            .orElseThrow(
                                () ->
                                    new AggregateNotFoundException(
                                        "Principal", assignment.actorPrincipalId()));
                    return new ResponsibilityAssignmentView(assignment, principal.displayName());
                  })
              .toList();
        });
  }
}
