package io.crewscope.domain.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import org.junit.jupiter.api.Test;

class WorkProjectTest {

    @Test
    void createsActiveProjectInsideTheTeamWorkspace() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();

        assertEquals(WorkProjectStatus.ACTIVE, fixture.project.status());
        assertTrue(fixture.project.acceptsWork());
        assertEquals(fixture.team.team().id(), fixture.project.scope().teamId());
        assertEquals(fixture.team.defaultWorkspace().id(), fixture.project.scope().workspaceId());
        assertEquals(fixture.owner.id(), fixture.project.audit().createdBy().orElseThrow());
    }

    @Test
    void rejectsWorkspaceFromAnotherTeam() {
        WorkItemDomainFixture first = WorkItemDomainFixture.create();
        Principal secondOwner = WorkItemDomainFixture.activeUser(first.organizationId, "Second");
        var secondTeam = io.crewscope.domain.team.TeamInitialization.create(
                secondOwner, "Second", WorkItemDomainFixture.CREATED_AT);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> WorkProject.create(
                        WorkProjectId.generate(),
                        new WorkProjectKey("OPS"),
                        "Operations",
                        first.team.team(),
                        secondTeam.defaultWorkspace(),
                        first.owner,
                        WorkItemDomainFixture.CREATED_AT));

        assertEquals("workProject.workspaceId", failure.error().details().get("field"));
    }

    @Test
    void rejectsInactiveAndCrossOrganizationCreators() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        UtcTimestamp changedAt = UtcTimestamp.parse("2026-08-07T22:01:00Z");
        Principal disabled = fixture.owner.transitionTo(PrincipalStatus.DISABLED, changedAt);
        Principal outside = WorkItemDomainFixture.activeUser(
                io.crewscope.domain.shared.id.OrganizationId.generate(), "Outside");

        assertThrows(
                DomainValidationException.class,
                () -> WorkProject.create(
                        WorkProjectId.generate(),
                        new WorkProjectKey("OPS"),
                        "Operations",
                        fixture.team.team(),
                        fixture.team.defaultWorkspace(),
                        disabled,
                        changedAt));
        DomainValidationException outsideFailure = assertThrows(
                DomainValidationException.class,
                () -> WorkProject.create(
                        WorkProjectId.generate(),
                        new WorkProjectKey("OPS"),
                        "Operations",
                        fixture.team.team(),
                        fixture.team.defaultWorkspace(),
                        outside,
                        changedAt));

        assertEquals(
                "workProject.createdByPrincipalId",
                outsideFailure.error().details().get("field"));
    }

    @Test
    void renamesProjectAndAdvancesAuditVersion() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        UtcTimestamp changedAt = UtcTimestamp.parse("2026-08-07T22:01:00Z");

        WorkProject renamed = fixture.project.rename("  Delivery Platform  ", fixture.owner, changedAt);

        assertEquals("Delivery Platform", renamed.name());
        assertEquals(1, renamed.version());
        assertEquals(changedAt, renamed.audit().updatedAt());
    }

    @Test
    void archiveIsTerminalAndStopsNewWork() {
        WorkItemDomainFixture fixture = WorkItemDomainFixture.create();
        WorkProject archived = fixture.project.archive(
                fixture.owner, UtcTimestamp.parse("2026-08-07T22:01:00Z"));

        assertEquals(WorkProjectStatus.ARCHIVED, archived.status());
        assertFalse(archived.acceptsWork());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.rename(
                        "Closed",
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-07T22:02:00Z")));
    }
}
