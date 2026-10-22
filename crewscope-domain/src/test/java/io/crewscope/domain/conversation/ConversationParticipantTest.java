package io.crewscope.domain.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.team.TeamMember;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationParticipantTest {

    @Test
    void joinsActiveTeamMemberWithStableIdentityAndAuditActor() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Principal colleague = ConversationDomainFixture.activeUser(
                fixture.organizationId, "Colleague");
        TeamMember member = fixture.activeMember(colleague);

        ConversationParticipant first = ConversationParticipant.joinMember(
                fixture.conversation(),
                member,
                colleague,
                fixture.owner,
                ConversationDomainFixture.LATER);
        ConversationParticipant retry = ConversationParticipant.joinMember(
                fixture.conversation(),
                member,
                colleague,
                fixture.owner,
                ConversationDomainFixture.LATER);

        assertEquals(first.id(), retry.id());
        assertEquals(
                ConversationParticipantId.forPrincipal(
                        fixture.conversation().id(), colleague.id()),
                first.id());
        assertEquals(ConversationParticipantRole.MEMBER, first.role());
        assertEquals(member.id(), first.teamMemberId().orElseThrow());
        assertEquals(fixture.owner.id(), first.joinedByPrincipalId());
    }

    @Test
    void memberLeavesAndReactivatesWithoutLosingOriginalJoinFact() {
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
        UtcTimestamp leftAt = UtcTimestamp.parse("2026-08-08T12:02:00Z");
        UtcTimestamp reactivatedAt = UtcTimestamp.parse("2026-08-08T12:03:00Z");

        ConversationParticipant left = participant.leave(fixture.owner, leftAt);
        ConversationParticipant active = left.reactivateMember(
                fixture.conversation(),
                member,
                colleague,
                fixture.owner,
                reactivatedAt);

        assertEquals(ConversationParticipantStatus.LEFT, left.status());
        assertEquals(leftAt, left.leftAt().orElseThrow());
        assertEquals(1, left.version());
        assertEquals(ConversationParticipantStatus.ACTIVE, active.status());
        assertTrue(active.leftAt().isEmpty());
        assertEquals(2, active.version());
        assertEquals(participant.id(), active.id());
        assertEquals(participant.joinedAt(), active.joinedAt());
        assertEquals(participant.audit().createdAt(), active.audit().createdAt());
        assertEquals(reactivatedAt, active.audit().updatedAt());
        assertEquals(fixture.owner.id(), active.audit().updatedBy().orElseThrow());
    }

    @Test
    void rejectsDuplicateLifecycleOperationsAndProtectedParticipantsLeaving() {
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
        ConversationParticipant left = participant.leave(
                fixture.owner, UtcTimestamp.parse("2026-08-08T12:02:00Z"));

        assertThrows(
                DomainValidationException.class,
                () -> left.leave(
                        fixture.owner, UtcTimestamp.parse("2026-08-08T12:03:00Z")));
        assertThrows(
                DomainValidationException.class,
                () -> participant.reactivateMember(
                        fixture.conversation(),
                        member,
                        colleague,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-08T12:03:00Z")));
        assertThrows(
                DomainValidationException.class,
                () -> fixture.initialization.ownerParticipant()
                        .leave(fixture.owner, ConversationDomainFixture.LATER));
        assertThrows(
                DomainValidationException.class,
                () -> fixture.initialization.agentParticipant()
                        .leave(fixture.owner, ConversationDomainFixture.LATER));
        ConversationParticipant owner = fixture.initialization.ownerParticipant();
        assertThrows(
                DomainValidationException.class,
                () -> ConversationParticipant.reconstitute(
                        owner.id(),
                        owner.scope(),
                        owner.conversationId(),
                        owner.principalId(),
                        owner.teamMemberId(),
                        owner.role(),
                        ConversationParticipantStatus.LEFT,
                        owner.joinedByPrincipalId(),
                        owner.joinedAt(),
                        Optional.of(ConversationDomainFixture.LATER),
                        0L,
                        1,
                        owner.audit().modifiedBy(
                                fixture.owner.id(), ConversationDomainFixture.LATER)));
    }

    @Test
    void rejectsInactiveMembershipWhenJoiningOrReactivating() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Principal colleague = ConversationDomainFixture.activeUser(
                fixture.organizationId, "Colleague");
        TeamMember member = fixture.activeMember(colleague);
        TeamMember suspended = member.suspend(ConversationDomainFixture.LATER);

        assertThrows(
                DomainValidationException.class,
                () -> ConversationParticipant.joinMember(
                        fixture.conversation(),
                        suspended,
                        colleague,
                        fixture.owner,
                        ConversationDomainFixture.LATER));
        assertThrows(
                DomainValidationException.class,
                () -> ConversationParticipant.joinMember(
                        fixture.conversation(),
                        fixture.team.ownerMember(),
                        fixture.owner,
                        fixture.owner,
                        ConversationDomainFixture.LATER));

        ConversationParticipant left = ConversationParticipant.joinMember(
                        fixture.conversation(),
                        member,
                        colleague,
                        fixture.owner,
                        ConversationDomainFixture.LATER)
                .leave(fixture.owner, UtcTimestamp.parse("2026-08-08T12:02:00Z"));
        assertThrows(
                DomainValidationException.class,
                () -> left.reactivateMember(
                        fixture.conversation(),
                        suspended,
                        colleague,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-08T12:03:00Z")));
    }

    @Test
    void retriesProduceTheSameOwnerParticipantIdentity() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();

        ConversationParticipant first = ConversationParticipant.joinOwner(
                fixture.conversation(),
                fixture.team.ownerMember(),
                fixture.owner,
                ConversationDomainFixture.CREATED_AT);
        ConversationParticipant retry = ConversationParticipant.joinOwner(
                fixture.conversation(),
                fixture.team.ownerMember(),
                fixture.owner,
                ConversationDomainFixture.CREATED_AT);

        assertEquals(first.id(), retry.id());
        assertEquals(ConversationParticipantRole.OWNER, first.role());
        assertEquals(
                fixture.team.ownerMember().id(), first.teamMemberId().orElseThrow());
        assertEquals(fixture.owner.id(), first.joinedByPrincipalId());
    }

    @Test
    void personalAgentParticipantHasNoUserMembershipAndIsAddedByOwner() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        ConversationParticipant participant = fixture.initialization.agentParticipant();

        assertEquals(ConversationParticipantRole.AGENT, participant.role());
        assertTrue(participant.teamMemberId().isEmpty());
        assertEquals(
                fixture.team.ownerPersonalAgent().agentPrincipal().id(),
                participant.principalId());
        assertEquals(fixture.owner.id(), participant.joinedByPrincipalId());
    }

    @Test
    void rejectsParticipantWithUnstablePairIdentityOrInvalidRoleShape() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        ConversationParticipant owner = fixture.initialization.ownerParticipant();

        DomainValidationException unstableId = assertThrows(
                DomainValidationException.class,
                () -> ConversationParticipant.reconstitute(
                        ConversationParticipantId.generate(),
                        owner.scope(),
                        owner.conversationId(),
                        owner.principalId(),
                        owner.teamMemberId(),
                        owner.role(),
                        owner.status(),
                        owner.joinedByPrincipalId(),
                        owner.joinedAt(),
                        owner.leftAt(),
                        0L,
                        owner.version(),
                        owner.audit()));
        DomainValidationException invalidRole = assertThrows(
                DomainValidationException.class,
                () -> ConversationParticipant.reconstitute(
                        ConversationParticipantId.forPrincipal(
                                owner.conversationId(), owner.principalId()),
                        owner.scope(),
                        owner.conversationId(),
                        owner.principalId(),
                        Optional.empty(),
                        ConversationParticipantRole.OWNER,
                        owner.status(),
                        owner.joinedByPrincipalId(),
                        owner.joinedAt(),
                        owner.leftAt(),
                        0L,
                        owner.version(),
                        owner.audit()));

        assertEquals("conversationParticipant.id", unstableId.error().details().get("field"));
        assertEquals(
                "conversationParticipant.teamMemberId",
                invalidRole.error().details().get("field"));
    }

    @Test
    void validatesLeftStatusAndTerminalTime() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        Principal colleague = ConversationDomainFixture.activeUser(
                fixture.organizationId, "Colleague");
        TeamMember member = fixture.activeMember(colleague);
        ConversationParticipant participant = ConversationParticipant.joinMember(
                fixture.conversation(),
                member,
                colleague,
                fixture.owner,
                ConversationDomainFixture.CREATED_AT);
        AuditMetadata leftAudit = participant.audit().modifiedBy(
                fixture.owner.id(), ConversationDomainFixture.LATER);

        ConversationParticipant left = ConversationParticipant.reconstitute(
                participant.id(),
                participant.scope(),
                participant.conversationId(),
                participant.principalId(),
                participant.teamMemberId(),
                participant.role(),
                ConversationParticipantStatus.LEFT,
                participant.joinedByPrincipalId(),
                participant.joinedAt(),
                Optional.of(ConversationDomainFixture.LATER),
                0L,
                1,
                leftAudit);

        assertEquals(ConversationParticipantStatus.LEFT, left.status());
        assertEquals(ConversationDomainFixture.LATER, left.leftAt().orElseThrow());
        assertThrows(
                DomainValidationException.class,
                () -> ConversationParticipant.reconstitute(
                        participant.id(),
                        participant.scope(),
                        participant.conversationId(),
                        participant.principalId(),
                        participant.teamMemberId(),
                        participant.role(),
                        ConversationParticipantStatus.LEFT,
                        participant.joinedByPrincipalId(),
                        participant.joinedAt(),
                        Optional.empty(),
                        0L,
                        1,
                        leftAudit));
        assertThrows(
                DomainValidationException.class,
                () -> ConversationParticipant.reconstitute(
                        participant.id(),
                        participant.scope(),
                        participant.conversationId(),
                        participant.principalId(),
                        participant.teamMemberId(),
                        participant.role(),
                        ConversationParticipantStatus.LEFT,
                        participant.joinedByPrincipalId(),
                        participant.joinedAt(),
                        Optional.of(io.crewscope.domain.shared.time.UtcTimestamp.parse(
                                "2026-08-08T11:59:00Z")),
                        0L,
                        1,
                        leftAudit));
    }

    @Test
    void rejectsCreationAuditThatDoesNotMatchJoinFact() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        ConversationParticipant owner = fixture.initialization.ownerParticipant();

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> ConversationParticipant.reconstitute(
                        owner.id(),
                        owner.scope(),
                        owner.conversationId(),
                        owner.principalId(),
                        owner.teamMemberId(),
                        owner.role(),
                        owner.status(),
                        owner.joinedByPrincipalId(),
                        owner.joinedAt(),
                        owner.leftAt(),
                        0L,
                        owner.version(),
                        AuditMetadata.createdBy(
                                PrincipalId.generate(), ConversationDomainFixture.CREATED_AT)));

        assertEquals(
                "conversationParticipant.audit", failure.error().details().get("field"));
    }

    @Test
    void readReceiptsAdvanceTheCursorMonotonicallyAndNeverMoveItBackwards() {
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
        UtcTimestamp receivedAt = UtcTimestamp.parse("2026-08-08T12:02:00Z");

        assertEquals(0, participant.lastReadSequence());
        ConversationParticipant advanced = participant.markRead(
                colleague, new MessageSequence(5), receivedAt);

        assertEquals(5, advanced.lastReadSequence());
        assertEquals(1, advanced.version());
        assertEquals(receivedAt, advanced.audit().updatedAt());
        assertEquals(colleague.id(), advanced.audit().updatedBy().orElseThrow());

        // Same-value and older receipts are no-ops: the cursor never regresses and the
        // stored version does not churn for replays carrying distinct idempotency keys.
        assertSame(advanced, advanced.markRead(colleague, new MessageSequence(5), receivedAt));
        assertSame(advanced, advanced.markRead(colleague, new MessageSequence(3), receivedAt));
        assertEquals(5, advanced.lastReadSequence());
        assertEquals(1, advanced.version());
    }

    @Test
    void readReceiptsPreserveLifecycleFactsAndRejectParticipantsWhoLeft() {
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
        UtcTimestamp receivedAt = UtcTimestamp.parse("2026-08-08T12:02:00Z");
        UtcTimestamp leftAt = UtcTimestamp.parse("2026-08-08T12:03:00Z");

        ConversationParticipant advanced = participant.markRead(
                colleague, new MessageSequence(2), receivedAt);
        ConversationParticipant left = advanced.leave(fixture.owner, leftAt);

        // Leaving retains the historical read boundary for the unread projection.
        assertEquals(2, left.lastReadSequence());
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> left.markRead(colleague, new MessageSequence(4), receivedAt));
        assertEquals(
                "conversationParticipant.status", failure.error().details().get("field"));
    }
}
