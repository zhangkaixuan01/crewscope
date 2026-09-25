package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskTokenGrantScope;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M9b-A07: the executing member's authorization dimension pinned at issuance is compared against
 * the live member fact at every side-effect boundary, while legacy scopes without the dimension
 * keep reading history (ADR-038 §2).
 */
class TaskTokenMemberDimensionM9bA07Test {

    private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final PrincipalRepository principals = mock(PrincipalRepository.class);
    private final ResponsibilityAssignmentRepository assignments =
            mock(ResponsibilityAssignmentRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final TaskCredentialGrant grant = mock(TaskCredentialGrant.class);
    private final TeamMemberId pinnedMemberId = TeamMemberId.generate();
    private final long pinnedAuthorizationVersion = 3L;
    private TaskTokenRuntimeFixture fixture;
    private TaskTokenCurrentAuthorization authorization;
    private TeamMember membership;

    @BeforeEach
    void setUp() {
        fixture = new TaskTokenRuntimeFixture();
        // The fixture's executor is an Agent whose owning user is fixture.owner; the resolved
        // member behind the execution principal is therefore the owner's membership.
        membership = mock(TeamMember.class);
        when(membership.id()).thenReturn(pinnedMemberId);
        when(membership.authorizationVersion()).thenReturn(pinnedAuthorizationVersion);
        when(membership.userPrincipalId()).thenReturn(fixture.owner.id());
        when(membership.canParticipate()).thenReturn(true);
        when(membership.scope()).thenReturn(
                new io.crewscope.domain.team.TeamScope(
                        fixture.organizationId, fixture.workScope.teamId()));
        TaskTokenGrantScope initial = scope(
                fixture.executor.id(), Optional.of(pinnedMemberId),
                Optional.of(pinnedAuthorizationVersion));
        when(grant.scope()).thenReturn(initial);
        when(executions.findById(fixture.organizationId, fixture.executionId))
                .thenReturn(Optional.of(fixture.execution));
        when(tasks.findById(fixture.organizationId, fixture.taskId))
                .thenReturn(Optional.of(fixture.task));
        when(assignments.findById(
                        fixture.organizationId, fixture.executionPrincipal.assignmentId()))
                .thenReturn(Optional.of(fixture.assignment));
        when(principals.findById(fixture.organizationId, fixture.executor.id()))
                .thenReturn(Optional.of(fixture.executor));
        when(principals.findById(fixture.organizationId, fixture.owner.id()))
                .thenReturn(Optional.of(fixture.owner));
        when(members.findByTeamAndUserPrincipalId(
                        fixture.organizationId, fixture.workScope.teamId(), fixture.owner.id()))
                .thenReturn(Optional.of(membership));
        authorization = new TaskTokenCurrentAuthorization(
                executions, tasks, principals, assignments, members);
    }

    @Test
    void acceptsWhenThePinnedDimensionStillMatchesTheAgentOwnersMembership() {
        assertDoesNotThrow(() -> authorization.requireCurrent(grant));
        assertDoesNotThrow(() -> authorization.currentExecutionMember(
                fixture.workScope, fixture.executor.id()));
    }

    @Test
    void legacyScopeWithoutTheDimensionKeepsReading() {
        TaskTokenGrantScope legacy = scope(fixture.executor.id(), Optional.empty(), Optional.empty());
        when(grant.scope()).thenReturn(legacy);

        assertDoesNotThrow(() -> authorization.requireCurrent(grant));
    }

    @Test
    void rejectsWhenTheLiveMemberFactIsADifferentMember() {
        when(membership.id()).thenReturn(TeamMemberId.generate());

        assertThrows(RuntimeException.class, () -> authorization.requireCurrent(grant));
    }

    @Test
    void rejectsWhenTheAuthorizationVersionAdvancedAfterARoleChange() {
        when(membership.authorizationVersion()).thenReturn(pinnedAuthorizationVersion + 1);

        assertThrows(RuntimeException.class, () -> authorization.requireCurrent(grant));
    }

    @Test
    void rejectsWhenMembershipParticipationIsRevokedEvenWithAMatchingDimension() {
        when(membership.canParticipate()).thenReturn(false);

        assertThrows(RuntimeException.class, () -> authorization.requireCurrent(grant));
    }

    @Test
    void userExecutorDimensionComparesAgainstTheExecutorsOwnMembership() {
        PrincipalId userId = PrincipalId.generate();
        io.crewscope.domain.identity.Principal user =
                mock(io.crewscope.domain.identity.Principal.class);
        when(user.id()).thenReturn(userId);
        when(user.type()).thenReturn(PrincipalType.USER);
        when(user.canAct()).thenReturn(true);
        when(user.scope()).thenReturn(io.crewscope.domain.identity.PrincipalScope.organization(
                fixture.organizationId));
        ResponsibilityAssignment userAssignment = fixture.assignment;
        when(userAssignment.actorType()).thenReturn(PrincipalType.USER);
        when(userAssignment.actorPrincipalId()).thenReturn(userId);
        TeamMemberId userMemberId = TeamMemberId.generate();
        when(userAssignment.actorMemberId()).thenReturn(Optional.of(userMemberId));
        when(principals.findById(fixture.organizationId, userId)).thenReturn(Optional.of(user));
        TeamMember userMembership = mock(TeamMember.class);
        when(userMembership.id()).thenReturn(userMemberId);
        when(userMembership.authorizationVersion()).thenReturn(7L);
        when(userMembership.userPrincipalId()).thenReturn(userId);
        when(userMembership.canParticipate()).thenReturn(true);
        when(userMembership.scope()).thenReturn(
                new io.crewscope.domain.team.TeamScope(
                        fixture.organizationId, fixture.workScope.teamId()));
        when(members.findByTeamAndUserPrincipalId(
                        fixture.organizationId, fixture.workScope.teamId(), userId))
                .thenReturn(Optional.of(userMembership));
        TaskTokenGrantScope userGrant = scope(
                userId, Optional.of(userMemberId), Optional.of(7L));
        when(grant.scope()).thenReturn(userGrant);

        assertDoesNotThrow(() -> authorization.requireCurrent(grant));

        when(userMembership.authorizationVersion()).thenReturn(8L);
        assertThrows(RuntimeException.class, () -> authorization.requireCurrent(grant));
    }

    private TaskTokenGrantScope scope(
            PrincipalId executorId,
            Optional<TeamMemberId> memberId,
            Optional<Long> authorizationVersion) {
        io.crewscope.domain.task.ExecutionPrincipalSnapshot principal =
                new io.crewscope.domain.task.ExecutionPrincipalSnapshot(
                        executorId,
                        fixture.executionPrincipal.assignmentId(),
                        fixture.executionPrincipal.assignmentVersion(),
                        fixture.executionPrincipal.responsibilitySnapshotHash());
        io.crewscope.domain.task.TaskExecutionPlanningContext planning =
                new io.crewscope.domain.task.TaskExecutionPlanningContext(
                        principal, fixture.policyId, fixture.policyHash,
                        fixture.overlayReference, Optional.empty(), Optional.empty());
        when(fixture.execution.planningContext()).thenReturn(Optional.of(planning));
        return new TaskTokenGrantScope(
                fixture.workScope, fixture.taskId, fixture.executionId, 1,
                fixture.leaseId, fixture.environment, fixture.runtimeId, fixture.workerId,
                fixture.claimTokenHash, fixture.fencingToken, principal,
                fixture.policyId, fixture.policyHash, fixture.overlayReference,
                Set.of("repository.read"), Set.of(), memberId, authorizationVersion);
    }
}
