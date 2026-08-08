package io.crewscope.domain.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConversationWorkItemLinkTest {

    @Test
    void linksConversationAndWorkItemWithStablePairIdentity() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        WorkItem workItem = fixture.workItem();

        ConversationWorkItemLink first = ConversationWorkItemLink.link(
                fixture.conversation(),
                workItem,
                ConversationWorkItemLinkOrigin.TASK_INTENT_CONFIRMATION,
                fixture.owner,
                ConversationDomainFixture.CREATED_AT);
        ConversationWorkItemLink retry = ConversationWorkItemLink.link(
                fixture.conversation(),
                workItem,
                ConversationWorkItemLinkOrigin.TASK_INTENT_CONFIRMATION,
                fixture.owner,
                ConversationDomainFixture.CREATED_AT);

        assertEquals(first.id(), retry.id());
        assertEquals(
                ConversationWorkItemLinkId.forPair(
                        fixture.conversation().id(), workItem.id()),
                first.id());
        assertEquals(fixture.project.id(), first.workProjectId());
        assertEquals(workItem.id(), first.workItemId());
        assertEquals(
                ConversationWorkItemLinkOrigin.TASK_INTENT_CONFIRMATION,
                first.origin());
        assertEquals(fixture.owner.id(), first.createdByPrincipalId());
    }

    @Test
    void rejectsWorkItemFromAnotherTeamWorkspace() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        TeamInitialization otherTeam = TeamInitialization.create(
                fixture.owner, "Other team", ConversationDomainFixture.CREATED_AT);
        WorkProject otherProject = WorkProject.create(
                WorkProjectId.generate(),
                new WorkProjectKey("OTH"),
                "Other project",
                otherTeam.team(),
                otherTeam.defaultWorkspace(),
                fixture.owner,
                ConversationDomainFixture.CREATED_AT);
        WorkItem otherWorkItem = WorkItem.createNative(
                WorkItemId.generate(),
                otherProject,
                new WorkItemKey("OTH-1"),
                WorkItemType.TASK,
                "Other work",
                Optional.empty(),
                WorkItemPriority.MEDIUM,
                Set.of(),
                Optional.empty(),
                fixture.owner,
                ConversationDomainFixture.CREATED_AT);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> ConversationWorkItemLink.link(
                        fixture.conversation(),
                        otherWorkItem,
                        ConversationWorkItemLinkOrigin.MANUAL,
                        fixture.owner,
                        ConversationDomainFixture.CREATED_AT));

        assertEquals(
                "conversationWorkItemLink.workItemId",
                failure.error().details().get("field"));
    }

    @Test
    void rejectsCreatorOutsideConversationScope() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Principal outsider = ConversationDomainFixture.activeUser(
                OrganizationId.generate(), "Outsider");

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> ConversationWorkItemLink.link(
                        fixture.conversation(),
                        fixture.workItem(),
                        ConversationWorkItemLinkOrigin.WORK_ITEM_DISCUSSION,
                        outsider,
                        ConversationDomainFixture.CREATED_AT));

        assertEquals(
                "conversationWorkItemLink.createdByPrincipalId",
                failure.error().details().get("field"));
    }

    @Test
    void rejectsPersistedLinkWithUnstableIdentity() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        WorkItem workItem = fixture.workItem();
        ConversationWorkItemLink link = ConversationWorkItemLink.link(
                fixture.conversation(),
                workItem,
                ConversationWorkItemLinkOrigin.MANUAL,
                fixture.owner,
                ConversationDomainFixture.CREATED_AT);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> ConversationWorkItemLink.reconstitute(
                        ConversationWorkItemLinkId.forPair(
                                ConversationId.generate(), workItem.id()),
                        link.scope(),
                        link.conversationId(),
                        link.workProjectId(),
                        link.workItemId(),
                        link.origin(),
                        link.createdByPrincipalId(),
                        link.audit()));

        assertEquals(
                "conversationWorkItemLink.id", failure.error().details().get("field"));
    }
}
