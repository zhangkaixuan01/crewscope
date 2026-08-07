package io.crewscope.domain.workitem;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.Optional;

final class WorkItemDomainFixture {

    static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-07T22:00:00Z");

    final OrganizationId organizationId;
    final Principal owner;
    final TeamInitialization team;
    final WorkProject project;

    private WorkItemDomainFixture(
            OrganizationId organizationId,
            Principal owner,
            TeamInitialization team,
            WorkProject project) {
        this.organizationId = organizationId;
        this.owner = owner;
        this.team = team;
        this.project = project;
    }

    static WorkItemDomainFixture create() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal owner = activeUser(organizationId, "Owner");
        TeamInitialization team = TeamInitialization.create(owner, "Platform", CREATED_AT);
        WorkProject project = WorkProject.create(
                WorkProjectId.generate(),
                new WorkProjectKey("CRW"),
                "CrewScope",
                team.team(),
                team.defaultWorkspace(),
                owner,
                CREATED_AT);
        return new WorkItemDomainFixture(organizationId, owner, team, project);
    }

    WorkItem nativeWorkItem() {
        return WorkItem.createNative(
                WorkItemId.generate(),
                project,
                new WorkItemKey("CRW-1"),
                WorkItemType.TASK,
                "Implement WorkItem",
                Optional.of("Markdown description"),
                WorkItemPriority.HIGH,
                java.util.Set.of(new WorkItemLabel("Backend")),
                Optional.of(UtcTimestamp.parse("2026-08-10T12:00:00Z")),
                owner,
                CREATED_AT);
    }

    static Principal activeUser(OrganizationId organizationId, String displayName) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                displayName,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
    }
}
