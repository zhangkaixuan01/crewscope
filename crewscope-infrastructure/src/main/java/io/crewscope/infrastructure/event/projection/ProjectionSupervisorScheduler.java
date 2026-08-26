package io.crewscope.infrastructure.event.projection;

import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.annotation.Scheduled;

/** Startup recovery, fixed-delay polling and graceful interruption for Projection Supervisor. */
@ConditionalOnProperty(
        prefix = "crewscope.projection.supervisor",
        name = "enabled",
        havingValue = "true")
public final class ProjectionSupervisorScheduler
        implements ApplicationListener<ContextClosedEvent> {

    private final ProjectionSupervisor supervisor;

    public ProjectionSupervisorScheduler(ProjectionSupervisor supervisor) {
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.supervisor.recoverStartup();
    }

    @Scheduled(fixedDelayString = "${crewscope.projection.supervisor.poll-interval:2s}")
    public void supervise() {
        supervisor.runOnce();
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        supervisor.interruptForShutdown();
    }
}
