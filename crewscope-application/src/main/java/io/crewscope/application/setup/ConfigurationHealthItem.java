package io.crewscope.application.setup;

import java.util.Objects;
import java.util.Optional;

/** One member-safe health fact with a deterministic next action. */
public record ConfigurationHealthItem(
        String component,
        ConfigurationHealthStatus status,
        String reasonCode,
        String responsibleParty,
        Optional<String> actionKey) {

    public ConfigurationHealthItem {
        if (component == null || component.isBlank()) {
            throw new IllegalArgumentException("component must not be blank");
        }
        status = Objects.requireNonNull(status, "status");
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("reasonCode must be a stable uppercase code");
        }
        if (responsibleParty == null || responsibleParty.isBlank()) {
            throw new IllegalArgumentException("responsibleParty must not be blank");
        }
        actionKey = Objects.requireNonNull(actionKey, "actionKey");
        if (status == ConfigurationHealthStatus.READY && !"READY".equals(reasonCode)) {
            throw new IllegalArgumentException("READY items must use the READY reason code");
        }
        if (status != ConfigurationHealthStatus.ACTION_REQUIRED && actionKey.isPresent()) {
            throw new IllegalArgumentException("only ACTION_REQUIRED may expose an actionKey");
        }
    }
}
