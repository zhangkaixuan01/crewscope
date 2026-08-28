package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded known-account failure window and temporary lock state for an atomic defense store. */
public final class AccountLoginAttemptState {

    private final List<UtcTimestamp> failures;
    private final Optional<UtcTimestamp> lockedUntil;
    private final UtcTimestamp observedAt;

    private AccountLoginAttemptState(
            List<UtcTimestamp> failures,
            Optional<UtcTimestamp> lockedUntil,
            UtcTimestamp observedAt,
            LoginAttemptPolicy policy) {
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
        this.failures = requireFailures(failures, this.observedAt, policy);
        this.lockedUntil = requireLock(lockedUntil, this.failures, this.observedAt, policy);
    }

    public static AccountLoginAttemptState clear(UtcTimestamp observedAt) {
        return new AccountLoginAttemptState(
                List.of(), Optional.empty(), observedAt, LoginAttemptPolicy.standard());
    }

    /** Reconstitutes state returned by the atomic defense store and rejects impossible shapes. */
    public static AccountLoginAttemptState reconstitute(
            List<UtcTimestamp> failures,
            Optional<UtcTimestamp> lockedUntil,
            UtcTimestamp observedAt,
            LoginAttemptPolicy policy) {
        return new AccountLoginAttemptState(
                failures, lockedUntil, observedAt, Objects.requireNonNull(policy, "policy"));
    }

    public AccountLoginAttemptState recordFailure(
            UtcTimestamp occurredAt, LoginAttemptPolicy policy) {
        LoginAttemptPolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        UtcTimestamp requiredTime = requireMonotonic(occurredAt);
        AccountLoginAttemptState current = observe(requiredTime, requiredPolicy);
        if (current.isTemporarilyLocked(requiredTime)) {
            return current;
        }
        List<UtcTimestamp> currentFailures = new ArrayList<>(current.failures);
        currentFailures.add(requiredTime);
        Optional<UtcTimestamp> targetLock = requiredPolicy.shouldTemporarilyLock(
                        currentFailures.size())
                ? Optional.of(UtcTimestamp.from(
                        requiredTime.value().plus(requiredPolicy.temporaryLockDuration())))
                : Optional.empty();
        return new AccountLoginAttemptState(
                currentFailures, targetLock, requiredTime, requiredPolicy);
    }

    /** Successful authentication clears only account failures, not rate-limit resource windows. */
    public AccountLoginAttemptState recordSuccess(
            UtcTimestamp occurredAt, LoginAttemptPolicy policy) {
        LoginAttemptPolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        UtcTimestamp requiredTime = requireMonotonic(occurredAt);
        AccountLoginAttemptState current = observe(requiredTime, requiredPolicy);
        if (current.isTemporarilyLocked(requiredTime)) {
            throw invalid("cannot record authentication success while temporarily locked");
        }
        return new AccountLoginAttemptState(
                List.of(), Optional.empty(), requiredTime, requiredPolicy);
    }

    /** Advances time, pruning the sliding window and clearing an expired temporary lock. */
    public AccountLoginAttemptState observe(
            UtcTimestamp occurredAt, LoginAttemptPolicy policy) {
        LoginAttemptPolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        UtcTimestamp requiredTime = requireMonotonic(occurredAt);
        if (lockedUntil.isPresent()) {
            if (requiredTime.compareTo(lockedUntil.orElseThrow()) >= 0) {
                return new AccountLoginAttemptState(
                        List.of(), Optional.empty(), requiredTime, requiredPolicy);
            }
            // Preserve the threshold evidence until the active lock expires.
            return new AccountLoginAttemptState(
                    failures, lockedUntil, requiredTime, requiredPolicy);
        }
        UtcTimestamp floor = UtcTimestamp.from(
                requiredTime.value().minus(requiredPolicy.accountFailureWindow()));
        List<UtcTimestamp> activeFailures = failures.stream()
                .filter(failure -> failure.compareTo(floor) > 0)
                .toList();
        return new AccountLoginAttemptState(
                activeFailures, lockedUntil, requiredTime, requiredPolicy);
    }

    public boolean isTemporarilyLocked(UtcTimestamp at) {
        UtcTimestamp requiredTime = requireMonotonic(at);
        return lockedUntil.filter(until -> requiredTime.compareTo(until) < 0).isPresent();
    }

    public int failureCount() {
        return failures.size();
    }

    public List<UtcTimestamp> failures() {
        return failures;
    }

    public Optional<UtcTimestamp> lockedUntil() {
        return lockedUntil;
    }

    public UtcTimestamp observedAt() {
        return observedAt;
    }

    @Override
    public String toString() {
        return "AccountLoginAttemptState[failures="
                + failures.size()
                + ", temporarilyLocked="
                + lockedUntil.isPresent()
                + "]";
    }

    private UtcTimestamp requireMonotonic(UtcTimestamp value) {
        UtcTimestamp required = Objects.requireNonNull(value, "occurredAt");
        if (required.compareTo(observedAt) < 0) {
            throw invalid("time must not move backwards");
        }
        return required;
    }

    private static List<UtcTimestamp> requireFailures(
            List<UtcTimestamp> values,
            UtcTimestamp observedAt,
            LoginAttemptPolicy policy) {
        List<UtcTimestamp> source = Objects.requireNonNull(values, "failures");
        if (source.size() > policy.accountFailureLimit()
                || source.stream().anyMatch(Objects::isNull)) {
            throw invalid("failure window is invalid");
        }
        List<UtcTimestamp> required = List.copyOf(source);
        if (required.stream().anyMatch(value -> value.compareTo(observedAt) > 0)) {
            throw invalid("failure window is invalid");
        }
        List<UtcTimestamp> sorted = required.stream().sorted(Comparator.naturalOrder()).toList();
        if (!required.equals(sorted)) {
            throw invalid("failure window must be ordered");
        }
        return required;
    }

    private static Optional<UtcTimestamp> requireLock(
            Optional<UtcTimestamp> value,
            List<UtcTimestamp> failures,
            UtcTimestamp observedAt,
            LoginAttemptPolicy policy) {
        Optional<UtcTimestamp> required = Objects.requireNonNull(value, "lockedUntil");
        required.ifPresent(until -> {
            if (until.compareTo(observedAt) <= 0) {
                throw invalid("active lock must end after observedAt");
            }
            UtcTimestamp maximumLock = UtcTimestamp.from(
                    observedAt.value().plus(policy.temporaryLockDuration()));
            if (until.compareTo(maximumLock) > 0) {
                throw invalid("active lock exceeds the temporary lock duration");
            }
            if (failures.size() < policy.accountFailureLimit()) {
                throw invalid("active lock requires the failure threshold");
            }
        });
        if (required.isEmpty() && failures.size() >= policy.accountFailureLimit()) {
            throw invalid("failure threshold requires an active lock");
        }
        return required;
    }

    private static DomainValidationException invalid(String reason) {
        return new DomainValidationException("accountLoginAttemptState", reason);
    }
}
