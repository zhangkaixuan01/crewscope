package io.crewscope.domain.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationTest {

    @Test
    void startsPersonalConversationWithOwnerAndAgentParticipants() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Conversation conversation = fixture.conversation();

        assertEquals("Plan the M2 milestone", conversation.title());
        assertEquals(ConversationVisibility.PRIVATE, conversation.visibility());
        assertEquals(ConversationStatus.ACTIVE, conversation.status());
        assertEquals(fixture.team.ownerMember().id(), conversation.ownerMemberId());
        assertEquals(fixture.owner.id(), conversation.ownerPrincipalId());
        assertEquals(
                fixture.team.ownerPersonalAgent().agentPrincipal().id(),
                conversation.personalAgentPrincipalId());
        assertEquals(fixture.team.defaultWorkspace().id(), conversation.scope().workspaceId());
        assertEquals(0, conversation.version());
        assertEquals(fixture.owner.id(), conversation.audit().createdBy().orElseThrow());
    }

    @Test
    void normalizesTitleAndSupportsTeamVisibility() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();

        Conversation conversation = Conversation.startPersonal(
                ConversationId.generate(),
                fixture.team.defaultWorkspace(),
                fixture.team.ownerMember(),
                fixture.owner,
                fixture.team.ownerPersonalAgent(),
                "  Shared delivery discussion  ",
                ConversationVisibility.TEAM,
                ConversationDomainFixture.CREATED_AT);

        assertEquals("Shared delivery discussion", conversation.title());
        assertEquals(ConversationVisibility.TEAM, conversation.visibility());
    }

    @Test
    void changesVisibilityAndArchivesWithVersionedAudit() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Conversation shared = fixture.conversation().changeVisibility(
                ConversationVisibility.TEAM, fixture.owner, ConversationDomainFixture.LATER);
        UtcTimestamp archivedAt = UtcTimestamp.parse("2026-08-08T12:02:00Z");
        Conversation archived = shared.archive(fixture.owner, archivedAt);

        assertEquals(ConversationVisibility.TEAM, shared.visibility());
        assertEquals(1, shared.version());
        assertEquals(ConversationDomainFixture.LATER, shared.audit().updatedAt());
        assertEquals(ConversationStatus.ARCHIVED, archived.status());
        assertEquals(2, archived.version());
        assertEquals(archivedAt, archived.audit().updatedAt());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.archive(
                        fixture.owner, UtcTimestamp.parse("2026-08-08T12:03:00Z")));
        assertThrows(
                DomainValidationException.class,
                () -> archived.changeVisibility(
                        ConversationVisibility.PRIVATE,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-08T12:03:00Z")));
    }

    @Test
    void allocatesGapFreeMessageSequencesAndAdvancesConversationActivity() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        ConversationMessageAppend first = fixture.conversation().appendMessage(
                MessageId.generate(),
                fixture.initialization.ownerParticipant(),
                fixture.owner,
                new MessageContent("First"),
                ConversationDomainFixture.LATER);
        ConversationMessageAppend second = first.conversation().appendSystemNotice(
                MessageId.generate(),
                fixture.owner,
                new MessageContent("Second"),
                UtcTimestamp.parse("2026-08-08T12:02:00Z"));
        ConversationMessageAppend third = second.conversation().appendMessage(
                MessageId.generate(),
                fixture.initialization.ownerParticipant(),
                fixture.owner,
                new MessageContent("Third"),
                UtcTimestamp.parse("2026-08-08T12:03:00Z"));

        assertEquals(new MessageSequence(1), first.message().sequence());
        assertEquals(new MessageSequence(2), second.message().sequence());
        assertEquals(new MessageSequence(3), third.message().sequence());
        assertEquals(Optional.of(new MessageSequence(3)), third.conversation().lastMessageSequence());
        assertEquals(3, third.conversation().version());
        assertEquals(
                UtcTimestamp.parse("2026-08-08T12:03:00Z"),
                third.conversation().audit().updatedAt());
        assertEquals(fixture.owner.id(), third.conversation().audit().updatedBy().orElseThrow());
    }

    @Test
    void rejectsMessageAppendAfterArchiveAndSequenceOverflow() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Conversation archived = fixture.conversation().archive(
                fixture.owner, ConversationDomainFixture.LATER);
        Conversation exhausted = Conversation.reconstitute(
                fixture.conversation().id(),
                fixture.conversation().scope(),
                fixture.conversation().ownerMemberId(),
                fixture.conversation().ownerPrincipalId(),
                fixture.conversation().personalAgentPrincipalId(),
                fixture.conversation().title(),
                fixture.conversation().visibility(),
                ConversationStatus.ACTIVE,
                Optional.of(new MessageSequence(Long.MAX_VALUE)),
                5,
                fixture.conversation().audit());

        assertThrows(
                DomainValidationException.class,
                () -> archived.appendMessage(
                        MessageId.generate(),
                        fixture.initialization.ownerParticipant(),
                        fixture.owner,
                        new MessageContent("Blocked"),
                        UtcTimestamp.parse("2026-08-08T12:02:00Z")));
        assertThrows(
                DomainValidationException.class,
                () -> exhausted.appendMessage(
                        MessageId.generate(),
                        fixture.initialization.ownerParticipant(),
                        fixture.owner,
                        new MessageContent("Overflow"),
                        ConversationDomainFixture.LATER));
    }

    @Test
    void initializationUsesDistinctStableParticipantIdentities() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        PersonalConversationInitialization initialization = fixture.initialization;

        assertNotEquals(
                initialization.ownerParticipant().id(),
                initialization.agentParticipant().id());
        assertEquals(
                ConversationParticipantId.forPrincipal(
                        fixture.conversation().id(), fixture.owner.id()),
                initialization.ownerParticipant().id());
        assertEquals(
                ConversationParticipantId.forPrincipal(
                        fixture.conversation().id(),
                        fixture.team.ownerPersonalAgent().agentPrincipal().id()),
                initialization.agentParticipant().id());
        assertTrue(initialization.ownerParticipant().isActive());
        assertTrue(initialization.agentParticipant().isActive());
    }

    @Test
    void rejectsInactiveMemberAndArchivedWorkspace() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();

        DomainValidationException inactiveMember = assertThrows(
                DomainValidationException.class,
                () -> Conversation.startPersonal(
                        ConversationId.generate(),
                        fixture.team.defaultWorkspace(),
                        fixture.team.ownerMember().suspend(ConversationDomainFixture.LATER),
                        fixture.owner,
                        fixture.team.ownerPersonalAgent(),
                        "Blocked",
                        ConversationVisibility.PRIVATE,
                        ConversationDomainFixture.LATER));
        DomainValidationException archivedWorkspace = assertThrows(
                DomainValidationException.class,
                () -> Conversation.startPersonal(
                        ConversationId.generate(),
                        fixture.team.defaultWorkspace().archive(
                                fixture.owner.id(), ConversationDomainFixture.LATER),
                        fixture.team.ownerMember(),
                        fixture.owner,
                        fixture.team.ownerPersonalAgent(),
                        "Blocked",
                        ConversationVisibility.PRIVATE,
                        ConversationDomainFixture.LATER));

        assertEquals("conversation.ownerMemberId", inactiveMember.error().details().get("field"));
        assertEquals("conversation.workspaceId", archivedWorkspace.error().details().get("field"));
    }

    @Test
    void rejectsPersonalAgentFromAnotherTeam() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        TeamInitialization otherTeam = TeamInitialization.create(
                fixture.owner, "Other team", ConversationDomainFixture.CREATED_AT);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> Conversation.startPersonal(
                        ConversationId.generate(),
                        fixture.team.defaultWorkspace(),
                        fixture.team.ownerMember(),
                        fixture.owner,
                        otherTeam.ownerPersonalAgent(),
                        "Wrong Agent",
                        ConversationVisibility.PRIVATE,
                        ConversationDomainFixture.CREATED_AT));

        assertEquals(
                "personalAgentInitialization.agentProfile",
                failure.error().details().get("field"));
    }

    @Test
    void rejectsInvalidTitleVersionAndDuplicateUserAgentIdentity() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();

        assertThrows(
                DomainValidationException.class,
                () -> Conversation.startPersonal(
                        ConversationId.generate(),
                        fixture.team.defaultWorkspace(),
                        fixture.team.ownerMember(),
                        fixture.owner,
                        fixture.team.ownerPersonalAgent(),
                        " ",
                        ConversationVisibility.PRIVATE,
                        ConversationDomainFixture.CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> Conversation.reconstitute(
                        ConversationId.generate(),
                        fixture.conversation().scope(),
                        fixture.team.ownerMember().id(),
                        fixture.owner.id(),
                        fixture.team.ownerPersonalAgent().agentPrincipal().id(),
                        "Valid",
                        ConversationVisibility.PRIVATE,
                        ConversationStatus.ACTIVE,
                        Optional.empty(),
                        -1,
                        AuditMetadata.createdBy(
                                fixture.owner.id(), ConversationDomainFixture.CREATED_AT)));
        DomainValidationException duplicateIdentity = assertThrows(
                DomainValidationException.class,
                () -> Conversation.reconstitute(
                        ConversationId.generate(),
                        fixture.conversation().scope(),
                        fixture.team.ownerMember().id(),
                        fixture.owner.id(),
                        fixture.owner.id(),
                        "Invalid",
                        ConversationVisibility.PRIVATE,
                        ConversationStatus.ACTIVE,
                        Optional.empty(),
                        0,
                        new AuditMetadata(
                                Optional.of(fixture.owner.id()),
                                ConversationDomainFixture.CREATED_AT,
                                Optional.of(PrincipalId.generate()),
                                ConversationDomainFixture.CREATED_AT)));

        assertEquals(
                "conversation.personalAgentPrincipalId",
                duplicateIdentity.error().details().get("field"));
    }
}
