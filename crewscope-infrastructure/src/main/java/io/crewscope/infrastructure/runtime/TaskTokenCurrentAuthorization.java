package io.crewscope.infrastructure.runtime;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskTokenGrantScope;
import java.util.Objects;

/** Rebuilds every mutable execution, responsibility and membership fact for a Task Token. */
public final class TaskTokenCurrentAuthorization {

    private final TaskExecutionRepository executionRepository;
    private final TaskRepository taskRepository;
    private final PrincipalRepository principalRepository;
    private final ResponsibilityAssignmentRepository assignmentRepository;
    private final TeamMemberRepository memberRepository;

    public TaskTokenCurrentAuthorization(
            TaskExecutionRepository executionRepository,
            TaskRepository taskRepository,
            PrincipalRepository principalRepository,
            ResponsibilityAssignmentRepository assignmentRepository,
            TeamMemberRepository memberRepository) {
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
        this.assignmentRepository = Objects.requireNonNull(
                assignmentRepository, "assignmentRepository");
        this.memberRepository = Objects.requireNonNull(memberRepository, "memberRepository");
    }

    /** Fails closed when a fact pinned at issuance is no longer the current authorization fact. */
    public void requireCurrent(TaskCredentialGrant grant) {
        TaskTokenGrantScope scope = Objects.requireNonNull(grant, "grant").scope();
        var organizationId = scope.workItemScope().organizationId();
        TaskExecution execution = executionRepository.findById(
                        organizationId, scope.taskExecutionId())
                .orElseThrow(TaskTokenCurrentAuthorization::invalidToken);
        var task = taskRepository.findById(organizationId, scope.taskId())
                .orElseThrow(TaskTokenCurrentAuthorization::invalidToken);
        var planning = execution.planningContext()
                .orElseThrow(TaskTokenCurrentAuthorization::invalidToken);
        ResponsibilityAssignment assignment = assignmentRepository.findById(
                        organizationId, scope.executionPrincipal().assignmentId())
                .orElseThrow(TaskTokenCurrentAuthorization::invalidToken);
        Principal principal = principalRepository.findById(
                        organizationId, scope.executionPrincipal().principalId())
                .orElseThrow(TaskTokenCurrentAuthorization::invalidToken);

        boolean current = execution.id().equals(scope.taskExecutionId())
                && execution.scope().equals(scope.workItemScope())
                && execution.taskId().equals(scope.taskId())
                && execution.attempt() == scope.attempt()
                && execution.lastFencingToken().filter(scope.fencingToken()::equals).isPresent()
                && planning.executionPrincipal().equals(scope.executionPrincipal())
                && planning.policySnapshotId().equals(scope.policySnapshotId())
                && planning.policySnapshotHash().equals(scope.policySnapshotHash())
                && planning.safetyOverlay().equals(scope.safetyOverlay())
                && task.scope().equals(scope.workItemScope())
                && task.id().equals(scope.taskId())
                && task.currentExecutionId().filter(scope.taskExecutionId()::equals).isPresent()
                && assignment.isActive()
                && assignment.role() == ResponsibilityRole.EXECUTOR
                && assignment.scope().equals(scope.workItemScope())
                && assignment.workItemId().equals(task.workItemId())
                && assignment.id().equals(scope.executionPrincipal().assignmentId())
                && assignment.version() == scope.executionPrincipal().assignmentVersion()
                && assignment.actorPrincipalId().equals(scope.executionPrincipal().principalId())
                && assignment.actorType() == principal.type()
                && principal.canAct()
                && principal.scope().organizationId().equals(organizationId)
                && principal.scope().teamId()
                        .map(scope.workItemScope().teamId()::equals)
                        .orElse(true);
        if (!current) {
            throw invalidToken();
        }
        requireCurrentMembership(scope, assignment, principal);
    }

    private void requireCurrentMembership(
            TaskTokenGrantScope scope,
            ResponsibilityAssignment assignment,
            Principal principal) {
        if (principal.type() == PrincipalType.USER) {
            var member = memberRepository.findById(
                            scope.workItemScope().organizationId(),
                            assignment.actorMemberId()
                                    .orElseThrow(TaskTokenCurrentAuthorization::invalidToken))
                    .orElseThrow(TaskTokenCurrentAuthorization::invalidToken);
            if (!member.canParticipate()
                    || !member.userPrincipalId().equals(principal.id())
                    || !member.scope().organizationId().equals(
                            scope.workItemScope().organizationId())
                    || !member.scope().teamId().equals(scope.workItemScope().teamId())) {
                throw invalidToken();
            }
            return;
        }
        if (principal.type().isAgent()) {
            var ownerId = principal.ownerPrincipalId()
                    .orElseThrow(TaskTokenCurrentAuthorization::invalidToken);
            Principal owner = principalRepository.findById(
                            scope.workItemScope().organizationId(), ownerId)
                    .orElseThrow(TaskTokenCurrentAuthorization::invalidToken);
            var ownerMembership = memberRepository.findByTeamAndUserPrincipalId(
                            scope.workItemScope().organizationId(),
                            scope.workItemScope().teamId(),
                            ownerId)
                    .orElseThrow(TaskTokenCurrentAuthorization::invalidToken);
            if (owner.type() != PrincipalType.USER
                    || !owner.canAct()
                    || !owner.scope().organizationId().equals(
                            scope.workItemScope().organizationId())
                    || !ownerMembership.canParticipate()
                    || !ownerMembership.userPrincipalId().equals(ownerId)
                    || !ownerMembership.scope().organizationId().equals(
                            scope.workItemScope().organizationId())
                    || !ownerMembership.scope().teamId().equals(
                            scope.workItemScope().teamId())) {
                throw invalidToken();
            }
            return;
        }
        throw invalidToken();
    }

    private static DomainValidationException invalidToken() {
        return new DomainValidationException("taskToken", "is invalid or no longer authorized");
    }
}
