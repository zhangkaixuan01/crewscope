package io.crewscope.infrastructure.event.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Verifies that a projection keeps one canonical identity for receipts and checkpoints. */
class CheckpointedProjectionRunnerTest {

    @Test
    void capturesProjectionNameOnce() {
        AtomicReference<String> configuredName = new AtomicReference<>("audit-event-v1");
        ProjectionHandler handler = handler(configuredName);

        CheckpointedProjectionRunner runner = runner(handler);
        configuredName.set("changed-at-runtime");

        assertEquals("projection:audit-event-v1", runner.consumerName());
    }

    @Test
    void rejectsProjectionNameWithOuterWhitespace() {
        ProjectionHandler handler = handler(new AtomicReference<>(" audit-event-v1 "));

        assertThrows(IllegalArgumentException.class, () -> runner(handler));
    }

    private static CheckpointedProjectionRunner runner(ProjectionHandler handler) {
        return new CheckpointedProjectionRunner(
                handler,
                mock(JdbcProjectionCheckpointStore.class),
                mock(ProjectionEventJsonMapper.class),
                Clock.systemUTC());
    }

    private static ProjectionHandler handler(AtomicReference<String> configuredName) {
        return new ProjectionHandler() {
            @Override
            public String projectionName() {
                return configuredName.get();
            }

            @Override
            public void project(ProjectionEvent event) {
                // No projection is executed in constructor contract tests.
            }
        };
    }
}
