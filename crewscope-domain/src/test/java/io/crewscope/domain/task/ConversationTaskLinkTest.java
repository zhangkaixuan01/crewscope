package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationTaskLinkTest {

    @Test
    void linksConversationAndTaskWithStablePairIdentity() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Task task = Task.create(
                TaskId.generate(),
                fixture.workItem,
                TaskSource.fromMessage(
                        fixture.workItem, fixture.conversation, fixture.message),
                fixture.snapshot(),
                fixture.owner,
                TaskDomainFixture.CREATED_AT);

        ConversationTaskLink first = ConversationTaskLink.link(
                fixture.conversation,
                task,
                ConversationTaskLinkOrigin.SOURCE,
                fixture.owner,
                TaskDomainFixture.CREATED_AT);
        ConversationTaskLink retry = ConversationTaskLink.link(
                fixture.conversation,
                task,
                ConversationTaskLinkOrigin.SOURCE,
                fixture.owner,
                TaskDomainFixture.CREATED_AT);

        assertEquals(first.id(), retry.id());
        assertEquals(
                ConversationTaskLinkId.forPair(fixture.conversation.id(), task.id()),
                first.id());
        assertEquals(fixture.workItem.id(), first.workItemId());
        assertEquals(fixture.scope.projectId(), first.workProjectId());
        assertEquals(task.id(), first.taskId());
    }

    @Test
    void allowsAdditionalConversationLinkWithoutChangingTaskSource() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Task task = fixture.task();

        ConversationTaskLink link = ConversationTaskLink.link(
                fixture.conversation,
                task,
                ConversationTaskLinkOrigin.MANUAL,
                fixture.owner,
                TaskDomainFixture.CREATED_AT);

        assertEquals(TaskSourceType.WORK_ITEM, task.source().type());
        assertEquals(ConversationTaskLinkOrigin.MANUAL, link.origin());
    }

    @Test
    void rejectsCrossWorkspaceTaskAndFalseSourceLink() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        WorkItemScope outsideScope = new WorkItemScope(
                fixture.scope.organizationId(),
                fixture.scope.teamId(),
                WorkspaceId.generate(),
                fixture.scope.projectId());
        Conversation outside = TaskDomainFixture.conversation(
                outsideScope, fixture.owner, fixture.executor);

        DomainValidationException outsideFailure = assertThrows(
                DomainValidationException.class,
                () -> ConversationTaskLink.link(
                        outside,
                        fixture.task(),
                        ConversationTaskLinkOrigin.MANUAL,
                        fixture.owner,
                        TaskDomainFixture.CREATED_AT));
        DomainValidationException sourceFailure = assertThrows(
                DomainValidationException.class,
                () -> ConversationTaskLink.link(
                        fixture.conversation,
                        fixture.task(),
                        ConversationTaskLinkOrigin.SOURCE,
                        fixture.owner,
                        TaskDomainFixture.CREATED_AT));

        assertEquals("conversationTaskLink.taskId", outsideFailure.error().details().get("field"));
        assertEquals("conversationTaskLink.origin", sourceFailure.error().details().get("field"));
    }

    @Test
    void rejectsReconstitutionWithUnstableIdentity() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Task task = fixture.task();
        ConversationTaskLink link = ConversationTaskLink.link(
                fixture.conversation,
                task,
                ConversationTaskLinkOrigin.MANUAL,
                fixture.owner,
                TaskDomainFixture.CREATED_AT);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> ConversationTaskLink.reconstitute(
                        ConversationTaskLinkId.forPair(
                                fixture.conversation.id(), TaskId.generate()),
                        link.scope(),
                        link.conversationId(),
                        link.workProjectId(),
                        link.workItemId(),
                        link.taskId(),
                        link.origin(),
                        link.createdByPrincipalId(),
                        link.audit()));

        assertEquals("conversationTaskLink.id", failure.error().details().get("field"));
    }
}
