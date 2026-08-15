package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.ExecutionLeasePhase;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Validates phase TTL and heartbeat jitter safety margins at deployment startup. */
class ExecutionLeaseCoordinatorSpecTest {

    @Test
    void keepsHeartbeatAndJitterStrictlyInsideEveryPhaseTtl() {
        ExecutionLeaseCoordinatorSpec spec = spec(
                Duration.ofSeconds(30),
                Duration.ofSeconds(45),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5));

        assertEquals(Duration.ofSeconds(30), spec.durationFor(ExecutionLeasePhase.PREPARE));
        assertEquals(Duration.ofSeconds(45), spec.durationFor(ExecutionLeasePhase.RUN));
        assertThrows(IllegalArgumentException.class, () -> spec(
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5)));
    }

    private ExecutionLeaseCoordinatorSpec spec(
            Duration prepare,
            Duration run,
            Duration heartbeat,
            Duration jitter) {
        return new ExecutionLeaseCoordinatorSpec(
                OrganizationId.generate(),
                new RuntimeEnvironment("test"),
                mock(Principal.class),
                prepare,
                run,
                heartbeat,
                jitter,
                Duration.ofSeconds(5),
                100);
    }
}
