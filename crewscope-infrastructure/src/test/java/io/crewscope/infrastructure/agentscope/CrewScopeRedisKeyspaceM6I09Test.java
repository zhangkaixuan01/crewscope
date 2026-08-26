package io.crewscope.infrastructure.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** M6-I09 ownership-scope contract: state is shared while role leases are isolated. */
class CrewScopeRedisKeyspaceM6I09Test {

    @Test
    void isolatesRoleOwnershipWithoutChangingTheSharedAgentStatePrefix() {
        CrewScopeRedisKeyspace keyspace = new CrewScopeRedisKeyspace("team-beta");

        assertEquals("crewscope:team-beta:agentscope:v1:", keyspace.distributedStorePrefix());
        assertEquals(
                "crewscope:team-beta:agentscope:v1:ownership:active-instance:server",
                keyspace.activeExecutionOwnerKey("server"));
        assertEquals(
                "crewscope:team-beta:agentscope:v1:ownership:active-instance:worker",
                keyspace.activeExecutionOwnerKey("worker"));
        assertEquals(keyspace.activeExecutionOwnerKey(),
                keyspace.activeExecutionOwnerKey("default"));
    }

    @Test
    void rejectsUnsafeOwnershipScopes() {
        CrewScopeRedisKeyspace keyspace = new CrewScopeRedisKeyspace("team-beta");

        assertThrows(IllegalArgumentException.class,
                () -> keyspace.activeExecutionOwnerKey("Worker/../../other"));
    }
}
