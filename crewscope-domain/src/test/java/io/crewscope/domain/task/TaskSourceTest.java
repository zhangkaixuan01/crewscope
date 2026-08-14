package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskSourceTest {

    @Test
    void capturesOneWorkItemVersionWithoutConversationInput() {
        TaskDomainFixture fixture = new TaskDomainFixture();

        TaskSource source = TaskSource.fromWorkItem(fixture.workItem);

        assertEquals(TaskSourceType.WORK_ITEM, source.type());
        assertEquals(fixture.scope, source.scope());
        assertEquals(fixture.workItem.id(), source.workItemId());
        assertEquals(7, source.workItemVersion());
        assertTrue(source.conversationId().isEmpty());
        assertTrue(source.inputReference().isEmpty());
    }

    @Test
    void capturesCommittedMessageAndSequenceWithoutCopyingContent() {
        TaskDomainFixture fixture = new TaskDomainFixture();

        TaskSource source =
                TaskSource.fromMessage(fixture.workItem, fixture.conversation, fixture.message);

        assertEquals(TaskSourceType.CONVERSATION, source.type());
        assertEquals(Optional.of(fixture.conversation.id()), source.conversationId());
        TaskInputReference input = source.inputReference().orElseThrow();
        assertEquals(TaskInputReferenceType.MESSAGE, input.type());
        assertEquals(fixture.message.id().value(), input.referenceId());
        assertEquals(fixture.message.sequence().value(), input.referenceVersion());
    }

    @Test
    void rejectsConversationOutsideWorkItemWorkspace() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        WorkItemScope outsideScope = new WorkItemScope(
                fixture.scope.organizationId(),
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
        Conversation outside = TaskDomainFixture.conversation(
                outsideScope, fixture.owner, fixture.executor);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> TaskSource.fromMessage(
                        fixture.workItem,
                        outside,
                        TaskDomainFixture.message(outside, fixture.owner)));

        assertEquals("taskSource.conversationId", failure.error().details().get("field"));
    }

    @Test
    void rejectsInvalidSingleSourceShape() {
        TaskDomainFixture fixture = new TaskDomainFixture();

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> new TaskSource(
                        TaskSourceType.CONVERSATION,
                        fixture.scope,
                        fixture.workItem.id(),
                        fixture.workItem.version(),
                        Optional.empty(),
                        Optional.empty()));

        assertEquals("taskSource.conversationId", failure.error().details().get("field"));
    }
}
