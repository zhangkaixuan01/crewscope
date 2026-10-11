package io.crewscope.domain.workgraph;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemLabel;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal org/team/two-WorkProject fixture for WorkGraph domain tests. */
final class WorkGraphDomainFixture {

    static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-10-11T08:00:00Z");

    final OrganizationId organizationId;
    final Principal owner;
    final TeamInitialization team;
    final WorkProject projectA;
    final WorkProject projectB;

    private final AtomicInteger keySequence = new AtomicInteger();

    private WorkGraphDomainFixture(
            OrganizationId organizationId,
            Principal owner,
            TeamInitialization team,
            WorkProject projectA,
            WorkProject projectB) {
        this.organizationId = organizationId;
        this.owner = owner;
        this.team = team;
        this.projectA = projectA;
        this.projectB = projectB;
    }

    static WorkGraphDomainFixture create() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal owner = activeUser(organizationId, "Owner");
        TeamInitialization team = TeamInitialization.create(owner, "Platform", CREATED_AT);
        return new WorkGraphDomainFixture(
                organizationId,
                owner,
                team,
                project(team, owner, "CRW"),
                project(team, owner, "WGS"));
    }

    WorkItem itemIn(WorkProject project) {
        WorkProjectKey projectKey = project.key();
        return WorkItem.createNative(
                WorkItemId.generate(),
                project,
                new WorkItemKey(projectKey.value() + "-" + keySequence.incrementAndGet()),
                WorkItemType.TASK,
                "Task " + keySequence.get(),
                Optional.empty(),
                WorkItemPriority.MEDIUM,
                java.util.Set.of(new WorkItemLabel("Backend")),
                Optional.empty(),
                owner,
                CREATED_AT);
    }

    PrincipalId actorId() {
        return owner.id();
    }

    private static WorkProject project(
            TeamInitialization team, Principal owner, String key) {
        return WorkProject.create(
                WorkProjectId.generate(),
                new WorkProjectKey(key),
                key + " Project",
                team.team(),
                team.defaultWorkspace(),
                owner,
                CREATED_AT);
    }

    private static Principal activeUser(OrganizationId organizationId, String displayName) {
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
