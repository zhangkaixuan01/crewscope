package io.crewscope.application.notification;

import java.time.Duration;

/** Issues one short-lived credential capability for one exact claimed notification action. */
@FunctionalInterface
public interface NotificationCredentialIssuer {

    NotificationCredentialHandle issue(
            NotificationPlan plan, NotificationClaim claim, Duration timeToLive);
}
