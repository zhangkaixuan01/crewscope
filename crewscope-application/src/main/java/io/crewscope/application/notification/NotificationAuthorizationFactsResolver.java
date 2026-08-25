package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationAuthorizationFacts;
import io.crewscope.domain.notification.NotificationIntentId;

/** Resolves all current recipient, provider, grant, policy and preference coordinates. */
public interface NotificationAuthorizationFactsResolver {

    NotificationAuthorizationFacts resolveCurrent(NotificationIntentId intentId);
}
