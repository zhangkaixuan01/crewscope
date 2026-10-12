package io.crewscope.application.organization;

import io.crewscope.domain.organization.Organization;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/**
 * Persistence Port for the Organization aggregate (ADR-035).
 *
 * <p>Read-only by design in M12-D01: the single-organization profile keeps the bootstrap seeder
 * as the only organization writer, so the port starts with the lookup A01/F01 need for settings
 * and identity surfaces. Write operations, if ever introduced, arrive with the hosted increment
 * under an explicit ADR-035 revision — not by accreting methods here.
 */
public interface OrganizationRepository {

    /** Returns the organization by id, or empty when no such row exists. */
    Optional<Organization> findById(OrganizationId organizationId);
}
