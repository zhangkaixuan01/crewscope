package io.crewscope.application.transaction;

import io.crewscope.domain.shared.time.UtcTimestamp;

/** Database wall-clock time used for ownership, expiry and concurrency decisions. */
@FunctionalInterface
public interface AuthoritativeTimeProvider {
    UtcTimestamp now();
}
