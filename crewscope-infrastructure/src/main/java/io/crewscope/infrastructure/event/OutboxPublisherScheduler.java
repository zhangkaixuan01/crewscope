package io.crewscope.infrastructure.event;

import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Starts polling when the deployment enables the Outbox transport. */
@Component
@ConditionalOnProperty(prefix = "crewscope.outbox", name = "enabled", havingValue = "true")
public class OutboxPublisherScheduler {

    private final PollingOutboxPublisher publisher;

    public OutboxPublisherScheduler(PollingOutboxPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /** Fixed-delay polling prevents one node from overlapping its own previous polling cycle. */
    @Scheduled(fixedDelayString = "${crewscope.outbox.poll-interval:1000}")
    public void publishAvailable() {
        publisher.publishAvailable();
    }
}
