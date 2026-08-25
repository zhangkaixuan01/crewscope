package io.crewscope.domain.notification;

import java.util.Objects;

/** Exact immutable version of a fixed notification template. */
public record NotificationTemplateRef(
        NotificationTemplateId templateId, NotificationTemplateVersion version) {

    public NotificationTemplateRef {
        templateId = Objects.requireNonNull(templateId, "templateId");
        version = Objects.requireNonNull(version, "version");
    }
}
