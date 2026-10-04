package io.crewscope.application.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.agent.AgentMemoryPolicy;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The code-catalog contract (M10-I02a): the frozen default resolves under its stable id at
 * exactly version 1, and every other reference — unknown id, future or stale version —
 * answers empty so the service degrades instead of failing the model call.
 */
final class DefaultAgentMemoryPolicyCatalogTest {

    private final DefaultAgentMemoryPolicyCatalog catalog = new DefaultAgentMemoryPolicyCatalog();

    @Test
    void theFrozenDefaultResolvesUnderItsStableId() {
        Optional<AgentMemoryPolicy> resolved =
                catalog.resolve(AgentMemoryPolicy.defaults().reference());

        assertEquals(Optional.of(AgentMemoryPolicy.defaults()), resolved);
        assertEquals(90, resolved.orElseThrow().ttlDays());
        assertEquals(100, resolved.orElseThrow().maxEntriesPerOwner());
        assertEquals(1024, resolved.orElseThrow().valueMaxBytes());
    }

    @Test
    void unknownIdsAndForeignVersionsResolveEmpty() {
        assertTrue(catalog.resolve(new AgentMemoryPolicyReference(
                UUID.randomUUID(), 1)).isEmpty());
        assertTrue(catalog.resolve(new AgentMemoryPolicyReference(
                AgentMemoryPolicy.DEFAULT_POLICY_ID, 2)).isEmpty());
    }
}
