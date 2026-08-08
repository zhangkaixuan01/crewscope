package io.crewscope.domain.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
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
import java.util.Set;

final class ConversationDomainFixture {

    static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-08T12:00:00Z");
    static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-08T12:01:00Z");

    final OrganizationId organizationId;
    final Principal owner;
    final TeamInitialization team;
    final PersonalConversationInitialization initialization;
    final WorkProject project;

    private ConversationDomainFixture(
            OrganizationId organizationId,
            Principal owner,
            TeamInitialization team,
            PersonalConversationInitialization initialization,
            WorkProject project) {
        this.organizationId = organizationId;
        this.owner = owner;
        this.team = team;
        this.initialization = initialization;
        this.project = project;
    }

    static ConversationDomainFixture create() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal owner = activeUser(organizationId, "Owner");
        TeamInitialization team = TeamInitialization.create(owner, "Platform", CREATED_AT);
        PersonalConversationInitialization initialization =
                PersonalConversationInitialization.start(
                        ConversationId.generate(),
                        team.defaultWorkspace(),
                        team.ownerMember(),
                        owner,
                        team.ownerPersonalAgent(),
                        "Plan the M2 milestone",
                        ConversationVisibility.PRIVATE,
                        CREATED_AT);
        WorkProject project = WorkProject.create(
                WorkProjectId.generate(),
                new WorkProjectKey("CRW"),
                "CrewScope",
                team.team(),
                team.defaultWorkspace(),
                owner,
                CREATED_AT);
        return new ConversationDomainFixture(
                organizationId, owner, team, initialization, project);
    }

    Conversation conversation() {
        return initialization.conversation();
    }

    WorkItem workItem() {
        return WorkItem.createNative(
                WorkItemId.generate(),
                project,
                new WorkItemKey("CRW-1"),
                WorkItemType.FEATURE,
                "Build conversations",
                Optional.of("Implement the M2 conversation domain"),
                WorkItemPriority.HIGH,
                Set.of(new WorkItemLabel("Conversation")),
                Optional.empty(),
                owner,
                CREATED_AT);
    }

    TeamMember activeMember(Principal user) {
        return team.team().joinMember(
                TeamMemberId.generate(), user, TeamJoinMethod.OIDC, CREATED_AT);
    }

    static Principal activeUser(OrganizationId organizationId, String name) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                name,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
    }
}
