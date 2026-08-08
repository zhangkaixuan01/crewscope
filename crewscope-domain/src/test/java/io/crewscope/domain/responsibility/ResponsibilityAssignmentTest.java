package io.crewscope.domain.responsibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResponsibilityAssignmentTest {

    private static final UtcTimestamp ASSIGNED_AT =
            UtcTimestamp.parse("2026-08-07T23:00:00Z");
    private static final UtcTimestamp RELEASED_AT =
            UtcTimestamp.parse("2026-08-07T23:30:00Z");

    @Test
    void createsAnImmediatelyActiveAndAuditedOwnerFact() {
        Fixture fixture = Fixture.create();

        ResponsibilityAssignment assignment = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                fixture.workItem,
                ResponsibilityRole.OWNER,
                fixture.owner,
                Optional.of(fixture.team.ownerMember()),
                fixture.owner,
                ASSIGNED_AT);

        assertEquals(fixture.workItem.scope(), assignment.scope());
        assertEquals(fixture.workItem.id(), assignment.workItemId());
        assertEquals(ResponsibilityRole.OWNER, assignment.role());
        assertEquals(PrincipalType.USER, assignment.actorType());
        assertEquals(fixture.team.ownerMember().id(), assignment.actorMemberId().orElseThrow());
        assertEquals(ResponsibilityAssignmentStatus.ACTIVE, assignment.status());
        assertEquals(ASSIGNED_AT, assignment.assignedAt());
        assertEquals(ASSIGNED_AT, assignment.acceptedAt());
        assertEquals(fixture.owner.id(), assignment.audit().createdBy().orElseThrow());
        assertEquals(0, assignment.version());
        assertTrue(assignment.isActive());
    }

    @Test
    void assignsTeamScopedAgentsOnlyToSupportedRoles() {
        Fixture fixture = Fixture.create();
        Principal personalAgent = fixture.agent(PrincipalType.PERSONAL_AGENT);
        Principal specialistAgent = fixture.agent(PrincipalType.SPECIALIST_AGENT);

        ResponsibilityAssignment executor = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                fixture.workItem,
                ResponsibilityRole.EXECUTOR,
                personalAgent,
                Optional.empty(),
                fixture.owner,
                ASSIGNED_AT);
        ResponsibilityAssignment reviewer = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                fixture.workItem,
                ResponsibilityRole.REVIEWER,
                specialistAgent,
                Optional.empty(),
                fixture.owner,
                ASSIGNED_AT);

        assertEquals(PrincipalType.PERSONAL_AGENT, executor.actorType());
        assertTrue(executor.actorMemberId().isEmpty());
        assertEquals(PrincipalType.SPECIALIST_AGENT, reviewer.actorType());
        assertThrows(
                DomainValidationException.class,
                () -> ResponsibilityAssignment.assign(
                        ResponsibilityAssignmentId.generate(),
                        fixture.workItem,
                        ResponsibilityRole.REVIEWER,
                        personalAgent,
                        Optional.empty(),
                        fixture.owner,
                        ASSIGNED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> ResponsibilityAssignment.assign(
                        ResponsibilityAssignmentId.generate(),
                        fixture.workItem,
                        ResponsibilityRole.OWNER,
                        specialistAgent,
                        Optional.empty(),
                        fixture.owner,
                        ASSIGNED_AT));
    }

    @Test
    void releasesAnAssignmentPermanentlyAndAdvancesAuditAndVersion() {
        Fixture fixture = Fixture.create();
        ResponsibilityAssignment assignment = fixture.ownerAssignment();

        ResponsibilityAssignment released = assignment.release(fixture.owner, RELEASED_AT);

        assertEquals(ResponsibilityAssignmentStatus.RELEASED, released.status());
        assertFalse(released.isActive());
        assertEquals(fixture.owner.id(), released.releasedByPrincipalId().orElseThrow());
        assertEquals(RELEASED_AT, released.releasedAt().orElseThrow());
        assertEquals(1, released.version());
        assertEquals(RELEASED_AT, released.audit().updatedAt());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> released.release(fixture.owner, RELEASED_AT));
    }

    @Test
    void rejectsReleaseBeforeAcceptance() {
        Fixture fixture = Fixture.create();

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> fixture.ownerAssignment().release(
                        fixture.owner, UtcTimestamp.parse("2026-08-07T22:59:59Z")));

        assertEquals(
                "responsibilityAssignment.releasedAt",
                failure.error().details().get("field"));
    }

    @Test
    void rejectsArchivedWorkItems() {
        Fixture fixture = Fixture.create();
        WorkItem archived = archive(fixture.workItem, fixture.owner);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> ResponsibilityAssignment.assign(
                        ResponsibilityAssignmentId.generate(),
                        archived,
                        ResponsibilityRole.OWNER,
                        fixture.owner,
                        Optional.of(fixture.team.ownerMember()),
                        fixture.owner,
                        ASSIGNED_AT));

        assertEquals(
                "responsibilityAssignment.workItemId",
                failure.error().details().get("field"));
    }

    @Test
    void rejectsInactiveOrCrossScopePrincipals() {
        Fixture fixture = Fixture.create();
        Principal suspended = fixture.owner.transitionTo(PrincipalStatus.SUSPENDED, ASSIGNED_AT);
        Principal crossOrganization = Fixture.activeUser(OrganizationId.generate(), "Other");

        assertThrows(
                DomainValidationException.class,
                () -> ResponsibilityAssignment.assign(
                        ResponsibilityAssignmentId.generate(),
                        fixture.workItem,
                        ResponsibilityRole.OWNER,
                        suspended,
                        Optional.of(fixture.team.ownerMember()),
                        fixture.owner,
                        ASSIGNED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> ResponsibilityAssignment.assign(
                        ResponsibilityAssignmentId.generate(),
                        fixture.workItem,
                        ResponsibilityRole.EXECUTOR,
                        crossOrganization,
                        Optional.of(fixture.team.ownerMember()),
                        fixture.owner,
                        ASSIGNED_AT));
    }

    @Test
    void requiresTheUsersActiveMembershipInTheWorkItemTeam() {
        Fixture fixture = Fixture.create();
        Principal memberUser = Fixture.activeUser(fixture.organizationId, "Member");
        TeamMember member = TeamMember.join(
                TeamMemberId.generate(),
                new TeamScope(fixture.organizationId, fixture.team.team().id()),
                memberUser,
                TeamJoinMethod.OIDC,
                ASSIGNED_AT);
        TeamMember suspended = member.suspend(ASSIGNED_AT);

        assertThrows(
                DomainValidationException.class,
                () -> ResponsibilityAssignment.assign(
                        ResponsibilityAssignmentId.generate(),
                        fixture.workItem,
                        ResponsibilityRole.EXECUTOR,
                        memberUser,
                        Optional.empty(),
                        fixture.owner,
                        ASSIGNED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> ResponsibilityAssignment.assign(
                        ResponsibilityAssignmentId.generate(),
                        fixture.workItem,
                        ResponsibilityRole.EXECUTOR,
                        memberUser,
                        Optional.of(suspended),
                        fixture.owner,
                        ASSIGNED_AT));
    }

    @Test
    void rejectsAnOrganizationScopedAgentWithoutTeamAffinity() {
        Fixture fixture = Fixture.create();
        Principal agent = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(fixture.organizationId),
                PrincipalType.TEAM_AGENT,
                Optional.of(fixture.owner.id()),
                "Unscoped Agent",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                ASSIGNED_AT);

        assertThrows(
                DomainValidationException.class,
                () -> ResponsibilityAssignment.assign(
                        ResponsibilityAssignmentId.generate(),
                        fixture.workItem,
                        ResponsibilityRole.EXECUTOR,
                        agent,
                        Optional.empty(),
                        fixture.owner,
                        ASSIGNED_AT));
    }

    private static WorkItem archive(WorkItem workItem, Principal actor) {
        return workItem.transitionTo(WorkItemStatus.READY, actor, ASSIGNED_AT)
                .transitionTo(WorkItemStatus.IN_PROGRESS, actor, ASSIGNED_AT)
                .transitionTo(WorkItemStatus.IN_REVIEW, actor, ASSIGNED_AT)
                .transitionTo(WorkItemStatus.DONE, actor, ASSIGNED_AT)
                .transitionTo(WorkItemStatus.ARCHIVED, actor, ASSIGNED_AT);
    }

    private static final class Fixture {

        private final OrganizationId organizationId;
        private final Principal owner;
        private final TeamInitialization team;
        private final WorkItem workItem;

        private Fixture(
                OrganizationId organizationId,
                Principal owner,
                TeamInitialization team,
                WorkItem workItem) {
            this.organizationId = organizationId;
            this.owner = owner;
            this.team = team;
            this.workItem = workItem;
        }

        private static Fixture create() {
            OrganizationId organizationId = OrganizationId.generate();
            Principal owner = activeUser(organizationId, "Owner");
            TeamInitialization team = TeamInitialization.create(owner, "Platform", ASSIGNED_AT);
            WorkItem workItem = WorkItem.create(
                    WorkItemId.generate(),
                    new io.crewscope.domain.workitem.WorkItemScope(
                            organizationId,
                            team.team().id(),
                            team.defaultWorkspace().id(),
                            io.crewscope.domain.workitem.WorkProjectId.generate()),
                    new WorkItemKey("CRW-1"),
                    "Responsibility baseline",
                    owner.id(),
                    ASSIGNED_AT);
            return new Fixture(organizationId, owner, team, workItem);
        }

        private ResponsibilityAssignment ownerAssignment() {
            return ResponsibilityAssignment.assign(
                    ResponsibilityAssignmentId.generate(),
                    workItem,
                    ResponsibilityRole.OWNER,
                    owner,
                    Optional.of(team.ownerMember()),
                    owner,
                    ASSIGNED_AT);
        }

        private Principal agent(PrincipalType type) {
            return Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.team(organizationId, team.team().id()),
                    type,
                    Optional.of(owner.id()),
                    type.name(),
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    ASSIGNED_AT);
        }

        private static Principal activeUser(OrganizationId organizationId, String name) {
            return Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    name,
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    ASSIGNED_AT);
        }
    }
}
