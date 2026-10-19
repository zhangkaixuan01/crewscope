package io.crewscope.application.distiller;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;

/**
 * Lazy execution-time readiness boundary for a built-in Distiller (D10): guaranteed by the
 * provisioning service on the first distillation of a Team, never at startup. Readiness is
 * idempotent and its return value carries no caller-facing signal.
 */
@FunctionalInterface
public interface DistillerReadiness {

    void ensureReady(OrganizationId organizationId, TeamId teamId);
}
