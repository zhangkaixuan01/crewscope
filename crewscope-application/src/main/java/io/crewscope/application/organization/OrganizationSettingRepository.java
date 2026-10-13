package io.crewscope.application.organization;

import io.crewscope.domain.organization.setting.OrganizationSetting;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Persistence Port for the OrganizationSetting aggregate (V68). */
public interface OrganizationSettingRepository {

    /** Empty means the default state: retention forever, surfaced without materializing a row. */
    Optional<OrganizationSetting> findByOrganization(OrganizationId organizationId);

    /** Inserts the first settings row (upsert head). */
    OrganizationSetting insert(OrganizationSetting setting);

    /** Persists a change under optimistic versioning. */
    OrganizationSetting update(OrganizationSetting setting);
}
