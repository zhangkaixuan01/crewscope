package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationTemplateStatus;
import io.crewscope.domain.notification.NotificationVariableType;
import java.util.List;
import java.util.Objects;

/** Safe fixed-template catalog row exposing schema metadata but never rendered content. */
public record NotificationTemplateView(
        NotificationTemplateRef ref,
        String serverTemplateKey,
        NotificationTemplateStatus status,
        List<VariableView> variables) {

    public NotificationTemplateView {
        ref = Objects.requireNonNull(ref, "ref");
        serverTemplateKey = Objects.requireNonNull(serverTemplateKey, "serverTemplateKey");
        status = Objects.requireNonNull(status, "status");
        variables = List.copyOf(Objects.requireNonNull(variables, "variables"));
    }

    public record VariableView(String name, NotificationVariableType type, int maximumLength) {
        public VariableView {
            name = Objects.requireNonNull(name, "name");
            type = Objects.requireNonNull(type, "type");
            if (maximumLength < 1) {
                throw new IllegalArgumentException("maximumLength must be positive");
            }
        }
    }
}
