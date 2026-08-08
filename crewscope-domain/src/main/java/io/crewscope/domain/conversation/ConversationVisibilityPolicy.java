package io.crewscope.domain.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;
import java.util.Optional;

/** Resolves discovery, read, write and post-exit history access for Conversation subjects. */
public final class ConversationVisibilityPolicy {

    /** Resolves access for a USER and its current Team membership. */
    public ConversationAccessDecision forMember(
            Conversation conversation,
            TeamMember member,
            Principal user,
            Optional<ConversationParticipant> participation) {
        Conversation requiredConversation = Objects.requireNonNull(conversation, "conversation");
        TeamMember requiredMember = Objects.requireNonNull(member, "member");
        Principal requiredUser = Objects.requireNonNull(user, "user");
        Optional<ConversationParticipant> requiredParticipation =
                Objects.requireNonNull(participation, "participation");
        if (!isCurrentMember(requiredConversation, requiredMember, requiredUser)) {
            return ConversationAccessDecision.denied(requiredConversation.id());
        }
        requiredParticipation.ifPresent(value ->
                requireMatchingParticipation(requiredConversation, requiredMember, requiredUser, value));
        if (requiredConversation.visibility() == ConversationVisibility.TEAM) {
            return requiredParticipation
                    .map(value -> participantAccess(requiredConversation, value))
                    .orElseGet(() -> ConversationAccessDecision.readOnly(requiredConversation.id()));
        }
        return requiredParticipation
                .map(value -> participantAccess(requiredConversation, value))
                .orElseGet(() -> ConversationAccessDecision.denied(requiredConversation.id()));
    }

    /** Resolves access for an Agent that already has an explicit Conversation participation. */
    public ConversationAccessDecision forAgent(
            Conversation conversation,
            ConversationParticipant participation,
            Principal agent) {
        Conversation requiredConversation = Objects.requireNonNull(conversation, "conversation");
        ConversationParticipant requiredParticipation =
                Objects.requireNonNull(participation, "participation");
        Principal requiredAgent = Objects.requireNonNull(agent, "agent");
        boolean agentOutsideTeam = requiredAgent.scope().teamId().isPresent()
                && requiredAgent
                        .scope()
                        .teamId()
                        .filter(requiredConversation.scope().teamId()::equals)
                        .isEmpty();
        if (!requiredAgent.type().isAgent()
                || !requiredAgent.canAct()
                || !requiredAgent
                        .scope()
                        .organizationId()
                        .equals(requiredConversation.scope().organizationId())
                || agentOutsideTeam
                || requiredParticipation.role() != ConversationParticipantRole.AGENT
                || !requiredParticipation.principalId().equals(requiredAgent.id())
                || !requiredParticipation.conversationId().equals(requiredConversation.id())
                || !requiredParticipation.scope().equals(requiredConversation.scope())) {
            return ConversationAccessDecision.denied(requiredConversation.id());
        }
        return participantAccess(requiredConversation, requiredParticipation);
    }

    private static ConversationAccessDecision participantAccess(
            Conversation conversation, ConversationParticipant participant) {
        if (participant.isActive()) {
            return conversation.acceptsMessages()
                    ? ConversationAccessDecision.readWrite(conversation.id())
                    : ConversationAccessDecision.readOnly(conversation.id());
        }
        return participant.leftAt()
                .map(cutoff -> ConversationAccessDecision.historical(conversation.id(), cutoff))
                .orElseGet(() -> ConversationAccessDecision.denied(conversation.id()));
    }

    private static boolean isCurrentMember(
            Conversation conversation, TeamMember member, Principal user) {
        boolean userOutsideTeam = user.scope().teamId().isPresent()
                && user.scope().teamId().filter(conversation.scope().teamId()::equals).isEmpty();
        return member.canParticipate()
                && user.type() == PrincipalType.USER
                && user.canAct()
                && member.userPrincipalId().equals(user.id())
                && member.scope().organizationId().equals(conversation.scope().organizationId())
                && member.scope().teamId().equals(conversation.scope().teamId())
                && user.scope().organizationId().equals(conversation.scope().organizationId())
                && !userOutsideTeam;
    }

    private static void requireMatchingParticipation(
            Conversation conversation,
            TeamMember member,
            Principal user,
            ConversationParticipant participant) {
        if (!participant.conversationId().equals(conversation.id())
                || !participant.scope().equals(conversation.scope())
                || !participant.principalId().equals(user.id())
                || participant.teamMemberId().filter(member.id()::equals).isEmpty()
                || participant.role() == ConversationParticipantRole.AGENT) {
            throw new DomainValidationException(
                    "conversationVisibilityPolicy.participation",
                    "must match the resolved USER, TeamMember and Conversation");
        }
    }
}
