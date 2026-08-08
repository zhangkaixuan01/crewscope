package io.crewscope.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationMessageAppend;
import io.crewscope.domain.conversation.ConversationStatus;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.conversation.MessageSequence;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationCursorTest {

    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-08T12:00:00Z");

    @Test
    void ordersSameTimestampByCanonicalIdAndRecognizesOnlyOlderRows() {
        Fixture fixture = Fixture.create();
        UtcTimestamp activity = UtcTimestamp.parse("2026-08-08T12:03:00Z");
        Conversation highest = fixture.conversation(
                "00000000-0000-0000-0000-000000000003", activity);
        Conversation cursorRow = fixture.conversation(
                "00000000-0000-0000-0000-000000000002", activity);
        Conversation lowest = fixture.conversation(
                "00000000-0000-0000-0000-000000000001", activity);
        Conversation olderTime = fixture.conversation(
                "ffffffff-ffff-ffff-ffff-ffffffffffff",
                UtcTimestamp.parse("2026-08-08T12:02:00Z"));

        List<ConversationId> ordered = List.of(lowest, olderTime, highest, cursorRow).stream()
                .sorted(ConversationListOrder.UPDATED_AT_DESC)
                .map(Conversation::id)
                .toList();
        ConversationListCursor cursor = ConversationListCursor.from(cursorRow);

        assertEquals(
                List.of(highest.id(), cursorRow.id(), lowest.id(), olderTime.id()), ordered);
        assertFalse(cursor.isOlder(highest));
        assertFalse(cursor.isOlder(cursorRow));
        assertTrue(cursor.isOlder(lowest));
        assertTrue(cursor.isOlder(olderTime));
    }

    @Test
    void messageCursorLoadsLowerSequencesAndRejectsAnotherConversation() {
        Fixture fixture = Fixture.create();
        ConversationMessageAppend first = fixture.initialization.conversation().appendMessage(
                MessageId.generate(),
                fixture.initialization.ownerParticipant(),
                fixture.owner,
                new MessageContent("First"),
                CREATED_AT);
        ConversationMessageAppend second = first.conversation().appendMessage(
                MessageId.generate(),
                fixture.initialization.ownerParticipant(),
                fixture.owner,
                new MessageContent("Second"),
                UtcTimestamp.parse("2026-08-08T12:01:00Z"));
        ConversationMessageCursor cursor = new ConversationMessageCursor(
                fixture.initialization.conversation().id(), second.message().sequence());
        PersonalConversationInitialization another = PersonalConversationInitialization.start(
                ConversationId.generate(),
                fixture.team.defaultWorkspace(),
                fixture.team.ownerMember(),
                fixture.owner,
                fixture.team.ownerPersonalAgent(),
                "Another",
                ConversationVisibility.PRIVATE,
                CREATED_AT);
        Message foreign = another.conversation()
                .appendMessage(
                        MessageId.generate(),
                        another.ownerParticipant(),
                        fixture.owner,
                        new MessageContent("Foreign"),
                        CREATED_AT)
                .message();

        assertEquals(cursor, cursor.requireConversation(fixture.initialization.conversation().id()));
        assertTrue(cursor.isOlder(first.message()));
        assertFalse(cursor.isOlder(second.message()));
        assertThrows(IllegalArgumentException.class, () -> cursor.isOlder(foreign));
        assertThrows(
                IllegalArgumentException.class,
                () -> cursor.requireConversation(another.conversation().id()));
    }

    private record Fixture(
            Principal owner,
            TeamInitialization team,
            PersonalConversationInitialization initialization) {

        static Fixture create() {
            OrganizationId organizationId = OrganizationId.generate();
            Principal owner = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    CREATED_AT);
            TeamInitialization team = TeamInitialization.create(owner, "Platform", CREATED_AT);
            PersonalConversationInitialization initialization =
                    PersonalConversationInitialization.start(
                            ConversationId.generate(),
                            team.defaultWorkspace(),
                            team.ownerMember(),
                            owner,
                            team.ownerPersonalAgent(),
                            "Cursor test",
                            ConversationVisibility.PRIVATE,
                            CREATED_AT);
            return new Fixture(owner, team, initialization);
        }

        Conversation conversation(String id, UtcTimestamp updatedAt) {
            Conversation source = initialization.conversation();
            return Conversation.reconstitute(
                    ConversationId.from(id),
                    source.scope(),
                    source.ownerMemberId(),
                    source.ownerPrincipalId(),
                    source.personalAgentPrincipalId(),
                    source.title(),
                    source.visibility(),
                    ConversationStatus.ACTIVE,
                    Optional.empty(),
                    0,
                    AuditMetadata.createdBy(owner.id(), updatedAt));
        }
    }
}
