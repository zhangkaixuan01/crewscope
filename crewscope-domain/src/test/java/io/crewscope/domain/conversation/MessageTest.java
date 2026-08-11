package io.crewscope.domain.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MessageTest {

    @Test
    void appendsNormalizedUserMessageFromOwnerParticipant() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();

        Message message = fixture.conversation()
                .appendMessage(
                        MessageId.generate(),
                        fixture.initialization.ownerParticipant(),
                        fixture.owner,
                        new MessageContent("  **Build** the conversation flow.  "),
                        ConversationDomainFixture.CREATED_AT)
                .message();

        assertEquals(MessageType.USER_MESSAGE, message.type());
        assertEquals("**Build** the conversation flow.", message.content().markdown());
        assertEquals(
                fixture.initialization.ownerParticipant().id(),
                message.participantId().orElseThrow());
        assertEquals(fixture.owner.id(), message.authorPrincipalId().orElseThrow());
        assertEquals(fixture.owner.id(), message.audit().createdBy().orElseThrow());
        assertEquals(MessageSequence.first(), message.sequence());
    }

    @Test
    void appendsAgentMessageFromPersonalAgentParticipant() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Principal agent = fixture.team.ownerPersonalAgent().agentPrincipal();

        Message message = fixture.conversation()
                .appendMessage(
                        MessageId.generate(),
                        fixture.initialization.agentParticipant(),
                        agent,
                        new MessageContent("I will clarify the target."),
                        ConversationDomainFixture.CREATED_AT)
                .message();

        assertEquals(MessageType.AGENT_MESSAGE, message.type());
        assertEquals(agent.id(), message.authorPrincipalId().orElseThrow());
    }

    @Test
    void appendsSystemNoticeWithoutForgingAnAuthor() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();

        Message message = fixture.conversation()
                .appendSystemNotice(
                        MessageId.generate(),
                        fixture.owner,
                        new MessageContent("Conversation created."),
                        ConversationDomainFixture.CREATED_AT)
                .message();

        assertEquals(MessageType.SYSTEM_NOTICE, message.type());
        assertTrue(message.participantId().isEmpty());
        assertTrue(message.authorPrincipalId().isEmpty());
        assertEquals(fixture.owner.id(), message.audit().createdBy().orElseThrow());
    }

    @Test
    void rejectsAuthorThatDoesNotMatchParticipant() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Principal anotherUser = ConversationDomainFixture.activeUser(
                fixture.organizationId, "Another user");

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> fixture.conversation().appendMessage(
                        MessageId.generate(),
                        fixture.initialization.ownerParticipant(),
                        anotherUser,
                        new MessageContent("Forged"),
                        ConversationDomainFixture.CREATED_AT));

        assertEquals("message.authorPrincipalId", failure.error().details().get("field"));
    }

    @Test
    void rejectsParticipantFromAnotherConversationAndInactiveAuthor() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        PersonalConversationInitialization another = PersonalConversationInitialization.start(
                ConversationId.generate(),
                fixture.team.defaultWorkspace(),
                fixture.team.ownerMember(),
                fixture.owner,
                fixture.team.ownerPersonalAgent(),
                "Another conversation",
                ConversationVisibility.PRIVATE,
                ConversationDomainFixture.CREATED_AT);
        Principal suspendedOwner = fixture.owner.transitionTo(
                io.crewscope.domain.identity.PrincipalStatus.SUSPENDED,
                ConversationDomainFixture.LATER);

        assertThrows(
                DomainValidationException.class,
                () -> fixture.conversation().appendMessage(
                        MessageId.generate(),
                        another.ownerParticipant(),
                        fixture.owner,
                        new MessageContent("Wrong conversation"),
                        ConversationDomainFixture.CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> fixture.conversation().appendMessage(
                        MessageId.generate(),
                        fixture.initialization.ownerParticipant(),
                        suspendedOwner,
                        new MessageContent("Inactive"),
                        ConversationDomainFixture.LATER));
    }

    @Test
    void rejectsBlankOversizedAndInvalidPersistedAuthorShape() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();

        assertThrows(DomainValidationException.class, () -> new MessageContent(" "));
        assertThrows(
                DomainValidationException.class,
                () -> new MessageContent("x".repeat(MessageContent.MAX_LENGTH + 1)));
        assertThrows(
                DomainValidationException.class,
                () -> Message.reconstitute(
                        MessageId.generate(),
                        fixture.conversation().scope(),
                        fixture.conversation().id(),
                        MessageSequence.first(),
                        MessageType.USER_MESSAGE,
                        Optional.empty(),
                        Optional.empty(),
                        new MessageContent("Invalid"),
                        AuditMetadata.createdBy(
                                fixture.owner.id(), ConversationDomainFixture.CREATED_AT)));
    }

    @Test
    void rejectsUnsafeControlsButKeepsMarkdownAndMultilineText() {
        MessageContent content = new MessageContent("## Plan\n\n- `safe`\titem");

        assertEquals("## Plan\n\n- `safe`\titem", content.markdown());
        assertThrows(DomainValidationException.class, () -> new MessageContent("hidden\u0000text"));
        assertThrows(DomainValidationException.class, () -> new MessageContent("hidden\u061Ctext"));
        assertThrows(DomainValidationException.class, () -> new MessageContent("hidden\u200Ftext"));
        assertThrows(DomainValidationException.class, () -> new MessageContent("safe\u202Etxt"));
        assertThrows(DomainValidationException.class, () -> new MessageContent("broken\uD800"));
    }

    @Test
    void rejectsAuthoredMessageWhoseAuditActorWasChanged() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> Message.reconstitute(
                        MessageId.generate(),
                        fixture.conversation().scope(),
                        fixture.conversation().id(),
                        MessageSequence.first(),
                        MessageType.USER_MESSAGE,
                        Optional.of(fixture.initialization.ownerParticipant().id()),
                        Optional.of(fixture.owner.id()),
                        new MessageContent("Persisted"),
                        AuditMetadata.createdBy(
                                io.crewscope.domain.shared.id.PrincipalId.generate(),
                                UtcTimestamp.parse("2026-08-08T12:00:00Z"))));

        assertEquals("message.audit.createdBy", failure.error().details().get("field"));
    }
}
