package io.crewscope.domain.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.util.Objects;

/** Conversation and its required USER/Personal Agent participants created as one unit. */
public record PersonalConversationInitialization(
        Conversation conversation,
        ConversationParticipant ownerParticipant,
        ConversationParticipant agentParticipant) {

    public PersonalConversationInitialization {
        conversation = Objects.requireNonNull(conversation, "conversation");
        ownerParticipant = Objects.requireNonNull(ownerParticipant, "ownerParticipant");
        agentParticipant = Objects.requireNonNull(agentParticipant, "agentParticipant");
        validate(conversation, ownerParticipant, agentParticipant);
    }

    /** Builds the complete initial participant set for a Personal Agent Conversation. */
    public static PersonalConversationInitialization start(
            ConversationId conversationId,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            PersonalAgentInitialization personalAgent,
            String title,
            ConversationVisibility visibility,
            UtcTimestamp occurredAt) {
        Conversation conversation = Conversation.startPersonal(
                conversationId,
                workspace,
                ownerMember,
                ownerUser,
                personalAgent,
                title,
                visibility,
                occurredAt);
        ConversationParticipant ownerParticipant = ConversationParticipant.joinOwner(
                conversation, ownerMember, ownerUser, occurredAt);
        ConversationParticipant agentParticipant = ConversationParticipant.joinPersonalAgent(
                conversation, personalAgent, occurredAt);
        return new PersonalConversationInitialization(
                conversation, ownerParticipant, agentParticipant);
    }

    private static void validate(
            Conversation conversation,
            ConversationParticipant ownerParticipant,
            ConversationParticipant agentParticipant) {
        if (!ownerParticipant.conversationId().equals(conversation.id())
                || !agentParticipant.conversationId().equals(conversation.id())
                || !ownerParticipant.scope().equals(conversation.scope())
                || !agentParticipant.scope().equals(conversation.scope())
                || ownerParticipant.role() != ConversationParticipantRole.OWNER
                || agentParticipant.role() != ConversationParticipantRole.AGENT
                || !ownerParticipant.principalId().equals(conversation.ownerPrincipalId())
                || !agentParticipant
                        .principalId()
                        .equals(conversation.personalAgentPrincipalId())
                || ownerParticipant.id().equals(agentParticipant.id())
                || !ownerParticipant.isActive()
                || !agentParticipant.isActive()) {
            throw new DomainValidationException(
                    "personalConversationInitialization.participants",
                    "must contain one active owner and one distinct active Personal Agent");
        }
    }
}
