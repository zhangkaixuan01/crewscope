package io.crewscope.application.identity;

import io.crewscope.domain.shared.id.OrganizationId;

/** Serializes deployment Operator provisioning on the stable Bootstrap Organization row. */
@FunctionalInterface
public interface BootstrapOperatorLock {

    /** Holds the Organization lock until the caller's required transaction completes. */
    void acquire(OrganizationId organizationId);
}
