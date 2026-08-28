package io.crewscope.application.identity;

import io.crewscope.domain.identity.LoginAttemptPolicy;
import io.crewscope.domain.identity.AccountLoginAttemptState;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Non-identifying projection of one known Account's Redis failure window and temporary lock. */
public record AccountLoginDefenseState(
        int failureCount,
        Optional<UtcTimestamp> lockedUntil,
        UtcTimestamp observedAt) {

    public AccountLoginDefenseState {
        if (failureCount < 0 || failureCount > LoginAttemptPolicy.standard().accountFailureLimit()) {
            throw new IllegalArgumentException("Account failure count is outside the fixed policy");
        }
        lockedUntil = Objects.requireNonNull(lockedUntil, "lockedUntil");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        if ((failureCount >= LoginAttemptPolicy.standard().accountFailureLimit())
                != lockedUntil.isPresent()) {
            throw new IllegalArgumentException("Account lock and failure threshold do not agree");
        }
        if (lockedUntil.isPresent() && lockedUntil.orElseThrow().compareTo(observedAt) <= 0) {
            throw new IllegalArgumentException("Active account lock must end after observedAt");
        }
    }

    public boolean temporarilyLocked() {
        return lockedUntil.isPresent();
    }

    /** Projects a domain-validated Redis state without exposing individual failure timestamps. */
    public static AccountLoginDefenseState from(AccountLoginAttemptState state) {
        AccountLoginAttemptState required = Objects.requireNonNull(state, "state");
        return new AccountLoginDefenseState(
                required.failureCount(), required.lockedUntil(), required.observedAt());
    }

    @Override
    public String toString() {
        return "AccountLoginDefenseState[failureCount="
                + failureCount
                + ", temporarilyLocked="
                + temporarilyLocked()
                + "]";
    }
}
