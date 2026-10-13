package io.crewscope.application.quota;

import io.crewscope.domain.organization.quota.OrganizationQuotaConfiguration;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Persistence port for the single quota configuration row per organization (V70). */
public interface OrganizationQuotaConfigurationRepository {

    Optional<OrganizationQuotaConfiguration> findByOrganization(OrganizationId organizationId);

    OrganizationQuotaConfiguration insert(OrganizationQuotaConfiguration configuration);

    OrganizationQuotaConfiguration update(OrganizationQuotaConfiguration configuration);
}
