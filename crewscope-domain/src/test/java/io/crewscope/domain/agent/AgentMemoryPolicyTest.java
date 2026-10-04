package io.crewscope.domain.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Contract of the built-in memory policy (M10-I02a, S01 §3.6 frozen defaults). */
final class AgentMemoryPolicyTest {

    @Test
    void defaultsCarryTheFrozenTtlCapacityAndValueBound() {
        AgentMemoryPolicy policy = AgentMemoryPolicy.defaults();

        assertEquals(AgentMemoryPolicy.DEFAULT_POLICY_ID, policy.policyId());
        assertEquals(1, policy.version());
        assertEquals(90, policy.ttlDays());
        assertEquals(100, policy.maxEntriesPerOwner());
        assertEquals(1024, policy.valueMaxBytes());
    }

    @Test
    void referenceRoundTripsIdentityAndVersion() {
        AgentMemoryPolicyReference reference = AgentMemoryPolicy.defaults().reference();

        assertEquals(AgentMemoryPolicy.DEFAULT_POLICY_ID, reference.policyId());
        assertEquals(1, reference.version());
    }

    @Test
    void nonPositiveNumbersAndVersionsAreRejected() {
        UUID id = AgentMemoryPolicy.DEFAULT_POLICY_ID;
        assertRejects(id, 0, 90, 100, 1024, "agentMemoryPolicy.version");
        assertRejects(id, 1, 0, 100, 1024, "agentMemoryPolicy.ttlDays");
        assertRejects(id, 1, 90, 0, 1024, "agentMemoryPolicy.maxEntriesPerOwner");
        assertRejects(id, 1, 90, 100, 0, "agentMemoryPolicy.valueMaxBytes");
        assertThrows(NullPointerException.class, () -> new AgentMemoryPolicy(null, 1, 90, 100, 1024));
    }

    private static void assertRejects(
            UUID policyId, long version, int ttlDays, int maxEntries, int valueMaxBytes,
            String expectedField) {
        DomainValidationException rejected = assertThrows(
                DomainValidationException.class,
                () -> new AgentMemoryPolicy(policyId, version, ttlDays, maxEntries, valueMaxBytes));
        assertEquals(expectedField, rejected.error().details().get("field"));
    }
}
