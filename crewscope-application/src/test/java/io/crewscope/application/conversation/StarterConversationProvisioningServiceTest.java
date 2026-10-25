package io.crewscope.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.execution.AgentMessageCandidate;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationParticipant;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M13i first-run guidance: the starter Conversation is the same domain unit the regular
 * creation command assembles, and its opening message is committed through the public
 * Personal Agent reply path so the seeded greeting carries the same facts as a real reply.
 */
class StarterConversationProvisioningServiceTest {

    private static final UtcTimestamp NOW =
            UtcTimestamp.parse("2026-08-07T18:00:00.123456Z");

    @Test
    void seedsAStarterConversationAndCommitsTheAgentGreetingThroughTheReplyPath() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ConversationParticipantRepository participants =
                mock(ConversationParticipantRepository.class);
        ConversationApplicationService conversationService =
                mock(ConversationApplicationService.class);
        TimeProvider timeProvider =
                TimeProvider.from(
                        Clock.fixed(Instant.parse("2026-08-07T18:00:00.123456789Z"), ZoneOffset.UTC));
        StarterConversationProvisioningService service =
                new StarterConversationProvisioningService(
                        conversations, participants, conversationService, timeProvider);
        Principal owner = activeUser();
        TeamInitialization foundation = TeamInitialization.create(owner, "Platform Crew", NOW);

        service.initialize(
                foundation.team(),
                foundation.defaultWorkspace(),
                foundation.ownerMember(),
                owner,
                foundation.ownerPersonalAgent());

        ArgumentCaptor<Conversation> conversation =
                ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).create(conversation.capture());
        assertEquals(
                StarterConversationProvisioningService.STARTER_TITLE,
                conversation.getValue().title());
        assertEquals(ConversationVisibility.PRIVATE, conversation.getValue().visibility());
        assertEquals(owner.id(), conversation.getValue().ownerPrincipalId());
        ArgumentCaptor<ConversationParticipant> seededParticipant =
                ArgumentCaptor.forClass(ConversationParticipant.class);
        verify(participants, org.mockito.Mockito.times(2)).create(seededParticipant.capture());
        PersonalConversationInitialization expectedParticipants =
                PersonalConversationInitialization.start(
                        conversation.getValue().id(),
                        foundation.defaultWorkspace(),
                        foundation.ownerMember(),
                        owner,
                        foundation.ownerPersonalAgent(),
                        conversation.getValue().title(),
                        conversation.getValue().visibility(),
                        NOW);
        assertEquals(
                expectedParticipants.ownerParticipant().principalId(),
                seededParticipant.getAllValues().get(0).principalId());
        assertEquals(
                expectedParticipants.agentParticipant().principalId(),
                seededParticipant.getAllValues().get(1).principalId());

        verify(conversationService)
                .commitAgentMessage(
                        assertArg(
                                candidate -> {
                                    assertEquals(
                                            conversation.getValue().id(),
                                            candidate.conversationId());
                                    assertEquals(
                                            foundation.ownerPersonalAgent()
                                                    .agentPrincipal()
                                                    .id(),
                                            candidate.authorPrincipalId());
                                    assertEquals(
                                            seededParticipant.getAllValues().get(1).id(),
                                            candidate.participantId());
                                    assertEquals(
                                            StarterConversationProvisioningService
                                                    .WELCOME_MARKDOWN,
                                            candidate.content().markdown());
                                    assertEquals(NOW, candidate.occurredAt());
                                }),
                        eq(foundation.team().organizationId()),
                        any(UUID.class),
                        eq(Optional.empty()));
    }

    private static Principal activeUser() {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(OrganizationId.generate()),
                PrincipalType.USER,
                Optional.empty(),
                "Creator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }
}
