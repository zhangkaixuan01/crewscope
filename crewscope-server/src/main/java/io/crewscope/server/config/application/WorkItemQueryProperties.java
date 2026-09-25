package io.crewscope.server.config.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Signed-cursor policy for the M9b-A06 WorkItem list read model. */
@ConfigurationProperties(prefix = "crewscope.work-item-query")
public class WorkItemQueryProperties {

    private Duration cursorMaximumAge = Duration.ofMinutes(30);

    public Duration getCursorMaximumAge() {
        return cursorMaximumAge;
    }

    public void setCursorMaximumAge(Duration cursorMaximumAge) {
        if (cursorMaximumAge == null
                || cursorMaximumAge.compareTo(Duration.ofSeconds(1)) < 0
                || cursorMaximumAge.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException(
                    "cursorMaximumAge must be at least one second and at most one day");
        }
        this.cursorMaximumAge = cursorMaximumAge;
    }
}
