package io.crewscope.application.setup;

import java.util.Objects;
import java.util.Optional;

/** Member-safe readiness item; it deliberately contains no credential or provider internals. */
public record TeamSetupReadinessItem(
        TeamSetupCapability capability,
        boolean required,
        TeamSetupReadinessStatus status,
        String reasonCode,
        boolean canConfigure,
        String responsibleParty,
        Optional<String> actionKey) {

    public TeamSetupReadinessItem {
        capability = Objects.requireNonNull(capability, "capability");
        status = Objects.requireNonNull(status, "status");
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("reasonCode must be a stable uppercase code");
        }
        if (responsibleParty == null || responsibleParty.isBlank()) {
            throw new IllegalArgumentException("responsibleParty must not be blank");
        }
        actionKey = Objects.requireNonNull(actionKey, "actionKey");
        if (status != TeamSetupReadinessStatus.ACTION_REQUIRED && actionKey.isPresent()) {
            throw new IllegalArgumentException("only ACTION_REQUIRED may expose an actionKey");
        }
        if (status == TeamSetupReadinessStatus.READY && !reasonCode.equals("READY")) {
            throw new IllegalArgumentException("READY items must use the READY reason code");
        }
    }

    public boolean ready() {
        return status == TeamSetupReadinessStatus.READY;
    }
}
