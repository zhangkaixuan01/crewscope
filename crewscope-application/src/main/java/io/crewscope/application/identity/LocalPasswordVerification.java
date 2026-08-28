package io.crewscope.application.identity;

import java.util.Objects;

/** Non-secret password decision; callers map all unsuccessful decisions to fixed public errors. */
public record LocalPasswordVerification(Decision decision, Upgrade upgrade) {

    public LocalPasswordVerification {
        decision = Objects.requireNonNull(decision, "decision");
        upgrade = Objects.requireNonNull(upgrade, "upgrade");
        if ((decision == Decision.AUTHENTICATED) == (upgrade == Upgrade.NOT_APPLICABLE)) {
            throw new IllegalArgumentException(
                    "Only successful verification can report a password upgrade decision");
        }
    }

    public static LocalPasswordVerification authenticated(Upgrade upgrade) {
        return new LocalPasswordVerification(Decision.AUTHENTICATED, upgrade);
    }

    public static LocalPasswordVerification invalidCredentials() {
        return new LocalPasswordVerification(Decision.INVALID_CREDENTIALS, Upgrade.NOT_APPLICABLE);
    }

    public static LocalPasswordVerification inputRejected() {
        return new LocalPasswordVerification(Decision.INPUT_REJECTED, Upgrade.NOT_APPLICABLE);
    }

    public boolean authenticated() {
        return decision == Decision.AUTHENTICATED;
    }

    public enum Decision {
        AUTHENTICATED,
        INVALID_CREDENTIALS,
        INPUT_REJECTED
    }

    public enum Upgrade {
        NOT_APPLICABLE,
        NOT_REQUIRED,
        REHASHED,
        SKIPPED_CONCURRENT_CHANGE
    }
}
