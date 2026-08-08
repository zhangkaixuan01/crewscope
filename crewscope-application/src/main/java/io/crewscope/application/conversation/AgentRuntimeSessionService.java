package io.crewscope.application.conversation;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.util.Objects;

/** Idempotently establishes the trusted Conversation-to-AgentScope runtime binding. */
public final class AgentRuntimeSessionService {

    private final AgentRuntimeSessionRepository repository;
    private final TransactionExecutor transactionExecutor;
    private final TimeProvider timeProvider;

    public AgentRuntimeSessionService(
            AgentRuntimeSessionRepository repository,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactionExecutor = Objects.requireNonNull(
                transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Returns one durable session for retries and concurrent requests of the same binding. */
    public AgentRuntimeSession ensurePersonal(
            Conversation conversation,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            PersonalAgentInitialization personalAgent) {
        Conversation requiredConversation = Objects.requireNonNull(
                conversation, "conversation");
        Workspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        TeamMember requiredMember = Objects.requireNonNull(ownerMember, "ownerMember");
        Principal requiredOwner = Objects.requireNonNull(ownerUser, "ownerUser");
        PersonalAgentInitialization requiredAgent = Objects.requireNonNull(
                personalAgent, "personalAgent");
        return transactionExecutor.required(() -> {
            AgentRuntimeSession candidate = AgentRuntimeSession.initializePersonal(
                    requiredConversation,
                    requiredWorkspace,
                    requiredMember,
                    requiredOwner,
                    requiredAgent,
                    timeProvider.now());
            AgentRuntimeSession resolved = Objects.requireNonNull(
                    repository.initializeIfAbsent(candidate),
                    "AgentRuntimeSessionRepository.initializeIfAbsent result");
            return resolved.requireBinding(
                    requiredConversation,
                    requiredWorkspace,
                    requiredMember,
                    requiredOwner,
                    requiredAgent);
        });
    }
}
