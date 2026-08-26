package io.crewscope.server.config.application;

import io.crewscope.application.operations.OperationsComponentThreshold;
import io.crewscope.application.operations.OperationsHealthComponent;
import io.crewscope.application.operations.OperationsHealthThresholds;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded lag and backlog thresholds used for member-safe M6 operations health. */
@ConfigurationProperties(prefix = "crewscope.operations-health")
public class OperationsHealthProperties {

    private ThresholdProperties projection = new ThresholdProperties(
            Duration.ofSeconds(30), Duration.ofMinutes(2), 1, 10);
    private ThresholdProperties outbox = new ThresholdProperties(
            Duration.ofSeconds(30), Duration.ofMinutes(2), 10, 100);
    private ThresholdProperties deadLetter = new ThresholdProperties(
            Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1);
    private ThresholdProperties cursor = new ThresholdProperties(
            Duration.ofMinutes(1), Duration.ofMinutes(5), 10, 100);
    private ThresholdProperties notification = new ThresholdProperties(
            Duration.ofMinutes(1), Duration.ofMinutes(5), 10, 100);

    public ThresholdProperties getProjection() {
        return projection;
    }

    public void setProjection(ThresholdProperties projection) {
        this.projection = projection;
    }

    public ThresholdProperties getOutbox() {
        return outbox;
    }

    public void setOutbox(ThresholdProperties outbox) {
        this.outbox = outbox;
    }

    public ThresholdProperties getDeadLetter() {
        return deadLetter;
    }

    public void setDeadLetter(ThresholdProperties deadLetter) {
        this.deadLetter = deadLetter;
    }

    public ThresholdProperties getCursor() {
        return cursor;
    }

    public void setCursor(ThresholdProperties cursor) {
        this.cursor = cursor;
    }

    public ThresholdProperties getNotification() {
        return notification;
    }

    public void setNotification(ThresholdProperties notification) {
        this.notification = notification;
    }

    public OperationsHealthThresholds validatedThresholds() {
        EnumMap<OperationsHealthComponent, OperationsComponentThreshold> values =
                new EnumMap<>(OperationsHealthComponent.class);
        values.put(OperationsHealthComponent.PROJECTION, require(projection, "projection"));
        values.put(OperationsHealthComponent.OUTBOX, require(outbox, "outbox"));
        values.put(OperationsHealthComponent.DEAD_LETTER, require(deadLetter, "deadLetter"));
        values.put(OperationsHealthComponent.CURSOR, require(cursor, "cursor"));
        values.put(OperationsHealthComponent.NOTIFICATION, require(notification, "notification"));
        return new OperationsHealthThresholds(Map.copyOf(values));
    }

    private static OperationsComponentThreshold require(
            ThresholdProperties value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " thresholds must be configured");
        }
        return value.validated();
    }

    /** Mutable Spring binding shape converted to the immutable application contract. */
    public static class ThresholdProperties {

        private Duration degradedAfter;
        private Duration attentionAfter;
        private long degradedBacklog;
        private long attentionBacklog;

        public ThresholdProperties() {}

        ThresholdProperties(
                Duration degradedAfter,
                Duration attentionAfter,
                long degradedBacklog,
                long attentionBacklog) {
            this.degradedAfter = degradedAfter;
            this.attentionAfter = attentionAfter;
            this.degradedBacklog = degradedBacklog;
            this.attentionBacklog = attentionBacklog;
        }

        public Duration getDegradedAfter() {
            return degradedAfter;
        }

        public void setDegradedAfter(Duration degradedAfter) {
            this.degradedAfter = degradedAfter;
        }

        public Duration getAttentionAfter() {
            return attentionAfter;
        }

        public void setAttentionAfter(Duration attentionAfter) {
            this.attentionAfter = attentionAfter;
        }

        public long getDegradedBacklog() {
            return degradedBacklog;
        }

        public void setDegradedBacklog(long degradedBacklog) {
            this.degradedBacklog = degradedBacklog;
        }

        public long getAttentionBacklog() {
            return attentionBacklog;
        }

        public void setAttentionBacklog(long attentionBacklog) {
            this.attentionBacklog = attentionBacklog;
        }

        OperationsComponentThreshold validated() {
            return new OperationsComponentThreshold(
                    degradedAfter, attentionAfter, degradedBacklog, attentionBacklog);
        }
    }
}
