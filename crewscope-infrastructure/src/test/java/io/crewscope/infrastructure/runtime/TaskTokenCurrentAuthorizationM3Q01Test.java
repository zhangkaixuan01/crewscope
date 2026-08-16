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
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskTokenGrantScope;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Immediate responsibility, member and Agent-owner revocation tests for M3-Q01. */
class TaskTokenCurrentAuthorizationM3Q01Test {

    private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final PrincipalRepository principals = mock(PrincipalRepository.class);
    private final ResponsibilityAssignmentRepository assignments =
            mock(ResponsibilityAssignmentRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final TaskCredentialGrant grant = mock(TaskCredentialGrant.class);
    private TaskTokenRuntimeFixture fixture;
    private TaskTokenCurrentAuthorization authorization;

    @BeforeEach
    void setUp() {
        fixture = new TaskTokenRuntimeFixture();
        TaskTokenGrantScope scope = new TaskTokenGrantScope(
                fixture.workScope, fixture.taskId, fixture.executionId, 1,
                fixture.leaseId, fixture.environment, fixture.runtimeId, fixture.workerId,
                fixture.claimTokenHash, fixture.fencingToken, fixture.executionPrincipal,
                fixture.policyId, fixture.policyHash, fixture.overlayReference,
                java.util.Set.of("repository.read"), java.util.Set.of());
        when(grant.scope()).thenReturn(scope);
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
                .thenReturn(Optional.of(fixture.ownerMembership));
        authorization = new TaskTokenCurrentAuthorization(
                executions, tasks, principals, assignments, members);
    }

    @Test
    void acceptsOnlyTheCurrentExecutorAssignment() {
        assertDoesNotThrow(() -> authorization.requireCurrent(grant));

        when(fixture.assignment.isActive()).thenReturn(false);

        assertThrows(RuntimeException.class, () -> authorization.requireCurrent(grant));
    }

    @Test
    void invalidatesAUserExecutorImmediatelyAfterMembershipRevocation() {
        TeamMemberId memberId = TeamMemberId.generate();
        Principal user = principal(PrincipalType.USER, Optional.empty());
        TeamMember member = member(user.id(), true);
        currentAssignment(PrincipalType.USER, Optional.of(memberId));
        when(principals.findById(fixture.organizationId, fixture.executor.id()))
                .thenReturn(Optional.of(user));
        when(members.findById(fixture.organizationId, memberId))
                .thenReturn(Optional.of(member));
        assertDoesNotThrow(() -> authorization.requireCurrent(grant));

        when(member.canParticipate()).thenReturn(false);

        assertThrows(RuntimeException.class, () -> authorization.requireCurrent(grant));
    }

    @Test
    void invalidatesAnAgentWhenItsOwnerOrMembershipIsNoLongerAuthorized() {
        PrincipalId ownerId = PrincipalId.generate();
        Principal agent = principal(PrincipalType.TEAM_AGENT, Optional.of(ownerId));
        Principal owner = mock(Principal.class);
        when(owner.type()).thenReturn(PrincipalType.USER);
        when(owner.canAct()).thenReturn(true);
        when(owner.scope()).thenReturn(PrincipalScope.organization(fixture.organizationId));
        TeamMember membership = member(ownerId, true);
        currentAssignment(PrincipalType.TEAM_AGENT, Optional.empty());
        when(principals.findById(fixture.organizationId, fixture.executor.id()))
                .thenReturn(Optional.of(agent));
        when(principals.findById(fixture.organizationId, ownerId))
                .thenReturn(Optional.of(owner));
        when(members.findByTeamAndUserPrincipalId(
                        fixture.organizationId, fixture.workScope.teamId(), ownerId))
                .thenReturn(Optional.of(membership));
        assertDoesNotThrow(() -> authorization.requireCurrent(grant));

        when(owner.canAct()).thenReturn(false);
        assertThrows(RuntimeException.class, () -> authorization.requireCurrent(grant));

        when(owner.canAct()).thenReturn(true);
        when(owner.type()).thenReturn(PrincipalType.TEAM_AGENT);
        assertThrows(RuntimeException.class, () -> authorization.requireCurrent(grant));

        when(owner.type()).thenReturn(PrincipalType.USER);
        when(owner.scope()).thenReturn(PrincipalScope.organization(
                io.crewscope.domain.shared.id.OrganizationId.generate()));
        assertThrows(RuntimeException.class, () -> authorization.requireCurrent(grant));

        when(owner.scope()).thenReturn(PrincipalScope.organization(fixture.organizationId));
        when(membership.canParticipate()).thenReturn(false);

        assertThrows(RuntimeException.class, () -> authorization.requireCurrent(grant));
    }

    @Test
    void rejectsAnUnsupportedExecutorPrincipalTypeEvenForCorruptedPersistedFacts() {
        Principal service = principal(PrincipalType.SERVICE, Optional.empty());
        currentAssignment(PrincipalType.SERVICE, Optional.empty());
        when(principals.findById(fixture.organizationId, fixture.executor.id()))
                .thenReturn(Optional.of(service));

        assertThrows(RuntimeException.class, () -> authorization.requireCurrent(grant));
    }

    private Principal principal(PrincipalType type, Optional<PrincipalId> ownerId) {
        Principal principal = mock(Principal.class);
        when(principal.id()).thenReturn(fixture.executor.id());
        when(principal.type()).thenReturn(type);
        when(principal.ownerPrincipalId()).thenReturn(ownerId);
        when(principal.canAct()).thenReturn(true);
        when(principal.scope()).thenReturn(type.isAgent()
                ? PrincipalScope.team(fixture.organizationId, fixture.workScope.teamId())
                : PrincipalScope.organization(fixture.organizationId));
        return principal;
    }

    private TeamMember member(PrincipalId principalId, boolean active) {
        TeamMember member = mock(TeamMember.class);
        when(member.userPrincipalId()).thenReturn(principalId);
        when(member.canParticipate()).thenReturn(active);
        when(member.scope()).thenReturn(new io.crewscope.domain.team.TeamScope(
                fixture.organizationId, fixture.workScope.teamId()));
        return member;
    }

    private ResponsibilityAssignment currentAssignment(
            PrincipalType type, Optional<TeamMemberId> memberId) {
        ResponsibilityAssignment assignment = fixture.assignment;
        when(assignment.actorType()).thenReturn(type);
        when(assignment.actorMemberId()).thenReturn(memberId);
        return assignment;
    }
}
