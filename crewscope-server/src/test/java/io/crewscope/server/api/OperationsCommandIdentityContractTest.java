package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.crewscope.domain.shared.id.OrganizationId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the extracted Operations command identity boundary remains deterministic and isolated. */
class OperationsCommandIdentityContractTest {

    private static final OrganizationId ORGANIZATION = OrganizationId.from(
            "00000000-0000-4000-8000-000000000001");

    @Test
    void derivesStableIdsFromTheOpaqueIdempotencyKey() {
        UUID first = OperationsCommandIdentity.recovery(ORGANIZATION, "recovery-key");
        UUID second = OperationsCommandIdentity.recovery(ORGANIZATION, "recovery-key");
        assertEquals(first, second);
        assertNotEquals(first, OperationsCommandIdentity.recovery(ORGANIZATION, "another-key"));
        assertNotEquals(first, OperationsCommandIdentity.projection(ORGANIZATION, "recovery-key").value());
    }
}
