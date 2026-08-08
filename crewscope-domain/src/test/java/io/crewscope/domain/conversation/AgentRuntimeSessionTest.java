package io.crewscope.domain.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import org.junit.jupiter.api.Test;

class AgentRuntimeSessionTest {

    private static final UtcTimestamp T2 = UtcTimestamp.parse("2026-08-08T12:02:00Z");
    private static final UtcTimestamp T3 = UtcTimestamp.parse("2026-08-08T12:03:00Z");
    private static final UtcTimestamp T4 = UtcTimestamp.parse("2026-08-08T12:04:00Z");

    @Test
    void initializesTrustedPersonalAgentBindingAndStateReferences() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();

        AgentRuntimeSession session = initialize(fixture);

        assertEquals(fixture.conversation().scope(), session.scope());
        assertEquals(fixture.conversation().id(), session.conversationId());
        assertEquals(fixture.team.ownerMember().id(), session.ownerMemberId());
        assertEquals(fixture.owner.id(), session.ownerPrincipalId());
        assertEquals(
                fixture.team.ownerPersonalAgent().agentPrincipal().id(),
                session.personalAgentPrincipalId());
        assertEquals(
                fixture.team.ownerPersonalAgent().agentProfile().id(), session.agentProfileId());
        assertEquals(0, session.agentProfileVersion());
        assertEquals(AgentRuntimeSessionStatus.ACTIVE, session.status());
        assertEquals(0, session.version());
        assertTrue(session.canInvoke());
        assertTrue(session.agentScopeKey().userId().startsWith("crewscope:v1:user:"));
        assertTrue(session.agentScopeKey().sessionId().startsWith("crewscope:v1:session:"));
        assertTrue(session.stateReference().belongsTo(session.id()));
        assertEquals(fixture.owner.id(), session.audit().createdBy().orElseThrow());
    }

    @Test
    void retriesProduceStableRuntimeAndAgentScopeKeys() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();

        AgentRuntimeSession first = initialize(fixture);
        AgentRuntimeSession retry = initialize(fixture);

        assertEquals(first.id(), retry.id());
        assertEquals(first.agentScopeKey(), retry.agentScopeKey());
        assertEquals(first.stateReference(), retry.stateReference());
    }

    @Test
    void isolatesDifferentTeamPersonalAgents() {
        ConversationDomainFixture first = ConversationDomainFixture.create();
        TeamInitialization otherTeam = TeamInitialization.create(
                first.owner, "Other team", ConversationDomainFixture.CREATED_AT);
        PersonalConversationInitialization otherConversation =
                PersonalConversationInitialization.start(
                        ConversationId.generate(),
                        otherTeam.defaultWorkspace(),
                        otherTeam.ownerMember(),
                        first.owner,
                        otherTeam.ownerPersonalAgent(),
                        "Other Team Conversation",
                        ConversationVisibility.PRIVATE,
                        ConversationDomainFixture.CREATED_AT);
        AgentRuntimeSession second = AgentRuntimeSession.initializePersonal(
                otherConversation.conversation(),
                otherTeam.defaultWorkspace(),
                otherTeam.ownerMember(),
                first.owner,
                otherTeam.ownerPersonalAgent(),
                ConversationDomainFixture.CREATED_AT);

        AgentRuntimeSession firstSession = initialize(first);
        assertNotEquals(firstSession.id(), second.id());
        assertNotEquals(firstSession.agentScopeKey().userId(), second.agentScopeKey().userId());
        assertNotEquals(
                firstSession.agentScopeKey().sessionId(), second.agentScopeKey().sessionId());
    }

    @Test
    void rejectsAnotherPersonalAgentAndInactiveConversationFacts() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        TeamInitialization otherTeam = TeamInitialization.create(
                fixture.owner, "Other", ConversationDomainFixture.CREATED_AT);
        Conversation archived = fixture.conversation().archive(
                fixture.owner, ConversationDomainFixture.LATER);

        assertThrows(
                DomainValidationException.class,
                () -> AgentRuntimeSession.initializePersonal(
                        fixture.conversation(),
                        fixture.team.defaultWorkspace(),
                        fixture.team.ownerMember(),
                        fixture.owner,
                        otherTeam.ownerPersonalAgent(),
                        ConversationDomainFixture.CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> AgentRuntimeSession.initializePersonal(
                        archived,
                        fixture.team.defaultWorkspace(),
                        fixture.team.ownerMember(),
                        fixture.owner,
                        fixture.team.ownerPersonalAgent(),
                        ConversationDomainFixture.LATER));
        assertThrows(
                DomainValidationException.class,
                () -> AgentRuntimeSession.initializePersonal(
                        fixture.conversation(),
                        fixture.team.defaultWorkspace(),
                        fixture.team.ownerMember().suspend(ConversationDomainFixture.LATER),
                        fixture.owner,
                        fixture.team.ownerPersonalAgent(),
                        T2));
    }

    @Test
    void disablesAndReactivatesAfterRevalidatingCurrentProfile() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        AgentRuntimeSession active = initialize(fixture);
        AgentRuntimeSession disabled = active.disable(
                0, fixture.owner, ConversationDomainFixture.LATER);
        PersonalAgentInitialization refreshedAgent = refreshedAgent(fixture);

        AgentRuntimeSession reactivated = disabled.activate(
                1,
                fixture.conversation(),
                fixture.team.defaultWorkspace(),
                fixture.team.ownerMember(),
                fixture.owner,
                refreshedAgent,
                fixture.owner,
                T3);

        assertEquals(AgentRuntimeSessionStatus.DISABLED, disabled.status());
        assertFalse(disabled.canInvoke());
        assertEquals(AgentRuntimeSessionStatus.ACTIVE, reactivated.status());
        assertEquals(2, reactivated.agentProfileVersion());
        assertEquals(2, reactivated.version());
        assertEquals(active.agentScopeKey(), reactivated.agentScopeKey());
        assertEquals(active.stateReference(), reactivated.stateReference());
    }

    @Test
    void refreshesOnlyToANewerActiveProfileVersion() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        AgentRuntimeSession active = initialize(fixture);
        PersonalAgentInitialization refreshedAgent = refreshedAgent(fixture);

        AgentRuntimeSession refreshed = active.refreshConfiguration(
                0,
                fixture.conversation(),
                fixture.team.defaultWorkspace(),
                fixture.team.ownerMember(),
                fixture.owner,
                refreshedAgent,
                fixture.owner,
                T3);

        assertEquals(2, refreshed.agentProfileVersion());
        assertEquals(1, refreshed.version());
        assertEquals(active.agentScopeKey(), refreshed.agentScopeKey());
        assertThrows(
                DomainValidationException.class,
                () -> refreshed.refreshConfiguration(
                        1,
                        fixture.conversation(),
                        fixture.team.defaultWorkspace(),
                        fixture.team.ownerMember(),
                        fixture.owner,
                        refreshedAgent,
                        fixture.owner,
                        T4));
    }

    @Test
    void archivesOnlyWithBoundArchivedConversationAndCannotReactivate() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        AgentRuntimeSession active = initialize(fixture);

        assertThrows(
                DomainValidationException.class,
                () -> active.archiveForConversation(
                        0,
                        fixture.conversation(),
                        fixture.owner,
                        ConversationDomainFixture.LATER));

        Conversation archivedConversation = fixture.conversation().archive(
                fixture.owner, ConversationDomainFixture.LATER);
        AgentRuntimeSession archived = active.archiveForConversation(
                0, archivedConversation, fixture.owner, T2);

        assertEquals(AgentRuntimeSessionStatus.ARCHIVED, archived.status());
        assertFalse(archived.canInvoke());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.archiveForConversation(
                        1, archivedConversation, fixture.owner, T3));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.activate(
                        1,
                        fixture.conversation(),
                        fixture.team.defaultWorkspace(),
                        fixture.team.ownerMember(),
                        fixture.owner,
                        fixture.team.ownerPersonalAgent(),
                        fixture.owner,
                        T3));
    }

    @Test
    void rejectsStaleLifecycleVersions() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        AgentRuntimeSession active = initialize(fixture);

        assertThrows(
                OptimisticLockConflictException.class,
                () -> active.disable(1, fixture.owner, ConversationDomainFixture.LATER));
        assertThrows(
                IllegalArgumentException.class,
                () -> active.disable(-1, fixture.owner, ConversationDomainFixture.LATER));
    }

    @Test
    void reconstitutionRejectsForgedIdentityKeysAndStateReference() {
        ConversationDomainFixture fixture = ConversationDomainFixture.create();
        AgentRuntimeSession session = initialize(fixture);
        AgentRuntimeSessionId foreignId = AgentRuntimeSessionId.forPersonalConversation(
                ConversationId.generate(),
                session.ownerMemberId(),
                session.personalAgentPrincipalId());
        AgentScopeSessionKey forgedKey = new AgentScopeSessionKey(
                "crewscope:v1:user:forged", "crewscope:v1:session:forged");

        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(session, foreignId, session.agentScopeKey(), session.stateReference()));
        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(session, session.id(), forgedKey, session.stateReference()));
        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(
                        session,
                        session.id(),
                        session.agentScopeKey(),
                        AgentRuntimeStateReference.forSession(foreignId)));
        assertThrows(
                DomainValidationException.class,
                () -> new AgentRuntimeStateReference("crewscope:agent-state:v1:"));
    }

    private static AgentRuntimeSession initialize(ConversationDomainFixture fixture) {
        return AgentRuntimeSession.initializePersonal(
                fixture.conversation(),
                fixture.team.defaultWorkspace(),
                fixture.team.ownerMember(),
                fixture.owner,
                fixture.team.ownerPersonalAgent(),
                ConversationDomainFixture.CREATED_AT);
    }

    private static PersonalAgentInitialization refreshedAgent(
            ConversationDomainFixture fixture) {
        AgentProfile refreshedProfile = fixture.team.ownerPersonalAgent()
                .agentProfile()
                .disable(fixture.owner.id(), ConversationDomainFixture.LATER)
                .activate(fixture.owner.id(), T2);
        return new PersonalAgentInitialization(
                fixture.team.ownerPersonalAgent().agentPrincipal(), refreshedProfile);
    }

    private static AgentRuntimeSession reconstitute(
            AgentRuntimeSession source,
            AgentRuntimeSessionId id,
            AgentScopeSessionKey key,
            AgentRuntimeStateReference stateReference) {
        return AgentRuntimeSession.reconstitute(
                id,
                source.scope(),
                source.conversationId(),
                source.ownerMemberId(),
                source.ownerPrincipalId(),
                source.personalAgentPrincipalId(),
                source.agentProfileId(),
                source.agentProfileVersion(),
                key,
                stateReference,
                source.status(),
                source.version(),
                AuditMetadata.createdBy(
                        source.ownerPrincipalId(), ConversationDomainFixture.CREATED_AT));
    }
}
