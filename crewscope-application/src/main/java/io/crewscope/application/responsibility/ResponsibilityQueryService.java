package io.crewscope.application.responsibility;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.AgentProfileRepository;
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
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Reads the active responsibility chain after validating WorkItem visibility. */
public final class ResponsibilityQueryService {

  private final ResponsibilityAssignmentRepository assignmentRepository;
  private final PrincipalRepository principalRepository;
  private final AgentProfileRepository agentProfileRepository;
  private final WorkItemAccessPolicy accessPolicy;
  private final TransactionExecutor transactionExecutor;

  public ResponsibilityQueryService(
      ResponsibilityAssignmentRepository assignmentRepository,
      PrincipalRepository principalRepository,
      AgentProfileRepository agentProfileRepository,
      WorkItemAccessPolicy accessPolicy,
      TransactionExecutor transactionExecutor) {
    this.assignmentRepository =
        Objects.requireNonNull(assignmentRepository, "assignmentRepository");
    this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
    this.agentProfileRepository = Objects.requireNonNull(
        agentProfileRepository, "agentProfileRepository");
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
                    Optional<AgentProfileId> profileId = assignment.actorType().isAgent()
                        ? agentProfileRepository
                            .findActiveByAgentPrincipalId(
                                organizationId, assignment.actorPrincipalId())
                            .filter(profile -> profile.scope().teamId()
                                .filter(teamId::equals).isPresent())
                            .filter(profile -> profile.workspaceId().equals(item.scope().workspaceId()))
                            .map(profile -> profile.id())
                        : Optional.empty();
                    return new ResponsibilityAssignmentView(
                        assignment, principal.displayName(), profileId);
                  })
              .toList();
        });
  }
}
