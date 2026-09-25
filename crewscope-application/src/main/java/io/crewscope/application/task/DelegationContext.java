package io.crewscope.application.task;

import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItemStatus;
import java.util.List;
import java.util.Optional;

/**
 * Read model behind the unified delegation form (M9b-A05): the current responsibility chain, the
 * Agent candidate set with per-candidate conflicts, the A04 project execution defaults, whether an
 * execution is already in flight, and the caller's own authority — one request, no client-side
 * re-assembly. Facts only: the model/config preflight stays on its own endpoint.
 */
public record DelegationContext(
    WorkItemLine workItem,
    List<ResponsibilityLine> responsibilities,
    List<AgentCandidate> candidates,
    DefaultsSnapshot defaults,
    boolean activeExecution,
    Permissions permissions) {

  /** Candidate classification for the form's assignability display. */
  public static final String STATE_ASSIGNED = "ASSIGNED";
  public static final String STATE_AVAILABLE = "AVAILABLE";
  public static final String STATE_EXECUTOR_CONFLICT = "EXECUTOR_CONFLICT";
  public static final String STATE_AGENT_DISABLED = "AGENT_DISABLED";
  public static final String STATE_PRINCIPAL_INACTIVE = "PRINCIPAL_INACTIVE";

  public record WorkItemLine(
      WorkItemId id,
      WorkProjectId projectId,
      long version,
      String title,
      WorkItemStatus status) {}

  public record ResponsibilityLine(
      ResponsibilityAssignmentId assignmentId,
      long version,
      ResponsibilityRole role,
      PrincipalId actorPrincipalId,
      String actorType,
      String actorDisplayName,
      Optional<AgentProfileId> actorAgentProfileId) {}

  /**
   * One Agent profile that could receive the work. {@code state} explains assignability:
   * ASSIGNED means an identical active EXECUTOR already exists (reused, never re-assigned);
   * EXECUTOR_CONFLICT means a different active Executor must be released explicitly first.
   */
  public record AgentCandidate(
      AgentProfileId agentProfileId,
      long agentProfileVersion,
      PrincipalId agentPrincipalId,
      String displayName,
      String ownershipType,
      String runtimeRole,
      String state,
      Optional<String> reason) {}

  /** Raw A04 resolver output; source/availability/reason are derived at the edge like A04. */
  public record DefaultsSnapshot(
      long version,
      Optional<RepositoryBindingId> repositoryBindingId,
      Optional<Long> repositoryBindingVersion,
      Optional<RepositoryBranchName> branch,
      Optional<BuildProfileReference> buildProfile,
      Optional<AgentProfileId> agentProfileId,
      Optional<Long> agentProfileRevision) {}

  /** Server-computed authority facts; the form never guesses permissions client-side. */
  public record Permissions(boolean canAssignResponsibility, boolean canDelegate) {}
}
