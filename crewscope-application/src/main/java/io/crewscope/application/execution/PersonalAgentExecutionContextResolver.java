package io.crewscope.application.execution;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.UUID;

/** Rebuilds server-owned Personal Agent Session and execution facts for every operation. */
@FunctionalInterface
public interface PersonalAgentExecutionContextResolver {

    /** Verifies owner authority before an Invocation endpoint creates a USER Message fact. */
    default void requireOwner(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            ConversationId conversationId,
            UUID correlationId) {
        // Custom adapters remain fail-closed by performing a complete resolution with a throwaway
        // Invocation coordinate. The repository adapter overrides this to avoid Session creation.
        resolve(
                access,
                organizationId,
                teamId,
                conversationId,
                RuntimeInvocationId.generate(),
                correlationId);
    }

    ResolvedPersonalAgentExecution resolve(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            ConversationId conversationId,
            RuntimeInvocationId invocationId,
            UUID correlationId);
}
