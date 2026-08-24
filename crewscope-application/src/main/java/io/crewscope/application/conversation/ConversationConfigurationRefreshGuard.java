package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.function.Supplier;

/** Process-local execution guard for Conversation configuration safe-point refresh. */
@FunctionalInterface
public interface ConversationConfigurationRefreshGuard {

    void requireSafe(
            OrganizationId organizationId, TeamId teamId, ConversationId conversationId);

    /**
     * Runs a configuration mutation inside the same process-local boundary used to start calls.
     * Implementations with an invocation registry override this method to close the check/start
     * race; simple adapters retain the fail-closed check followed by the supplied action.
     */
    default <T> T atSafePoint(
            OrganizationId organizationId,
            TeamId teamId,
            ConversationId conversationId,
            Supplier<T> action) {
        requireSafe(organizationId, teamId, conversationId);
        return Objects.requireNonNull(action, "action").get();
    }
}
