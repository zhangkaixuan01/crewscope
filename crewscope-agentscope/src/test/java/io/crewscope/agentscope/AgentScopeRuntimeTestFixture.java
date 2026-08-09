package io.crewscope.agentscope;

import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentRuntimeSessionStatus;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.conversation.MessageSequence;
import io.crewscope.domain.conversation.MessageType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.application.execution.PlatformExecutionContext;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.workspace.WorkspaceType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;

/** Trusted Conversation fixtures shared by the M2-I03 factory and runtime integration tests. */
final class AgentScopeRuntimeTestFixture {

    static final UtcTimestamp OCCURRED_AT =
            UtcTimestamp.parse("2026-08-09T05:00:00Z");

    private AgentScopeRuntimeTestFixture() {}

    static AgentRuntimeSession session(AgentProfileId profileId, long profileVersion) {
        ConversationScope scope = new ConversationScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate());
        ConversationId conversationId = ConversationId.generate();
        TeamMemberId ownerMemberId = TeamMemberId.generate();
        PrincipalId ownerPrincipalId = PrincipalId.generate();
        PrincipalId agentPrincipalId = PrincipalId.generate();
        AgentRuntimeSessionId sessionId = AgentRuntimeSessionId.forPersonalConversation(
                conversationId, ownerMemberId, agentPrincipalId);
        return AgentRuntimeSession.reconstitute(
                sessionId,
                scope,
                conversationId,
                ownerMemberId,
                ownerPrincipalId,
                agentPrincipalId,
                profileId,
                profileVersion,
                AgentScopeSessionKey.forPersonalConversation(
                        scope.organizationId(),
                        ownerMemberId,
                        agentPrincipalId,
                        conversationId,
                        sessionId),
                AgentRuntimeStateReference.forSession(sessionId),
                AgentRuntimeSessionStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(ownerPrincipalId, OCCURRED_AT));
    }

    static Message userMessage(AgentRuntimeSession session, String text, long sequence) {
        return Message.reconstitute(
                MessageId.generate(),
                session.scope(),
                session.conversationId(),
                new MessageSequence(sequence),
                MessageType.USER_MESSAGE,
                Optional.of(ConversationParticipantId.forPrincipal(
                        session.conversationId(), session.ownerPrincipalId())),
                Optional.of(session.ownerPrincipalId()),
                new MessageContent(text),
                AuditMetadata.createdBy(session.ownerPrincipalId(), OCCURRED_AT));
    }

    static PlatformExecutionContext platformContext(
            AgentRuntimeSession session,
            RuntimeInvocationId invocationId,
            UUID correlationId) {
        return new PlatformExecutionContext(
                session.scope(),
                WorkspaceType.TEAM,
                session.ownerPrincipalId(),
                session.ownerMemberId(),
                Set.of(BuiltInTeamRole.MEMBER.key()),
                BuiltInTeamRole.MEMBER.permissions(),
                session.personalAgentPrincipalId(),
                session.agentProfileId(),
                session.agentProfileVersion(),
                session.conversationId(),
                ConversationVisibility.PRIVATE,
                ConversationParticipantId.forPrincipal(
                        session.conversationId(), session.ownerPrincipalId()),
                ConversationParticipantId.forPrincipal(
                        session.conversationId(), session.personalAgentPrincipalId()),
                session.id(),
                session.agentScopeKey(),
                invocationId,
                correlationId,
                Set.of(),
                Map.of());
    }
}
