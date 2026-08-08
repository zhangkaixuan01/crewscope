package io.crewscope.domain.conversation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationVisibilityPolicyTest {

    private final ConversationVisibilityPolicy policy = new ConversationVisibilityPolicy();

    @Test
    void privateConversationRequiresExplicitParticipation() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Principal colleague = ConversationDomainFixture.activeUser(
                fixture.organizationId, "Colleague");
        TeamMember member = fixture.activeMember(colleague);

        ConversationAccessDecision owner = policy.forMember(
                fixture.conversation(),
                fixture.team.ownerMember(),
                fixture.owner,
                Optional.of(fixture.initialization.ownerParticipant()));
        ConversationAccessDecision nonParticipant = policy.forMember(
                fixture.conversation(), member, colleague, Optional.empty());

        assertTrue(owner.discoverable());
        assertTrue(owner.readable());
        assertTrue(owner.writable());
        assertFalse(nonParticipant.discoverable());
        assertFalse(nonParticipant.readable());
    }

    @Test
    void teamConversationIsReadableByMembersAndWritableByActiveParticipants() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Conversation conversation = fixture.conversation().changeVisibility(
                ConversationVisibility.TEAM, fixture.owner, ConversationDomainFixture.LATER);
        Principal colleague = ConversationDomainFixture.activeUser(
                fixture.organizationId, "Colleague");
        TeamMember member = fixture.activeMember(colleague);
        ConversationParticipant participant = ConversationParticipant.joinMember(
                conversation,
                member,
                colleague,
                fixture.owner,
                ConversationDomainFixture.LATER);

        ConversationAccessDecision memberRead =
                policy.forMember(conversation, member, colleague, Optional.empty());
        ConversationAccessDecision participantWrite =
                policy.forMember(conversation, member, colleague, Optional.of(participant));

        assertTrue(memberRead.discoverable());
        assertTrue(memberRead.readable());
        assertFalse(memberRead.writable());
        assertTrue(participantWrite.writable());
    }

    @Test
    void formerParticipantReadsOnlyMessagesCreatedThroughItsInclusiveLeaveTime() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Conversation conversation = fixture.conversation().changeVisibility(
                ConversationVisibility.TEAM, fixture.owner, ConversationDomainFixture.LATER);
        Principal colleague = ConversationDomainFixture.activeUser(
                fixture.organizationId, "Colleague");
        TeamMember member = fixture.activeMember(colleague);
        ConversationParticipant participant = ConversationParticipant.joinMember(
                conversation,
                member,
                colleague,
                fixture.owner,
                ConversationDomainFixture.LATER);
        UtcTimestamp leftAt = UtcTimestamp.parse("2026-08-08T12:02:00Z");
        ConversationParticipant left = participant.leave(fixture.owner, leftAt);
        Message before = conversation
                .appendMessage(
                        MessageId.generate(),
                        fixture.initialization.ownerParticipant(),
                        fixture.owner,
                        new MessageContent("Before leave"),
                        ConversationDomainFixture.LATER)
                .message();
        Message atCutoff = Message.reconstitute(
                MessageId.generate(),
                conversation.scope(),
                conversation.id(),
                new MessageSequence(2),
                MessageType.SYSTEM_NOTICE,
                Optional.empty(),
                Optional.empty(),
                new MessageContent("At leave"),
                io.crewscope.domain.shared.audit.AuditMetadata.createdBy(
                        fixture.owner.id(), leftAt));
        Message after = Message.reconstitute(
                MessageId.generate(),
                conversation.scope(),
                conversation.id(),
                new MessageSequence(3),
                MessageType.SYSTEM_NOTICE,
                Optional.empty(),
                Optional.empty(),
                new MessageContent("After leave"),
                io.crewscope.domain.shared.audit.AuditMetadata.createdBy(
                        fixture.owner.id(), UtcTimestamp.parse("2026-08-08T12:03:00Z")));

        ConversationAccessDecision access =
                policy.forMember(conversation, member, colleague, Optional.of(left));

        assertTrue(access.readable());
        assertFalse(access.writable());
        assertTrue(access.canRead(before));
        assertTrue(access.canRead(atCutoff));
        assertFalse(access.canRead(after));
    }

    @Test
    void inactiveTeamMembershipImmediatelyRevokesParticipantAccess() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Principal colleague = ConversationDomainFixture.activeUser(
                fixture.organizationId, "Colleague");
        TeamMember member = fixture.activeMember(colleague);
        ConversationParticipant participant = ConversationParticipant.joinMember(
                fixture.conversation(),
                member,
                colleague,
                fixture.owner,
                ConversationDomainFixture.LATER);

        ConversationAccessDecision access = policy.forMember(
                fixture.conversation(),
                member.suspend(UtcTimestamp.parse("2026-08-08T12:02:00Z")),
                colleague,
                Optional.of(participant));

        assertFalse(access.discoverable());
        assertFalse(access.readable());
        assertFalse(access.writable());
    }

    @Test
    void agentRequiresMatchingExplicitParticipationAndArchiveMakesAccessReadOnly() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Principal agent = fixture.team.ownerPersonalAgent().agentPrincipal();
        ConversationAccessDecision active = policy.forAgent(
                fixture.conversation(), fixture.initialization.agentParticipant(), agent);
        Conversation archived = fixture.conversation().archive(
                fixture.owner, ConversationDomainFixture.LATER);
        ConversationAccessDecision archivedAccess = policy.forAgent(
                archived, fixture.initialization.agentParticipant(), agent);
        ConversationAccessDecision mismatched = policy.forAgent(
                fixture.conversation(), fixture.initialization.ownerParticipant(), agent);

        assertTrue(active.writable());
        assertTrue(archivedAccess.readable());
        assertFalse(archivedAccess.writable());
        assertFalse(mismatched.discoverable());
    }

    @Test
    void decisionRejectsMessagesFromAnotherConversation() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        ConversationAccessDecision access = policy.forMember(
                fixture.conversation(),
                fixture.team.ownerMember(),
                fixture.owner,
                Optional.of(fixture.initialization.ownerParticipant()));
        PersonalConversationInitialization another = PersonalConversationInitialization.start(
                ConversationId.generate(),
                fixture.team.defaultWorkspace(),
                fixture.team.ownerMember(),
                fixture.owner,
                fixture.team.ownerPersonalAgent(),
                "Another",
                ConversationVisibility.PRIVATE,
                ConversationDomainFixture.CREATED_AT);
        Message foreign = another.conversation()
                .appendMessage(
                        MessageId.generate(),
                        another.ownerParticipant(),
                        fixture.owner,
                        new MessageContent("Foreign"),
                        ConversationDomainFixture.CREATED_AT)
                .message();

        assertFalse(access.canRead(foreign));
    }
}
