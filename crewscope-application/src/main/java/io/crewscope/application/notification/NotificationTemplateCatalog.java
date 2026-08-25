package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationTemplate;
import io.crewscope.domain.notification.NotificationTemplateRef;

/** Server-owned registry that rejects unknown, retired and superseded template versions. */
public interface NotificationTemplateCatalog {

    NotificationTemplate requireCurrentPublished(NotificationTemplateRef ref);
}
