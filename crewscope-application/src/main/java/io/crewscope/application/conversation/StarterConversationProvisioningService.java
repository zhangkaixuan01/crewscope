package io.crewscope.application.conversation;

import io.crewscope.application.execution.AgentMessageCandidate;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.application.team.TeamCreationService;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates the starter Conversation inside the Team foundation transaction
 * ({@link TeamCreationService#create}). The conversation itself is the same domain unit the
 * regular creation command assembles; the opening message deliberately goes through
 * {@link ConversationApplicationService#commitAgentMessage} so the seeded greeting is a real
 * Personal Agent reply — same sequence bump, same read-model facts, same conversation event
 * stream — instead of a presentation-layer ghost row.
 */
public final class StarterConversationProvisioningService implements StarterConversationInitializer {

    static final String STARTER_TITLE = "欢迎：从第一个目标开始";

    static final String WELCOME_MARKDOWN = """
            你好！我是你的 Personal Agent，这个对话是我们协作的起点。

            **现在就可以试试**：在下方输入你的第一个目标，比如「帮我规划一次版本发布的检查清单」。

            我会先澄清关键信息，再开始执行；工作项、审查与知识都会关联回这个 Team，随时可回溯。""";

    private final ConversationRepository conversations;
    private final ConversationParticipantRepository participants;
    private final ConversationApplicationService conversationService;
    private final TimeProvider timeProvider;

    public StarterConversationProvisioningService(
            ConversationRepository conversations,
            ConversationParticipantRepository participants,
            ConversationApplicationService conversationService,
            TimeProvider timeProvider) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.participants = Objects.requireNonNull(participants, "participants");
        this.conversationService =
                Objects.requireNonNull(conversationService, "conversationService");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public void initialize(
            Team team,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            PersonalAgentInitialization personalAgent) {
        Team requiredTeam = Objects.requireNonNull(team, "team");
        Workspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        TeamMember requiredOwnerMember = Objects.requireNonNull(ownerMember, "ownerMember");
        Principal requiredOwnerUser = Objects.requireNonNull(ownerUser, "ownerUser");
        PersonalAgentInitialization requiredAgent =
                Objects.requireNonNull(personalAgent, "personalAgent");
        UtcTimestamp occurredAt = timeProvider.now();
        PersonalConversationInitialization starter =
                PersonalConversationInitialization.start(
                        ConversationId.generate(),
                        requiredWorkspace,
                        requiredOwnerMember,
                        requiredOwnerUser,
                        requiredAgent,
                        STARTER_TITLE,
                        ConversationVisibility.PRIVATE,
                        occurredAt);
        conversations.create(starter.conversation());
        participants.create(starter.ownerParticipant());
        participants.create(starter.agentParticipant());
        conversationService.commitAgentMessage(
                new AgentMessageCandidate(
                        RuntimeInvocationId.generate(),
                        UUID.randomUUID(),
                        starter.conversation().id(),
                        starter.agentParticipant().id(),
                        requiredAgent.agentPrincipal().id(),
                        new MessageContent(WELCOME_MARKDOWN),
                        occurredAt),
                requiredTeam.organizationId(),
                UUID.randomUUID(),
                java.util.Optional.empty());
    }
}
