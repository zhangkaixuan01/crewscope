package io.crewscope.application.provider;

import io.crewscope.domain.shared.id.OrganizationId;

/** Transaction-scoped serialization Port for product-owned Provider registry initialization. */
@FunctionalInterface
public interface ProviderBootstrapLock {

  void acquire(OrganizationId organizationId);
}
