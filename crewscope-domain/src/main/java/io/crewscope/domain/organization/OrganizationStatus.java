package io.crewscope.domain.organization;

import io.crewscope.domain.shared.error.DomainValidationException;

/**
 * Organization lifecycle status (ADR-035).
 *
 * <p>The V1 column is {@code VARCHAR(32)} without a CHECK constraint; the only value production
 * has ever written is {@code ACTIVE} (the bootstrap seeder and test fixtures, surveyed 2026-10-12).
 * This enumeration stays at that single observed value on purpose — adding a status (e.g. a hosted
 * suspension state) is an explicit later decision that must update the ownership matrix and
 * ADR-035 together, not a silent default.
 */
public enum OrganizationStatus {

    ACTIVE;

    /** Parses the stored column value; anything but the observed vocabulary fails fast. */
    public static OrganizationStatus from(String storedValue) {
        if (storedValue == null) {
            throw new DomainValidationException("organization.status", "must not be null");
        }
        for (OrganizationStatus status : values()) {
            if (status.name().equals(storedValue)) {
                return status;
            }
        }
        throw new DomainValidationException(
                "organization.status", "unknown organization status: " + storedValue);
    }
}
