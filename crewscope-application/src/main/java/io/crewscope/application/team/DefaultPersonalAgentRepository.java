package io.crewscope.application.team;

import io.crewscope.domain.workspace.PersonalAgentInitialization;

/** Atomic persistence Port for the Principal and AgentProfile of a default Personal Agent. */
public interface DefaultPersonalAgentRepository {

    /**
     * Persists the candidate when the member has no default Agent, otherwise returns the existing
     * pair. Implementations must serialize by TeamMember, never leave a partial pair and resolve
     * concurrent calls to one committed result.
     */
    PersonalAgentInitialization initializeIfAbsent(PersonalAgentInitialization candidate);
}
