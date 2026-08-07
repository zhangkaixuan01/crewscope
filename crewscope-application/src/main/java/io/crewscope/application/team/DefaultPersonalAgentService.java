package io.crewscope.application.team;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.util.Objects;

/** Idempotently provisions the stable default Personal Agent for one active Team member. */
public final class DefaultPersonalAgentService {

    private final DefaultPersonalAgentRepository repository;
    private final TransactionExecutor transactionExecutor;
    private final TimeProvider timeProvider;

    public DefaultPersonalAgentService(
            DefaultPersonalAgentRepository repository,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactionExecutor =
                Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Returns the same durable Agent pair for retries and concurrent calls for one member. */
    public PersonalAgentInitialization ensureDefault(
            TeamMember member, Workspace workspace, Principal ownerUser) {
        TeamMember requiredMember = Objects.requireNonNull(member, "member");
        Workspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        Principal requiredOwner = Objects.requireNonNull(ownerUser, "ownerUser");
        return transactionExecutor.required(() -> resolve(PersonalAgentInitialization.createDefault(
                requiredMember, requiredWorkspace, requiredOwner, timeProvider.now()),
                requiredMember,
                requiredWorkspace));
    }

    private PersonalAgentInitialization resolve(
            PersonalAgentInitialization candidate,
            TeamMember member,
            Workspace workspace) {
        PersonalAgentInitialization resolved = Objects.requireNonNull(
                repository.initializeIfAbsent(candidate),
                "DefaultPersonalAgentRepository.initializeIfAbsent result");
        return resolved.requireDefaultFor(member, workspace);
    }
}
